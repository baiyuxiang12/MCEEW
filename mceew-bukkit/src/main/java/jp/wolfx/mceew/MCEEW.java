package jp.wolfx.mceew;

import com.google.gson.JsonObject;
import jp.wolfx.mceew.format.EarthquakeTimeFormatter;
import jp.wolfx.mceew.format.LegacyTextFormatter;
import jp.wolfx.mceew.message.FujianEewEvent;
import jp.wolfx.mceew.message.JmaEewEvent;
import jp.wolfx.mceew.message.RegionalEewEvent;
import jp.wolfx.mceew.message.WolfxMessageRouter;
import jp.wolfx.mceew.notification.NotificationIntentFactory;
import jp.wolfx.mceew.notification.NotificationProfile;
import jp.wolfx.mceew.notification.NotificationSource;
import jp.wolfx.mceew.scheduler.PlatformScheduler;
import jp.wolfx.mceew.websocket.WebSocketConnectionManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Hashtable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpClient;

public final class MCEEW extends JavaPlugin implements Listener {
    private static final WolfxMessageRouter MESSAGE_ROUTER = new WolfxMessageRouter();
    private boolean jpEewBoolean;
    private boolean scEewBoolean;
    private boolean fjEewBoolean;
    private boolean cwaEewBoolean;
    private boolean cencEewBoolean;
    private boolean cqEewBoolean;
    private boolean broadcastBool;
    private boolean titleBool;
    private boolean alertBool;
    private boolean jmaEqlistBoolean;
    private boolean cencEqlistBoolean;
    private String timeFormat;
    private String alertBroadcastMessage;
    private String alertTitleMessage;
    private String alertSubtitleMessage;
    private String forecastBroadcastMessage;
    private String forecastTitleMessage;
    private String forecastSubtitleMessage;
    private String jmaEqlistBroadcastMessage;
    private String cencEqlistBroadcastMessage;
    private String sichuanBroadcastMessage;
    private String sichuanTitleMessage;
    private String sichuanSubtitleMessage;
    private String fjBroadcastMessage;
    private String fjTitleMessage;
    private String fjSubtitleMessage;
    private String cwaBroadcastMessage;
    private String cwaTitleMessage;
    private String cwaSubtitleMessage;
    private String cencBroadcastMessage;
    private String cencTitleMessage;
    private String cencSubtitleMessage;
    private String cqBroadcastMessage;
    private String cqTitleMessage;
    private String cqSubtitleMessage;
    private String alertAlertSoundType;
    private double alertAlertSoundVolume;
    private double alertAlertSoundPitch;
    private String forecastAlertSoundType;
    private double forecastAlertSoundVolume;
    private double forecastAlertSoundPitch;
    private String scAlertSoundType;
    private double scAlertSoundVolume;
    private double scAlertSoundPitch;
    private String fjAlertSoundType;
    private double fjAlertSoundVolume;
    private double fjAlertSoundPitch;
    private String cwaAlertSoundType;
    private double cwaAlertSoundVolume;
    private double cwaAlertSoundPitch;
    private String cencAlertSoundType;
    private double cencAlertSoundVolume;
    private double cencAlertSoundPitch;
    private String cqAlertSoundType;
    private double cqAlertSoundVolume;
    private double cqAlertSoundPitch;
    private final EarthquakeInfoCache earthquakeInfoCache = new EarthquakeInfoCache();
    private String version;
    private static final HttpClient client = HttpClient.newHttpClient();
    private PlatformScheduler platformScheduler;
    private BukkitNotificationDispatcher notificationDispatcher;
    private WebSocketConnectionManager webSocketManager;
    private ConfigManager configManager;
    private jp.wolfx.mceew.countdown.CountdownManager countdownManager;

    @Override
    public void onEnable() {
        version = getDescription().getVersion();
        platformScheduler = PlatformScheduler.create(this);
        notificationDispatcher = new BukkitNotificationDispatcher(
                platformScheduler, getLogger());
        configManager = ConfigManager.forPlugin(this);
        webSocketManager = new WebSocketConnectionManager(
                listener -> client.newWebSocketBuilder()
                        .buildAsync(URI.create("wss://ws-api.wolfx.jp/all_eew"), listener),
                (task, delay, unit) -> {
                    PlatformScheduler.TaskHandle handle =
                            platformScheduler.runAsyncDelayed(task, delay, unit);
                    return handle::cancel;
                },
                this::handleWebSocketMessage,
                getLogger(),
                5,
                TimeUnit.SECONDS
        );
        if (!prepareAndLoadConfiguration()) {
            throw new IllegalStateException("Unable to prepare MCEEW configuration");
        }
        countdownManager = new jp.wolfx.mceew.countdown.CountdownManager(this, platformScheduler);
        countdownManager.load();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(platformScheduler.isFolia()
                ? "Using Folia API for scheduler."
                : "Using Bukkit API for scheduler.");
        webSocketManager.start();
        platformScheduler.runAsync(this::updater);
        new Metrics(this, 17261);
    }

