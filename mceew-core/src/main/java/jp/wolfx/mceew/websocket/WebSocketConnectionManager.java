package jp.wolfx.mceew.websocket;

import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;

/**
 * Owns the single Wolfx WebSocket connection and all of its reconnect state.
 */
public final class WebSocketConnectionManager {
    static final String JMA_QUERY = "query_jmaeqlist";
    static final String CENC_QUERY = "query_cenceqlist";
    static final long BOOTSTRAP_QUERY_INTERVAL_MILLIS = 1200L;
    static final List<String> BOOTSTRAP_QUERIES = List.of(JMA_QUERY, CENC_QUERY);
    static final long CONNECT_TIMEOUT_MILLIS = 20_000L;
    static final long KEEPALIVE_INTERVAL_MILLIS = 30_000L;
    static final long KEEPALIVE_TIMEOUT_MILLIS = 15_000L;
    static final long MAX_RECONNECT_DELAY_MILLIS = 60_000L;

    enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        STOPPED
    }

    @FunctionalInterface
    public interface Connector {
        CompletableFuture<WebSocket> connect(WebSocket.Listener listener);
    }

    @FunctionalInterface
    public interface DelayScheduler {
        ScheduledAction schedule(Runnable task, long delay, TimeUnit unit);
    }

    @FunctionalInterface
    public interface ScheduledAction {
        void cancel();
    }

    private final Object lock = new Object();
    private final Connector connector;
    private final DelayScheduler delayScheduler;
    private final Consumer<String> messageConsumer;
    private final Logger logger;
    private final long initialReconnectDelayMillis;

    private long generation;
    private ConnectionState state = ConnectionState.STOPPED;
    private long nextReconnectDelayMillis;
    private long bootstrapGeneration = -1L;
    private long livenessSequence;
    private WebSocket activeSocket;
    private CompletableFuture<WebSocket> connecting;
    private ScheduledAction scheduledConnectTimeout;
    private ScheduledAction scheduledReconnect;
    private ScheduledAction scheduledBootstrap;
    private ScheduledAction scheduledLiveness;
    private boolean awaitingLivenessResponse;

    public WebSocketConnectionManager(
            Connector connector,
            DelayScheduler delayScheduler,
            Consumer<String> messageConsumer,
            Logger logger,
            long reconnectDelay,
            TimeUnit reconnectDelayUnit
    ) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.delayScheduler = Objects.requireNonNull(delayScheduler, "delayScheduler");
        this.messageConsumer = Objects.requireNonNull(messageConsumer, "messageConsumer");
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(reconnectDelayUnit, "reconnectDelayUnit");
        long configuredDelayMillis = reconnectDelayUnit.toMillis(reconnectDelay);
        if (configuredDelayMillis <= 0L) {
            throw new IllegalArgumentException("reconnect delay must be positive");
        }
        initialReconnectDelayMillis = Math.min(
                configuredDelayMillis, MAX_RECONNECT_DELAY_MILLIS);
        nextReconnectDelayMillis = initialReconnectDelayMillis;
    }

    public void start() {
        long token;
        synchronized (lock) {
            if (state != ConnectionState.STOPPED) {
                return;
            }
            state = ConnectionState.DISCONNECTED;
            nextReconnectDelayMillis = initialReconnectDelayMillis;
            token = ++generation;
        }
        connect(token, false);
    }

    public void restart() {
        WebSocket oldSocket;
        CompletableFuture<WebSocket> oldConnection;
        ScheduledAction oldConnectTimeout;
        ScheduledAction oldReconnect;
        ScheduledAction oldBootstrap;
        ScheduledAction oldLiveness;
        long token;
        synchronized (lock) {
            state = ConnectionState.DISCONNECTED;
            nextReconnectDelayMillis = initialReconnectDelayMillis;
            token = ++generation;
            oldSocket = activeSocket;
            activeSocket = null;
            oldConnection = connecting;
            connecting = null;
            oldConnectTimeout = scheduledConnectTimeout;
            scheduledConnectTimeout = null;
            oldReconnect = scheduledReconnect;
            scheduledReconnect = null;
            oldBootstrap = scheduledBootstrap;
            scheduledBootstrap = null;
            oldLiveness = scheduledLiveness;
            scheduledLiveness = null;
            awaitingLivenessResponse = false;
            livenessSequence++;
        }
        cancel(oldConnectTimeout);
        cancel(oldReconnect);
        cancel(oldBootstrap);
        cancel(oldLiveness);
        cancel(oldConnection);
        closeBeforeRestart(oldSocket, token);
    }

    public void stop() {
        WebSocket oldSocket;
        CompletableFuture<WebSocket> oldConnection;
        ScheduledAction oldConnectTimeout;
        ScheduledAction oldReconnect;
        ScheduledAction oldBootstrap;
        ScheduledAction oldLiveness;
        synchronized (lock) {
            if (state == ConnectionState.STOPPED && activeSocket == null && connecting == null
                    && scheduledConnectTimeout == null && scheduledReconnect == null
                    && scheduledBootstrap == null && scheduledLiveness == null) {
                return;
            }
            state = ConnectionState.STOPPED;
            generation++;
            oldSocket = activeSocket;
            activeSocket = null;
            oldConnection = connecting;
            connecting = null;
            oldConnectTimeout = scheduledConnectTimeout;
            scheduledConnectTimeout = null;
            oldReconnect = scheduledReconnect;
            scheduledReconnect = null;
            oldBootstrap = scheduledBootstrap;
            scheduledBootstrap = null;
            oldLiveness = scheduledLiveness;
            scheduledLiveness = null;
            awaitingLivenessResponse = false;
            livenessSequence++;
        }
        cancel(oldConnectTimeout);
        cancel(oldReconnect);
        cancel(oldBootstrap);
        cancel(oldLiveness);
        cancel(oldConnection);
        closeWithoutReconnect(oldSocket, "Plugin disabled");
    }

    private void connect(long token, boolean reconnectAttempt) {
        synchronized (lock) {
            if (!isCurrentGeneration(token)) {
                return;
            }
            state = ConnectionState.CONNECTING;
        }
        if (reconnectAttempt) {
            logger.info("Reconnecting to WebSocket API...");
        }

        ConnectionListener listener = new ConnectionListener(token, reconnectAttempt);
        CompletableFuture<WebSocket> future;
        try {
            future = Objects.requireNonNull(connector.connect(listener), "connector future");
        } catch (Throwable error) {
            handleConnectFailure(token, null, error, reconnectAttempt);
            return;
        }

        boolean stale;
        synchronized (lock) {
            stale = !isCurrentGeneration(token);
            if (!stale && activeSocket == null) {
                connecting = future;
            }
        }
        if (stale) {
            future.cancel(true);
            return;
        }

        future.whenComplete((socket, error) -> {
            if (error != null) {
                handleConnectFailure(token, future, error, reconnectAttempt);
                return;
            }
            boolean completedStale;
            synchronized (lock) {
                completedStale = !isCurrentGeneration(token);
            }
            if (completedStale && socket != null) {
                socket.abort();
            }
        });
        scheduleConnectTimeout(token, future, reconnectAttempt);
    }

    private void closeBeforeRestart(WebSocket socket, long token) {
        if (socket == null) {
            connectIfCurrent(token);
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin reload")
                    .orTimeout(2, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> {
                        socket.abort();
                        connectIfCurrent(token);
                    });
        } catch (Throwable error) {
            socket.abort();
            connectIfCurrent(token);
        }
    }

    private void closeWithoutReconnect(WebSocket socket, String reason) {
        if (socket == null) {
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> socket.abort());
        } catch (Throwable error) {
            socket.abort();
        }
    }

    private void connectIfCurrent(long token) {
        synchronized (lock) {
            if (!isCurrentGeneration(token)) {
                return;
            }
        }
        connect(token, false);
    }

    private void scheduleConnectTimeout(
            long token,
            CompletableFuture<WebSocket> future,
            boolean reconnectAttempt
    ) {
        Throwable scheduleFailure = null;
        synchronized (lock) {
            if (!isCurrentGeneration(token) || connecting != future || activeSocket != null) {
                return;
            }
            try {
                scheduledConnectTimeout = delayScheduler.schedule(
                        () -> handleConnectTimeout(token, future, reconnectAttempt),
                        CONNECT_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (Throwable error) {
                scheduleFailure = error;
            }
        }
        if (scheduleFailure != null) {
            if (handleConnectFailure(token, future, scheduleFailure, reconnectAttempt)) {
                future.cancel(true);
            }
        }
    }

    private void handleConnectTimeout(
            long token,
            CompletableFuture<WebSocket> future,
            boolean reconnectAttempt
    ) {
        if (handleConnectFailure(token, future,
                new TimeoutException("WebSocket connection attempt timed out"),
                reconnectAttempt)) {
            future.cancel(true);
        }
    }

    private boolean handleConnectFailure(
            long token,
            CompletableFuture<WebSocket> future,
            Throwable error,
            boolean reconnectAttempt
    ) {
        ScheduledAction connectTimeout;
        long reconnectToken;
        synchronized (lock) {
            if (!isCurrentGeneration(token) || activeSocket != null
                    || (future != null && connecting != future)) {
                return false;
            }
            connecting = null;
            connectTimeout = scheduledConnectTimeout;
            scheduledConnectTimeout = null;
            state = ConnectionState.DISCONNECTED;
            reconnectToken = ++generation;
        }
        cancel(connectTimeout);
        logFailure(reconnectAttempt
                ? "WebSocket reconnect failed."
                : "Failed to connect to WebSocket API.", error);
        scheduleReconnect(reconnectToken);
        return true;
    }

    private void handleConnectionFailure(
            long token,
            WebSocket socket,
            Throwable error,
            String message
    ) {
        ScheduledAction bootstrap;
        ScheduledAction liveness;
        long reconnectToken;
        synchronized (lock) {
            if (!isCurrentSocket(token, socket)) {
                return;
            }
            activeSocket = null;
            bootstrap = scheduledBootstrap;
            scheduledBootstrap = null;
            liveness = scheduledLiveness;
            scheduledLiveness = null;
            awaitingLivenessResponse = false;
            livenessSequence++;
            state = ConnectionState.DISCONNECTED;
            reconnectToken = ++generation;
        }
        cancel(bootstrap);
        cancel(liveness);
        logFailure(message, error);
        socket.abort();
        scheduleReconnect(reconnectToken);
    }

    private void scheduleReconnect(long token) {
        Throwable scheduleFailure = null;
        long delayMillis;
        synchronized (lock) {
            if (!isCurrentGeneration(token) || scheduledReconnect != null) {
                return;
            }
            delayMillis = nextReconnectDelayMillis;
            nextReconnectDelayMillis = Math.min(
                    MAX_RECONNECT_DELAY_MILLIS,
                    delayMillis > MAX_RECONNECT_DELAY_MILLIS / 2L
                            ? MAX_RECONNECT_DELAY_MILLIS
                            : delayMillis * 2L);
            state = ConnectionState.RECONNECTING;
            try {
                scheduledReconnect = delayScheduler.schedule(
                        () -> runReconnect(token), delayMillis, TimeUnit.MILLISECONDS);
            } catch (Throwable error) {
                state = ConnectionState.DISCONNECTED;
                scheduleFailure = error;
            }
        }
        if (scheduleFailure != null) {
            logger.log(Level.WARNING, "Unable to schedule WebSocket reconnect.", scheduleFailure);
            return;
        }
        logger.warning("Scheduling WebSocket reconnect in " + formatDelay(delayMillis) + ".");
    }

    private void runReconnect(long token) {
        synchronized (lock) {
            if (!isCurrentGeneration(token) || scheduledReconnect == null) {
                return;
            }
            scheduledReconnect = null;
        }
        connect(token, true);
    }

    private void scheduleKeepAlivePing(long token, WebSocket socket) {
        Throwable scheduleFailure = null;
        synchronized (lock) {
            if (!isCurrentSocket(token, socket) || scheduledLiveness != null
                    || awaitingLivenessResponse) {
                return;
            }
            long sequence = ++livenessSequence;
            try {
                scheduledLiveness = delayScheduler.schedule(
                        () -> sendKeepAlivePing(token, socket, sequence),
                        KEEPALIVE_INTERVAL_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (Throwable error) {
                scheduleFailure = error;
            }
        }
        if (scheduleFailure != null) {
            handleConnectionFailure(token, socket, scheduleFailure,
                    "WebSocket connection lost: unable to schedule its liveness check.");
        }
    }

    private void sendKeepAlivePing(long token, WebSocket socket, long sequence) {
        synchronized (lock) {
            if (!isCurrentSocket(token, socket) || sequence != livenessSequence
                    || scheduledLiveness == null) {
                return;
            }
            scheduledLiveness = null;
            awaitingLivenessResponse = true;
        }

        CompletableFuture<WebSocket> send;
        try {
            ByteBuffer payload = ByteBuffer.allocate(Long.BYTES);
            payload.putLong(sequence);
            payload.flip();
            send = Objects.requireNonNull(socket.sendPing(payload), "ping future");
        } catch (Throwable error) {
            handleConnectionFailure(token, socket, error,
                    "WebSocket connection lost: keepalive ping could not be sent.");
            return;
        }

        scheduleKeepAliveTimeout(token, socket, sequence);
        send.whenComplete((ignored, error) -> {
            if (error != null) {
                handleConnectionFailure(token, socket, error,
                        "WebSocket connection lost: keepalive ping failed.");
            }
        });
    }

    private void scheduleKeepAliveTimeout(long token, WebSocket socket, long sequence) {
        Throwable scheduleFailure = null;
        synchronized (lock) {
            if (!isCurrentSocket(token, socket) || sequence != livenessSequence
                    || !awaitingLivenessResponse || scheduledLiveness != null) {
                return;
            }
            try {
                scheduledLiveness = delayScheduler.schedule(
                        () -> handleKeepAliveTimeout(token, socket, sequence),
                        KEEPALIVE_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (Throwable error) {
                scheduleFailure = error;
            }
        }
        if (scheduleFailure != null) {
            handleConnectionFailure(token, socket, scheduleFailure,
                    "WebSocket connection lost: unable to schedule its keepalive timeout.");
        }
    }

    private void handleKeepAliveTimeout(long token, WebSocket socket, long sequence) {
        synchronized (lock) {
            if (!isCurrentSocket(token, socket) || sequence != livenessSequence
                    || !awaitingLivenessResponse || scheduledLiveness == null) {
                return;
            }
            scheduledLiveness = null;
            awaitingLivenessResponse = false;
        }
        handleConnectionFailure(token, socket,
                new TimeoutException("No WebSocket activity followed the keepalive ping"),
                "WebSocket connection lost: keepalive response timed out.");
    }

    private void recordInboundActivity(long token, WebSocket socket) {
        ScheduledAction timeout;
        synchronized (lock) {
            if (!isCurrentSocket(token, socket) || !awaitingLivenessResponse) {
                return;
            }
            awaitingLivenessResponse = false;
            livenessSequence++;
            timeout = scheduledLiveness;
            scheduledLiveness = null;
        }
        cancel(timeout);
        scheduleKeepAlivePing(token, socket);
    }

    private void sendBootstrapQuery(long token, WebSocket socket, int queryIndex) {
        String query = BOOTSTRAP_QUERIES.get(queryIndex);
        CompletableFuture<WebSocket> send = null;
        Throwable sendFailure = null;
        synchronized (lock) {
            if (!isCurrentBootstrap(token, socket)) {
                return;
            }
            try {
                send = socket.sendText(query, true);
            } catch (Throwable error) {
                sendFailure = error;
            }
        }
        if (sendFailure != null) {
            handleBootstrapFailure(token, socket, query, sendFailure);
            return;
        }
        CompletableFuture<WebSocket> sendResult = send;
        sendResult.whenComplete((ignored, error) -> {
            if (error != null) {
                handleBootstrapFailure(token, socket, query, error);
            } else if (queryIndex + 1 < BOOTSTRAP_QUERIES.size()) {
                scheduleBootstrapQuery(token, socket, queryIndex + 1);
            }
        });
    }

    private void scheduleBootstrapQuery(long token, WebSocket socket, int queryIndex) {
        Throwable scheduleFailure = null;
        synchronized (lock) {
            if (!isCurrentBootstrap(token, socket) || scheduledBootstrap != null) {
                return;
            }
            try {
                scheduledBootstrap = delayScheduler.schedule(
                        () -> runBootstrapQuery(token, socket, queryIndex),
                        BOOTSTRAP_QUERY_INTERVAL_MILLIS,
                        TimeUnit.MILLISECONDS
                );
            } catch (Throwable error) {
                scheduleFailure = error;
            }
        }
        if (scheduleFailure != null) {
            handleBootstrapFailure(
                    token, socket, BOOTSTRAP_QUERIES.get(queryIndex), scheduleFailure);
        }
    }

    private void runBootstrapQuery(long token, WebSocket socket, int queryIndex) {
        synchronized (lock) {
            if (!isCurrentBootstrap(token, socket) || scheduledBootstrap == null) {
                return;
            }
            scheduledBootstrap = null;
        }
        sendBootstrapQuery(token, socket, queryIndex);
    }

    private void handleBootstrapFailure(
            long token, WebSocket socket, String query, Throwable error) {
        handleConnectionFailure(token, socket, error,
                "Failed to send WebSocket bootstrap query: " + query);
    }

    private boolean isCurrentBootstrap(long token, WebSocket socket) {
        return bootstrapGeneration == token && isCurrentSocket(token, socket);
    }

    private boolean isCurrentGeneration(long token) {
        return state != ConnectionState.STOPPED && token == generation;
    }

    private boolean isCurrentSocket(long token, WebSocket socket) {
        return isCurrentGeneration(token) && activeSocket == socket;
    }

    private void logFailure(String message, Throwable error) {
        Throwable handshake = findCause(error, WebSocketHandshakeException.class);
        if (handshake != null) {
            HttpResponse<?> response = ((WebSocketHandshakeException) handshake).getResponse();
            logger.warning(message + " WebSocket handshake HTTP status: "
                    + response.statusCode() + ", HTTP version: " + response.version() + ".");
        }
        logger.log(Level.WARNING, message, unwrap(error));
    }

    private String formatDelay(long delayMillis) {
        if (delayMillis % 1000L == 0L) {
            return (delayMillis / 1000L) + "s";
        }
        return delayMillis + "ms";
    }

    ConnectionState connectionState() {
        synchronized (lock) {
            return state;
        }
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private Throwable findCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    private void cancel(ScheduledAction action) {
        if (action != null) {
            action.cancel();
        }
    }

    private void cancel(CompletableFuture<WebSocket> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private final class ConnectionListener implements WebSocket.Listener {
        private final long token;
        private final boolean reconnectAttempt;
        private final StringBuilder messageBuffer = new StringBuilder();

        private ConnectionListener(long token, boolean reconnectAttempt) {
            this.token = token;
            this.reconnectAttempt = reconnectAttempt;
        }

        @Override
        public void onOpen(WebSocket socket) {
            ScheduledAction connectTimeout;
            ScheduledAction reconnect;
            boolean startBootstrap;
            synchronized (lock) {
                if (!isCurrentGeneration(token) || (activeSocket != null && activeSocket != socket)) {
                    socket.abort();
                    return;
                }
                if (activeSocket == socket) {
                    return;
                }
                activeSocket = socket;
                connecting = null;
                connectTimeout = scheduledConnectTimeout;
                scheduledConnectTimeout = null;
                reconnect = scheduledReconnect;
                scheduledReconnect = null;
                nextReconnectDelayMillis = initialReconnectDelayMillis;
                state = ConnectionState.CONNECTED;
                startBootstrap = bootstrapGeneration != token;
                if (startBootstrap) {
                    bootstrapGeneration = token;
                }
            }
            cancel(connectTimeout);
            cancel(reconnect);
            logger.info(reconnectAttempt
                    ? "Reconnected to WebSocket API."
                    : "Connected to WebSocket API.");
            requestNext(token, socket);
            if (startBootstrap) {
                sendBootstrapQuery(token, socket, 0);
            }
            scheduleKeepAlivePing(token, socket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            synchronized (lock) {
                if (!isCurrentSocket(token, socket)) {
                    return CompletableFuture.completedFuture(null);
                }
            }
            recordInboundActivity(token, socket);
            try {
                messageBuffer.append(data);
                if (last) {
                    String completeMessage = messageBuffer.toString();
                    messageBuffer.setLength(0);
                    synchronized (lock) {
                        if (!isCurrentSocket(token, socket)) {
                            return CompletableFuture.completedFuture(null);
                        }
                        messageConsumer.accept(completeMessage);
                    }
                }
            } catch (Throwable error) {
                logger.log(Level.WARNING,
                        "Failed to process a WebSocket API message; the message was ignored.",
                        error);
            }
            requestNext(token, socket);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            recordInboundActivity(token, socket);
            requestNext(token, socket);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            recordInboundActivity(token, socket);
            requestNext(token, socket);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            recordInboundActivity(token, socket);
            requestNext(token, socket);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            ScheduledAction bootstrap;
            ScheduledAction liveness;
            long reconnectToken;
            synchronized (lock) {
                if (!isCurrentSocket(token, socket)) {
                    return CompletableFuture.completedFuture(null);
                }
                activeSocket = null;
                bootstrap = scheduledBootstrap;
                scheduledBootstrap = null;
                liveness = scheduledLiveness;
                scheduledLiveness = null;
                awaitingLivenessResponse = false;
                livenessSequence++;
                state = ConnectionState.DISCONNECTED;
                reconnectToken = ++generation;
            }
            cancel(bootstrap);
            cancel(liveness);
            logger.warning("WebSocket connection lost: closed with status "
                    + statusCode + (reason.isEmpty() ? "." : " (" + reason + ")."));
            scheduleReconnect(reconnectToken);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            handleConnectionFailure(token, socket, error,
                    "WebSocket connection lost: receive loop failed.");
        }

        private void requestNext(long token, WebSocket socket) {
            Throwable requestFailure = null;
            synchronized (lock) {
                if (!isCurrentSocket(token, socket)) {
                    return;
                }
                try {
                    socket.request(1);
                } catch (Throwable error) {
                    requestFailure = error;
                }
            }
            if (requestFailure != null) {
                handleConnectionFailure(token, socket, requestFailure,
                        "Failed to request the next WebSocket API message.");
            }
        }
    }
}