    /** 玩家加入时异步预取在线 IP 定位（缓存命中后 EEW 即可用区县级坐标）。 */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (countdownManager != null) {
            countdownManager.prefetchPlayerLocation(event.getPlayer());
        }
    }

    private void eewTest(int flag) {
        if (flag == 1) {
            String flags = "警報";
            String originTimeStr = "2024/01/01 16:10:08";
            String reportTime = "2024/01/01 16:14:18";
            String num = "46";
            String lat = "37.6";
            String lon = "137.2";
            String region = "能登半島沖";
            String mag = "7.4";
            String depth = "10km";
            String shindo = "7";
            String type = "最終報";
            String originTime = getDate("yyyy/MM/dd HH:mm:ss", timeFormat, "Asia/Tokyo", originTimeStr);
            jmaEewAction(flags, reportTime, originTime, num, lat, lon, region, mag, depth, getShindoColor(shindo), type);
            triggerCountdown("JMA", lat, lon, depth, mag, shindo, originTimeStr, "yyyy/MM/dd HH:mm:ss", "Asia/Tokyo", region);
        } else if (flag == 2) {
            String originTimeStr = "2024-02-28 21:23:30";
            String reportTime = "2024-02-28 21:23:37";
            String num = "1";
            String lat = "29.3";
            String lon = "102.82";
            String region = "四川雅安市汉源县";
            String mag = "3.3";
            String depth = "10km";
            String intensity = "5";
            String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", originTimeStr);
            scEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getIntensityColor(intensity));
            triggerCountdown("SICHUAN", lat, lon, depth, mag, intensity, originTimeStr, "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
        } else if (flag == 3) {
            String originTimeStr = "2024-02-29 13:26:28";
            String reportTime = "2024-02-29 13:27:40";
            String num = "4";
            String lat = "23.47";
            String lon = "120.26";
            String region = "台湾嘉义县";
            String mag = "4.4";
            String type = "最終報";
            String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", originTimeStr);
            fjEewAction(reportTime, originTime, num, lat, lon, region, mag, type);
            triggerCountdown("FUJIAN", lat, lon, null, mag, null, originTimeStr, "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
        } else if (flag == 4) {
            String originTimeStr = "2024-04-03 07:58:10";
            String reportTime = "2024-04-03 07:58:27";
            String num = "2";
            String lat = "23.89";
            String lon = "121.56";
            String region = "花蓮縣壽豐鄉";
            String mag = "6.8";
            String depth = "20km";
            String shindo = "6弱";
            String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", originTimeStr);
            cwaEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getShindoColor(shindo));
            triggerCountdown("CWA", lat, lon, depth, mag, shindo, originTimeStr, "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
        } else if (flag == 5) {
            String originTimeStr = "2025-09-12 05:50:58";
            String reportTime = "2025-09-12 05:50:58";
            String num = "1";
            String lat = "33.002";
            String lon = "102.89";
            String region = "四川阿坝州红原县";
            String mag = "4.4";
            String depth = "5km";
            String intensity = "6.1";
            String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", originTimeStr);
            cencEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getIntensityColor(intensity));
            triggerCountdown("CENC", lat, lon, depth, mag, intensity, originTimeStr, "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
        } else if (flag == 6) {
            String originTimeStr = "2026-08-07 13:08:30";
            String reportTime = "2026-08-07 13:08:30";
            String num = "1";
            String lat = "28.517";
            String lon = "104.673";
            String region = "四川宜宾市高县";
            String mag = "4.8";
            String depth = "4km";
            String intensity = "6.6";
            String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", originTimeStr);
            cqEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getIntensityColor(intensity));
            triggerCountdown("CHONGQING", lat, lon, depth, mag, intensity, originTimeStr, "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
        } else if (flag == 7) {
            // 测试专用：宜宾 M7.7，发震时间动态取 now-5s，保证倒计时 ETA 为正
            String originTimeStr = java.time.LocalDateTime.now().minusSeconds(5)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String reportTime = originTimeStr;
            String num = "1";
            String lat = "28.77";
            String lon = "104.62";
            String region = "四川宜宾市";
            String mag = "7.7";
            String depth = "10km";
            String intensity = "8";
            String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", originTimeStr);
            cencEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getIntensityColor(intensity));
            triggerCountdown("CENC", lat, lon, depth, mag, intensity, originTimeStr, "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
        } else {
            String flags = "予報";
            String originTimeStr = "2024/02/29 18:35:38";
            String reportTime = "2024/02/29 18:36:36";
            String num = "6";
            String lat = "35.4";
            String lon = "140.6";
            String region = "千葉県東方沖";
            String mag = "4.7";
            String depth = "10km";
            String shindo = "3";
            String type = "";
            String originTime = getDate("yyyy/MM/dd HH:mm:ss", timeFormat, "Asia/Tokyo", originTimeStr);
            jmaEewAction(flags, reportTime, originTime, num, lat, lon, region, mag, depth, getShindoColor(shindo), type);
            triggerCountdown("JMA", lat, lon, depth, mag, shindo, originTimeStr, "yyyy/MM/dd HH:mm:ss", "Asia/Tokyo", region);
        }
        broadcastMessage();
    }

    private String fetchVersionFromDnsTxt() throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        // 也可以指定 resolver，例如 Cloudflare：env.put("java.naming.provider.url", "dns://1.1.1.1");
        DirContext ctx = new InitialDirContext(env);

        Attributes attrs = ctx.getAttributes("mceew.mtf.edu.kg", new String[]{"TXT"});
        Attribute txt = attrs.get("TXT");
        if (txt == null || txt.size() == 0) return null;

        // 一个域名可能有多条 TXT，这里遍历找包含 version= 的那条
        for (int i = 0; i < txt.size(); i++) {
            String record = String.valueOf(txt.get(i));

            // JNDI 返回的 TXT 可能自带引号，先去掉
            record = record.replace("\"", "").trim();

            // 允许记录里包含多个键值，例如: foo=bar version=1.2.3
            // 但你目前是单值：version=1.2.3
            int idx = record.indexOf("version=");
            if (idx >= 0) {
                String v = record.substring(idx + "version=".length()).trim();

                // 如果后面还有空格/分号之类，切掉
                int cut = v.indexOf(' ');
                if (cut > 0) v = v.substring(0, cut);
                cut = v.indexOf(';');
                if (cut > 0) v = v.substring(0, cut);

                // 只保留数字和点（防御性）
                v = v.replaceAll("[^0-9.]", "");
                return v;
            }
        }
        return null;
    }

    private int compareSemver(String a, String b) {
        int[] av = parseSemver(a);
        int[] bv = parseSemver(b);

        int n = Math.max(av.length, bv.length);
        for (int i = 0; i < n; i++) {
            int ai = i < av.length ? av[i] : 0;
            int bi = i < bv.length ? bv[i] : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private int[] parseSemver(String v) {
        if (v == null) return new int[]{0, 0, 0};
        v = v.trim();

        // 防御：只留 x.y.z 数字点
        v = v.replaceAll("[^0-9.]", "");
        if (v.isEmpty()) return new int[]{0, 0, 0};

        String[] parts = v.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].isEmpty() ? "0" : parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private void broadcastMessage() {
        notificationDispatcher.deliverTestWarning(
                "§eWarning: This is an Earthquake Early Warning test.");
    }

    private String getDate(String pattern, String timeFormat, String timezone, String originTime) {
        return EarthquakeTimeFormatter.format(pattern, timeFormat, timezone, originTime);
    }

    private boolean isFresh(String reportTimeStr, String pattern, ZoneId zone) {
        return EarthquakeTimeFormatter.isFresh(reportTimeStr, pattern, zone);
    }

    // TEST SEAM: explicit time input makes the existing freshness calculation deterministic.
    boolean isFresh(
            String reportTimeStr, String pattern, ZoneId zone, ZonedDateTime now) {
        return EarthquakeTimeFormatter.isFresh(reportTimeStr, pattern, zone, now);
    }

    private void updater() {
        try {
            // 1) 从 DNS TXT 读取版本号（格式：version=x.x.x）
            String apiVersion = fetchVersionFromDnsTxt(); // 例如 "2.6.2"
            if (apiVersion == null || apiVersion.isBlank()) {
                throw new IOException("Empty version from DNS TXT");
            }

            // 2) 本地版本号清洗（去掉 -bxxx 之类后缀）
            String localVersion = version.replaceAll("-b.*", "");

            // 3) 版本比较（语义化比较，避免 2.10.0 vs 2.6.9 这种出错）
            int cmp = compareSemver(apiVersion, localVersion);

            if (cmp > 0) {
                getLogger().warning("New plugin version v" + apiVersion
                        + " detected, Please download a new version from https://www.spigotmc.org/resources/mceew-earthquake-early-warning.104549/");
            } else {
                getLogger().info(String.format("Plugin is up to date. Current version: v%s", apiVersion));
            }

        } catch (Exception e) {
            getLogger().warning("Failed to check for plugin updates via DNS TXT.");
            getLogger().warning(String.valueOf(e));
        }
    }

    private void handleWebSocketMessage(String message) {
        WolfxMessageRouter.RoutedMessage routed = MESSAGE_ROUTER.route(message);
        switch (routed.getType()) {
            case JMA_EEW:
                if (jpEewBoolean) {
                    jmaEewExecute((JmaEewEvent) MESSAGE_ROUTER.parseRealtime(routed));
                }
                break;
            case JMA_EARTHQUAKE_LIST:
                jmaEqlistExecute(routed.getPayload(), jmaEqlistBoolean);
                break;
            case SICHUAN_EEW:
                if (scEewBoolean) {
                    scEewExecute((RegionalEewEvent) MESSAGE_ROUTER.parseRealtime(routed));
                }
                break;
            case FUJIAN_EEW:
                if (fjEewBoolean) {
                    fjEewExecute((FujianEewEvent) MESSAGE_ROUTER.parseRealtime(routed));
                }
                break;
            case CWA_EEW:
                if (cwaEewBoolean) {
                    cwaEewExecute((RegionalEewEvent) MESSAGE_ROUTER.parseRealtime(routed));
                }
                break;
            case CENC_EEW:
                if (cencEewBoolean) {
                    cencEewExecute((RegionalEewEvent) MESSAGE_ROUTER.parseRealtime(routed));
                }
                break;
            case CHONGQING_EEW:
                if (cqEewBoolean) {
                    cqEewExecute((RegionalEewEvent) MESSAGE_ROUTER.parseRealtime(routed));
                }
                break;
            case CENC_EARTHQUAKE_LIST:
                cencEqlistExecute(routed.getPayload(), cencEqlistBoolean);
                break;
            case HEARTBEAT:
            case UNKNOWN:
                break;
        }
    }

    private void jmaEewExecute(JmaEewEvent event) {
        String type = LegacyTextFormatter.jmaReportType(
                event.isTraining(), event.isAssumption(),
                event.isFinalReport(), event.isCancelled());
        String flag = event.getFlag();
        String reportTime = event.getReportTime();
        String num = event.getReportNumber();
        String lat = event.getLatitude();
        String lon = event.getLongitude();
        String region = event.getRegion();
        String mag = event.getMagnitude();
        String depth = LegacyTextFormatter.depthKilometers(event.getDepth());
        String shindo = event.getMaximumIntensity();
        String originTime = getDate("yyyy/MM/dd HH:mm:ss", timeFormat, "Asia/Tokyo", event.getOriginTime());
        if (isFresh(reportTime, "yyyy/MM/dd HH:mm:ss", ZoneId.of("Asia/Tokyo"))) {
            if (event.isCancelled()) {
                if (countdownManager != null) {
                    countdownManager.onCancel();
                }
            } else {
                triggerCountdown("JMA", lat, lon, event.getDepth(), mag, shindo,
                        event.getOriginTime(), "yyyy/MM/dd HH:mm:ss", "Asia/Tokyo", region);
            }
            jmaEewAction(flag, reportTime, originTime, num, lat, lon, region, mag, depth, getShindoColor(shindo), type);
        }
    }

    private void jmaEqlistExecute(JsonObject data, boolean enabled) {
        JsonObject latest = data.get("No1").getAsJsonObject();
        String timeStr = latest.get("time_full").getAsString();
        String region = latest.get("location").getAsString();
        String mag = latest.get("magnitude").getAsString();
        String depth = latest.get("depth").getAsString();
        String latitude = latest.get("latitude").getAsString();
        String longitude = latest.get("longitude").getAsString();
        String shindo = latest.get("shindo").getAsString();
        String info = latest.get("info").getAsString();
        String originTime = getDate("yyyy/MM/dd HH:mm:ss", timeFormat, "Asia/Tokyo", timeStr);
        EarthquakeInfoCache.JmaSnapshot snapshot = new EarthquakeInfoCache.JmaSnapshot(
                data.get("md5").getAsString(), originTime, region, mag, depth,
                latitude, longitude, getShindoColor(shindo), info);
        EarthquakeInfoCache.UpdateResult update = earthquakeInfoCache.updateJma(snapshot);
        NotificationIntentFactory.earthquakeList(
                NotificationSource.JMA_EARTHQUAKE_LIST,
                update == EarthquakeInfoCache.UpdateResult.CHANGED,
                enabled,
                () -> snapshot.format(jmaEqlistBroadcastMessage)
        ).ifPresent(notificationDispatcher::deliverEarthquakeList);
    }

    private void cencEqlistExecute(JsonObject data, boolean enabled) {
        JsonObject latest = data.get("No1").getAsJsonObject();
        String timeStr = latest.get("time").getAsString();
        String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", timeStr);
        String intensity = latest.get("intensity").getAsString();
        EarthquakeInfoCache.CencSnapshot snapshot = EarthquakeInfoCache.CencSnapshot.fromEqlist(
                data, originTime, getIntensityColor(intensity));
        EarthquakeInfoCache.UpdateResult update = earthquakeInfoCache.updateCenc(snapshot);
        NotificationIntentFactory.earthquakeList(
                NotificationSource.CENC_EARTHQUAKE_LIST,
                update == EarthquakeInfoCache.UpdateResult.CHANGED,
                enabled,
                () -> snapshot.format(cencEqlistBroadcastMessage)
        ).ifPresent(notificationDispatcher::deliverEarthquakeList);
    }

    private void scEewExecute(RegionalEewEvent event) {
        String reportTime = event.getReportTime();
        String num = event.getReportNumber();
        String lat = event.getLatitude();
        String lon = event.getLongitude();
        String region = event.getRegion();
        String mag = event.getMagnitude();
        String intensity = event.getMaximumIntensity();
        String depth = LegacyTextFormatter.depthKilometers(event.getDepth());
        String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", event.getOriginTime());
        if (isFresh(reportTime, "yyyy-MM-dd HH:mm:ss", ZoneId.of("Asia/Shanghai"))) {
            triggerCountdown("SICHUAN", lat, lon, event.getDepth(), mag, intensity,
                    event.getOriginTime(), "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
            scEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getIntensityColor(intensity));
        }
    }

    private void fjEewExecute(FujianEewEvent event) {
        String type = LegacyTextFormatter.finalReportType(event.isFinalReport());
        String reportTime = event.getReportTime();
        String num = event.getReportNumber();
        String lat = event.getLatitude();
        String lon = event.getLongitude();
        String region = event.getRegion();
        String mag = event.getMagnitude();
        String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", event.getOriginTime());
        if (isFresh(reportTime, "yyyy-MM-dd HH:mm:ss", ZoneId.of("Asia/Shanghai"))) {
            triggerCountdown("FUJIAN", lat, lon, null, mag, null,
                    event.getOriginTime(), "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
            fjEewAction(reportTime, originTime, num, lat, lon, region, mag, type);
        }
    }

    private void cwaEewExecute(RegionalEewEvent event) {
        String reportTime = event.getReportTime();
        String num = event.getReportNumber();
        String lat = event.getLatitude();
        String lon = event.getLongitude();
        String region = event.getRegion();
        String mag = event.getMagnitude();
        String depth = LegacyTextFormatter.depthKilometers(event.getDepth());
        String shindo = event.getMaximumIntensity();
        String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", event.getOriginTime());
        if (isFresh(reportTime, "yyyy-MM-dd HH:mm:ss", ZoneId.of("Asia/Shanghai"))) {
            triggerCountdown("CWA", lat, lon, event.getDepth(), mag, shindo,
                    event.getOriginTime(), "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
            cwaEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getShindoColor(shindo));
        }
    }

    private void cencEewExecute(RegionalEewEvent event) {
        String reportTime = event.getReportTime();
        String num = event.getReportNumber();
        String lat = event.getLatitude();
        String lon = event.getLongitude();
        String region = event.getRegion();
        String mag = event.getMagnitude();
        String intensity = event.getMaximumIntensity();
        String depth = LegacyTextFormatter.depthKilometers(event.getDepth());
        String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", event.getOriginTime());
        if (isFresh(reportTime, "yyyy-MM-dd HH:mm:ss", ZoneId.of("Asia/Shanghai"))) {
            triggerCountdown("CENC", lat, lon, event.getDepth(), mag, intensity,
                    event.getOriginTime(), "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
            cencEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getIntensityColor(intensity));
        }
    }

    private void cqEewExecute(RegionalEewEvent event) {
        String reportTime = event.getReportTime();
        String num = event.getReportNumber();
        String lat = event.getLatitude();
        String lon = event.getLongitude();
        String region = event.getRegion();
        String mag = event.getMagnitude();
        String intensity = event.getMaximumIntensity();
        String depth = LegacyTextFormatter.depthKilometers(event.getDepth());
        String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", event.getOriginTime());
        if (isFresh(reportTime, "yyyy-MM-dd HH:mm:ss", ZoneId.of("Asia/Shanghai"))) {
            triggerCountdown("CHONGQING", lat, lon, event.getDepth(), mag, intensity,
                    event.getOriginTime(), "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region);
            cqEewAction(reportTime, originTime, num, lat, lon, region, mag, depth, getIntensityColor(intensity));
        }
    }

    private void getEewInfo(Boolean flag, CommandSender sender) {
        sender.sendMessage(flag
                ? earthquakeInfoCache.formatCenc(cencEqlistBroadcastMessage)
                : earthquakeInfoCache.formatJma(jmaEqlistBroadcastMessage));
    }

    private void jmaEewAction(String flag, String reportTime, String originTime, String num, String lat, String lon, String region, String mag, String depth, String shindo, String type) {
        notificationDispatcher.deliverJma(() -> NotificationIntentFactory.jma(
                flag, reportTime, originTime, num, lat, lon, region, mag, depth, shindo, type,
                broadcastBool, titleBool, alertBool,
                notificationProfile(
                        alertBroadcastMessage, alertTitleMessage, alertSubtitleMessage,
                        alertAlertSoundType, alertAlertSoundVolume, alertAlertSoundPitch),
                notificationProfile(
                        forecastBroadcastMessage, forecastTitleMessage, forecastSubtitleMessage,
                        forecastAlertSoundType, forecastAlertSoundVolume, forecastAlertSoundPitch)));
    }

    private void scEewAction(String reportTime, String originTime, String num, String lat, String lon, String region, String mag, String depth, String intensity) {
        notificationDispatcher.deliverRegional(NotificationSource.SICHUAN_EEW, () -> NotificationIntentFactory.regional(
                NotificationSource.SICHUAN_EEW,
                reportTime, originTime, num, lat, lon, region, mag, depth, intensity,
                broadcastBool, titleBool, alertBool,
                notificationProfile(
                        sichuanBroadcastMessage, sichuanTitleMessage, sichuanSubtitleMessage,
                        scAlertSoundType, scAlertSoundVolume, scAlertSoundPitch)));
    }

    private void fjEewAction(String reportTime, String originTime, String num, String lat, String lon, String region, String mag, String type) {
        notificationDispatcher.deliverRegional(NotificationSource.FUJIAN_EEW, () -> NotificationIntentFactory.fujian(
                reportTime, originTime, num, lat, lon, region, mag, type,
                broadcastBool, titleBool, alertBool,
                notificationProfile(
                        fjBroadcastMessage, fjTitleMessage, fjSubtitleMessage,
                        fjAlertSoundType, fjAlertSoundVolume, fjAlertSoundPitch)));
    }

    private void cwaEewAction(String reportTime, String originTime, String num, String lat, String lon, String region, String mag, String depth, String shindo) {
        notificationDispatcher.deliverRegional(NotificationSource.CWA_EEW, () -> NotificationIntentFactory.regional(
                NotificationSource.CWA_EEW,
                reportTime, originTime, num, lat, lon, region, mag, depth, shindo,
                broadcastBool, titleBool, alertBool,
                notificationProfile(
                        cwaBroadcastMessage, cwaTitleMessage, cwaSubtitleMessage,
                        cwaAlertSoundType, cwaAlertSoundVolume, cwaAlertSoundPitch)));
    }

    private void cencEewAction(String reportTime, String originTime, String num, String lat, String lon, String region, String mag, String depth, String intensity) {
        notificationDispatcher.deliverRegional(NotificationSource.CENC_EEW, () -> NotificationIntentFactory.regional(
                NotificationSource.CENC_EEW,
                reportTime, originTime, num, lat, lon, region, mag, depth, intensity,
                broadcastBool, titleBool, alertBool,
                notificationProfile(
                        cencBroadcastMessage, cencTitleMessage, cencSubtitleMessage,
                        cencAlertSoundType, cencAlertSoundVolume, cencAlertSoundPitch)));
    }

    private void cqEewAction(String reportTime, String originTime, String num, String lat, String lon, String region, String mag, String depth, String intensity) {
        notificationDispatcher.deliverRegional(NotificationSource.CHONGQING_EEW, () -> NotificationIntentFactory.regional(
                NotificationSource.CHONGQING_EEW,
                reportTime, originTime, num, lat, lon, region, mag, depth, intensity,
                broadcastBool, titleBool, alertBool,
                notificationProfile(
                        cqBroadcastMessage, cqTitleMessage, cqSubtitleMessage,
                        cqAlertSoundType, cqAlertSoundVolume, cqAlertSoundPitch)));
    }

    private NotificationProfile notificationProfile(
            String broadcast,
            String title,
            String subtitle,
            String soundKey,
            double soundVolume,
            double soundPitch
    ) {
        return new NotificationProfile(
                broadcast, title, subtitle, soundKey, soundVolume, soundPitch);
    }

    private String getShindoColor(String shindo) {
        return LegacyTextFormatter.shindo(shindo);
    }

    private String getIntensityColor(String intensity) {
        return LegacyTextFormatter.intensity(intensity);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§a[MCEEW] Plugin version: v" + version);
            sender.sendMessage("§a[MCEEW] §3/eew§a - Show available commands");
            sender.sendMessage("§a[MCEEW] §3/eew test§a - Send a test EEW alert");
            sender.sendMessage("§a[MCEEW] §3/eew info§a - Display latest earthquake information");
            sender.sendMessage("§a[MCEEW] §3/eew reload§a - Reload plugin configuration");
            return true;
        } else if (args[0].equalsIgnoreCase("reload") && sender.isOp()) {
            if (!prepareAndLoadConfiguration()) {
                sender.sendMessage("§c[MCEEW] Configuration reload failed; the existing file was left unchanged.");
                return true;
            }
            webSocketManager.restart();
            sender.sendMessage("§a[MCEEW] Configuration reloaded successfully.");
            return true;
        } else if (args[0].equalsIgnoreCase("info")) {
            if (args.length == 2) {
                if (args[1].equalsIgnoreCase("jma")) {
                    getEewInfo(false, sender);
                    return true;
                } else if (args[1].equalsIgnoreCase("cenc")) {
                    getEewInfo(true, sender);
                    return true;
                }
            } else {
                sender.sendMessage("§a[MCEEW] §3/eew info jma§a - Show Japan JMA earthquake information.");
                sender.sendMessage("§a[MCEEW] §3/eew info cenc§a - Show China CENC earthquake information.");
                return true;
            }
        } else if (args[0].equalsIgnoreCase("test") && sender.isOp()) {
            if (args.length == 2) {
                if (args[1].equalsIgnoreCase("forecast")) {
                    eewTest(0);
                    return true;
                } else if (args[1].equalsIgnoreCase("alert")) {
                    eewTest(1);
                    return true;
                } else if (args[1].equalsIgnoreCase("sc")) {
                    eewTest(2);
                    return true;
                } else if (args[1].equalsIgnoreCase("fj")) {
                    eewTest(3);
                    return true;
                } else if (args[1].equalsIgnoreCase("cwa")) {
                    eewTest(4);
                    return true;
                } else if (args[1].equalsIgnoreCase("cenc")) {
                    eewTest(5);
                    return true;
                } else if (args[1].equalsIgnoreCase("cq")) {
                    eewTest(6);
                    return true;
                } else if (args[1].equalsIgnoreCase("yibin77")) {
                    eewTest(7);
                    return true;
                } else {
                    sendEewTestHelp(sender);
                    return true;
                }
            } else if (args[1].equalsIgnoreCase("ip") && args.length == 3) {
                // /eew test ip <ip> —— 在线 IP 定位调试（验证腾讯 key / 区县精度）
                if (countdownManager == null) {
                    sender.sendMessage("§c[MCEEW] Countdown module is not loaded.");
                    return true;
                }
                final String ip = args[2];
                sender.sendMessage("§a[MCEEW] 查询在线定位: " + ip + " ...");
                countdownManager.lookupIpAsync(ip, r -> {
                    if (r == null) {
                        sender.sendMessage("§c[MCEEW] 在线定位失败或未启用"
                                + "（检查 Countdown.online-location 的 enable 与 key）");
                    } else {
                        sender.sendMessage("§a[MCEEW] 在线定位结果: " + r);
                    }
                });
                return true;
            } else if (args[1].equalsIgnoreCase("region") && args.length >= 4) {
                // /eew test region <省> [市] [区] <震级> [玩家名]
                // 最后一个参数非数字时视为玩家名，模拟只发给该玩家（不打扰其他人）
                double mag;
                String playerName = null;
                int lastIdx;
                try {
                    mag = Double.parseDouble(args[args.length - 1]);
                    lastIdx = args.length - 1;
                } catch (NumberFormatException e) {
                    if (args.length < 5) {
                        sender.sendMessage("§c[MCEEW] 用法: /eew test region <省> [市] [区] <震级> [玩家名]");
                        return true;
                    }
                    playerName = args[args.length - 1];
                    try {
                        mag = Double.parseDouble(args[args.length - 2]);
                    } catch (NumberFormatException e2) {
                        sender.sendMessage("§c[MCEEW] 震级格式错误: " + args[args.length - 2]);
                        return true;
                    }
                    lastIdx = args.length - 2;
                }
                int parts = lastIdx - 2; // 地区名个数: 去掉 "test region" 和 震级(及玩家名)
                if (parts < 1 || parts > 3) {
                    sender.sendMessage("§c[MCEEW] 地区名最多 3 个: 省 市 区");
                    return true;
                }
                String province = null, city = null, district = null;
                StringBuilder region = new StringBuilder();
                for (int i = 2; i < lastIdx; i++) {
                    region.append(args[i]);
                    if (i == 2) province = args[i];
                    if (i == 3) city = args[i];
                    if (i == 4) district = args[i];
                }
                Player target = null;
                if (playerName != null) {
                    target = Bukkit.getPlayerExact(playerName);
                    if (target == null || !target.isOnline()) {
                        sender.sendMessage("§c[MCEEW] 玩家 " + playerName + " 不在线");
                        return true;
                    }
                }
                double[] coords = countdownManager != null
                        ? countdownManager.resolveRegion(province, city, district)
                        : null;
                if (coords == null) {
                    sender.sendMessage("§c[MCEEW] 未找到地区坐标: " + region
                            + "（试试不带\"省/市/区\"后缀、或只用 区名/市名）");
                    return true;
                }
                if (mag <= 0) {
                    sender.sendMessage("§c[MCEEW] 震级必须大于 0");
                    return true;
                }
                String originTimeStr = java.time.LocalDateTime.now().minusSeconds(5)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String lat = String.valueOf(coords[0]);
                String lon = String.valueOf(coords[1]);
                String depth = "10km";
                String intensity = "8";
                String originTime = getDate("yyyy-MM-dd HH:mm:ss", timeFormat, "Asia/Shanghai", originTimeStr);
                // 指定玩家名时只发倒计时给目标，跳过全局广播/Title/警报声，不打扰其他人
                if (target == null) {
                    cencEewAction(originTimeStr, originTime, "1", lat, lon, region.toString(), String.valueOf(mag), depth, getIntensityColor(intensity));
                }
                triggerCountdown("CENC", lat, lon, depth, String.valueOf(mag), intensity,
                        originTimeStr, "yyyy-MM-dd HH:mm:ss", "Asia/Shanghai", region.toString(), target);
                sender.sendMessage("§a[MCEEW] 已模拟震中: " + region + " (M" + mag + ") @ "
                        + String.format("%.3f", coords[0]) + ", " + String.format("%.3f", coords[1])
                        + (target != null ? " §7(仅发送给 " + target.getName() + ")" : ""));
                return true;
            } else {
                sendEewTestHelp(sender);
                return true;
            }
        }
        return false;
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterTab("test", args[0]);
        }
        if (args.length == 2 && "test".equalsIgnoreCase(args[0])) {
            return filterTab(java.util.Arrays.asList("forecast", "alert", "sc", "fj", "cwa", "cenc", "cq", "yibin77", "region", "ip"),
                    args[1]);
        }
        // /eew test region <省> [市] [区] <震级> [玩家名]
        if (args.length >= 3 && "test".equalsIgnoreCase(args[0]) && "region".equalsIgnoreCase(args[1])) {
            if (countdownManager == null) {
                return null;
            }
            String last = args[args.length - 1];
            if (args.length == 3) {
                return countdownManager.tabProvinces(last);
            }
            if (args.length == 4) {
                return countdownManager.tabCities(args[2], last);
            }
            if (args.length == 5) {
                return countdownManager.tabDistricts(args[3], last);
            }
            // args.length == 6: 震级位
            return java.util.Collections.emptyList();
        }
        if (args.length >= 3 && "test".equalsIgnoreCase(args[0]) && "ip".equalsIgnoreCase(args[1])) {
            return java.util.Collections.emptyList();
        }
        return null; // 其余交给默认(玩家名补全)
    }

    private static java.util.List<String> filterTab(String one, String prefix) {
        return filterTab(java.util.Collections.singletonList(one), prefix);
    }

    private static java.util.List<String> filterTab(java.util.List<String> candidates, String prefix) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String c : candidates) {
            if (prefix == null || prefix.isEmpty() || c.toLowerCase().startsWith(prefix.toLowerCase())) {
                out.add(c);
            }
        }
        return out;
    }

    private void sendEewTestHelp(CommandSender sender) {
        sender.sendMessage("§a[MCEEW] §3/eew test forecast§a - Send JMA forecast EEW test.");
        sender.sendMessage("§a[MCEEW] §3/eew test alert§a - Send JMA alert EEW test.");
        sender.sendMessage("§a[MCEEW] §3/eew test sc§a - Send Sichuan EEW test.");
        sender.sendMessage("§a[MCEEW] §3/eew test fj§a - Send Taiwan/Fujian EEW test.");
        sender.sendMessage("§a[MCEEW] §3/eew test cwa§a - Send Taiwan CWA EEW test.");
        sender.sendMessage("§a[MCEEW] §3/eew test cenc§a - Send China CENC EEW test.");
        sender.sendMessage("§a[MCEEW] §3/eew test cq§a - Send Chongqing EEW test.");
        sender.sendMessage("§a[MCEEW] §3/eew test yibin77§a - Send Yibin M7.7 test.");
        sender.sendMessage("§a[MCEEW] §3/eew test ip <ip>§a - Test online IP location (e.g. /eew test ip 117.189.5.195).");
        sender.sendMessage("§a[MCEEW] §3/eew test region <省> [市] [区] <震级> [玩家名]§a - Simulate quake at a region (e.g. /eew test region 四川省 成都市 武侯区 7.0 ; 末尾加玩家名只发给他: /eew test region 贵州省 贵阳市 开阳县 8.0 baiyuxiang12).");
    }

    private synchronized boolean prepareAndLoadConfiguration() {        try {
            configManager.prepareConfig();
        } catch (ConfigManager.ConfigPreparationException error) {
            return false;
        }
        try {
            reloadConfig();
            loadRuntimeConfiguration();
            return true;
        } catch (RuntimeException error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Configuration was prepared but could not be loaded into the runtime.", error);
            return false;
        }
    }

    private void triggerCountdown(String source, String lat, String lon, String depth,
                                  String mag, String intensity, String rawOrigin,
                                  String pattern, String zone, String region) {
        triggerCountdown(source, lat, lon, depth, mag, intensity, rawOrigin, pattern, zone, region, null);
    }

    private void triggerCountdown(String source, String lat, String lon, String depth,
                                  String mag, String intensity, String rawOrigin,
                                  String pattern, String zone, String region, Player target) {
        if (countdownManager == null) {
            return;
        }
        countdownManager.onEew(
                source,
                jp.wolfx.mceew.countdown.CountdownManager.parseDoubleSafe(lat, Double.NaN),
                jp.wolfx.mceew.countdown.CountdownManager.parseDoubleSafe(lon, Double.NaN),
                jp.wolfx.mceew.countdown.CountdownManager.parseDepthKm(depth),
                jp.wolfx.mceew.countdown.CountdownManager.parseDoubleSafe(mag, 0),
                jp.wolfx.mceew.countdown.CountdownManager.parseDoubleSafe(intensity, -1),
                rawOrigin, pattern, zone, region, target);
    }

    private void loadRuntimeConfiguration() {
        jpEewBoolean = getConfig().getBoolean("enable_jp");
        scEewBoolean = getConfig().getBoolean("enable_sc");
        fjEewBoolean = getConfig().getBoolean("enable_fj");
        cwaEewBoolean = getConfig().getBoolean("enable_cwa");
        cencEewBoolean = getConfig().getBoolean("enable_cenceew");
        cqEewBoolean = getConfig().getBoolean("enable_cq");
        broadcastBool = getConfig().getBoolean("Action.broadcast");
        titleBool = getConfig().getBoolean("Action.title");
        alertBool = getConfig().getBoolean("Action.alert");
        jmaEqlistBoolean = getConfig().getBoolean("Action.jma");
        cencEqlistBoolean = getConfig().getBoolean("Action.cenc");
        timeFormat = getConfig().getString("time_format");
        alertBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Alert.broadcast")));
        alertTitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Alert.title")));
        alertSubtitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Alert.subtitle")));
        forecastBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Forecast.broadcast")));
        forecastTitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Forecast.title")));
        forecastSubtitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Forecast.subtitle")));
        jmaEqlistBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Jma.broadcast")));
        cencEqlistBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Cenc.broadcast")));
        sichuanBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Sichuan.broadcast")));
        sichuanTitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Sichuan.title")));
        sichuanSubtitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Sichuan.subtitle")));
        fjBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Fjea.broadcast")));
        fjTitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Fjea.title")));
        fjSubtitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Fjea.subtitle")));
        cwaBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Cwa.broadcast")));
        cwaTitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Cwa.title")));
        cwaSubtitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Cwa.subtitle")));
        cencBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.CencEEW.broadcast")));
        cencTitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.CencEEW.title")));
        cencSubtitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.CencEEW.subtitle")));
        cqBroadcastMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Chongqing.broadcast")));
        cqTitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Chongqing.title")));
        cqSubtitleMessage = LegacyTextFormatter.legacyColors(Objects.requireNonNull(getConfig().getString("Message.Chongqing.subtitle")));
        alertAlertSoundType = getConfig().getString("Sound.Alert.type");
        alertAlertSoundVolume = getConfig().getDouble("Sound.Alert.volume");
        alertAlertSoundPitch = getConfig().getDouble("Sound.Alert.pitch");
        forecastAlertSoundType = getConfig().getString("Sound.Forecast.type");
        forecastAlertSoundVolume = getConfig().getDouble("Sound.Forecast.volume");
        forecastAlertSoundPitch = getConfig().getDouble("Sound.Forecast.pitch");
        scAlertSoundType = getConfig().getString("Sound.Sichuan.type");
        scAlertSoundVolume = getConfig().getDouble("Sound.Sichuan.volume");
        scAlertSoundPitch = getConfig().getDouble("Sound.Sichuan.pitch");
        fjAlertSoundType = getConfig().getString("Sound.Fjea.type");
        fjAlertSoundVolume = getConfig().getDouble("Sound.Fjea.volume");
        fjAlertSoundPitch = getConfig().getDouble("Sound.Fjea.pitch");
        cwaAlertSoundType = getConfig().getString("Sound.Cwa.type");
        cwaAlertSoundVolume = getConfig().getDouble("Sound.Cwa.volume");
        cwaAlertSoundPitch = getConfig().getDouble("Sound.Cwa.pitch");
        cencAlertSoundType = getConfig().getString("Sound.CencEEW.type");
        cencAlertSoundVolume = getConfig().getDouble("Sound.CencEEW.volume");
        cencAlertSoundPitch = getConfig().getDouble("Sound.CencEEW.pitch");
        cqAlertSoundType = getConfig().getString("Sound.Chongqing.type");
        cqAlertSoundVolume = getConfig().getDouble("Sound.Chongqing.volume");
        cqAlertSoundPitch = getConfig().getDouble("Sound.Chongqing.pitch");
        if (countdownManager != null) {
            countdownManager.load();
        }
    }

    @Override
    public void onDisable() {
        if (webSocketManager != null) {
            webSocketManager.stop();
        }
        if (countdownManager != null) {
            countdownManager.shutdown();
        }
        if (platformScheduler != null) {
            platformScheduler.cancelTasks();
        }
    }
}
