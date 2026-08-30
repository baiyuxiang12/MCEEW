package jp.wolfx.mceew.countdown;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Online IP location enhancement (Tencent Location Service WebService IP positioning).
 *
 * <p>The bundled ip2region xdb only resolves to city level. When a key is configured this
 * provider queries the Tencent WebService IP API off the server thread (players are prefetched
 * on join), caches results for {@code cache-hours}, and issues at most one HTTP call per IP
 * per cache window. Any failure falls back to the offline ip2region pipeline, so the countdown
 * keeps working even when the online API is unreachable or rate-limited.
 */
public final class OnlineLocationProvider {

    /** A successful online lookup result. */
    public static final class Result {
        public final double lat;
        public final double lon;
        public final String province;
        public final String city;
        public final String district;
        public final long fetchedMs;

        Result(double lat, double lon, String province, String city, String district, long fetchedMs) {
            this.lat = lat;
            this.lon = lon;
            this.province = province;
            this.city = city;
            this.district = district;
            this.fetchedMs = fetchedMs;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (province != null) sb.append(province);
            if (city != null) sb.append(city);
            if (district != null) sb.append(district);
            if (sb.length() == 0) sb.append("?");
            sb.append(String.format(" (%.5f, %.5f)", lat, lon));
            return sb.toString();
        }
    }

    private static final String TENCENT_ENDPOINT = "https://apis.map.qq.com/ws/location/v1/ip";
    private static final int TIMEOUT_MS = 3000;

    private final String provider;
    private final String key;
    private final String secretKey;
    private final long cacheTtlMs;
    private final Logger logger;
    private final Map<String, Result> cache = new ConcurrentHashMap<>();

    public OnlineLocationProvider(String provider, String key, String secretKey, double cacheHours, Logger logger) {
        this.provider = provider == null ? "" : provider.trim();
        this.key = key == null ? "" : key.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.cacheTtlMs = (long) (Math.max(0, cacheHours) * 3600_000L);
        this.logger = logger;
    }

    /** Whether online lookup is usable (supported provider and a key is configured). */
    public boolean isEnabled() {
        return "tencent".equalsIgnoreCase(provider) && !key.isEmpty();
    }

    /** Cached result for an IP, or null when absent or expired. */
    public Result cached(String ip) {
        if (ip == null) return null;
        Result result = cache.get(ip);
        if (result == null) return null;
        if (System.currentTimeMillis() - result.fetchedMs >= cacheTtlMs) {
            cache.remove(ip);
            return null;
        }
        return result;
    }

    /**
     * Blocking online lookup. Must only be called off the server thread (see lookupAsync usage).
     * Stores a fresh result in the cache on success; returns null on any failure.
     */
    public Result lookup(String ip) {
        if (!isEnabled() || ip == null || isPrivateIp(ip)) return null;
        try {
            String encodedIp = URLEncoder.encode(ip, StandardCharsets.UTF_8.name());
            String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name());
            String sig = "";
            if (!secretKey.isEmpty()) {
                // 腾讯 WebService 签名: 参数按名升序(a-z), 使用"未编码"原始值拼接,
                // sig = md5(路径 + "?" + 排序后参数串 + SK)，小写32位；请求 URL 中的入参才做 Url 编码
                String paramStr = "ip=" + ip + "&key=" + key; // ip < key 按 ASCII 升序
                sig = "&sig=" + md5Hex("/ws/location/v1/ip?" + paramStr + secretKey);
            }
            String url = TENCENT_ENDPOINT + "?ip=" + encodedIp + "&key=" + encodedKey + sig;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "MCEEW-Countdown/2.9.1");
            conn.setRequestMethod("GET");
            try {
                int code = conn.getResponseCode();
                if (code != 200) {
                    logger.warning("Online location: HTTP " + code + " for " + ip);
                    return null;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
                    return parse(root, ip);
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            logger.warning("Online location lookup failed for " + ip + ": " + e.getMessage());
            return null;
        }
    }

    private Result parse(JsonObject root, String ip) {
        if (root.has("status") && root.get("status").getAsInt() != 0) {
            String message = root.has("message") && !root.get("message").isJsonNull()
                    ? root.get("message").getAsString() : "?";
            logger.warning("Online location: API error " + root.get("status").getAsInt()
                    + " (" + message + ") for " + ip);
            return null;
        }
        if (!root.has("result")) return null;
        JsonObject result = root.getAsJsonObject("result");
        if (!result.has("location")) return null;
        JsonObject location = result.getAsJsonObject("location");
        double lat = location.get("lat").getAsDouble();
        double lon = location.get("lng").getAsDouble();
        String province = null, city = null, district = null;
        if (result.has("ad_info")) {
            JsonObject ad = result.getAsJsonObject("ad_info");
            province = stringOrNull(ad, "province");
            city = stringOrNull(ad, "city");
            district = stringOrNull(ad, "district");
        }
        Result r = new Result(lat, lon, province, city, district, System.currentTimeMillis());
        cache.put(ip, r);
        return r;
    }

    private static String stringOrNull(JsonObject obj, String member) {
        if (obj.has(member) && !obj.get(member).isJsonNull()) {
            String value = obj.get(member).getAsString();
            return value == null || value.isEmpty() ? null : value;
        }
        return null;
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** LAN / loopback / CGNAT addresses are never sent to the online API. */
    public static boolean isPrivateIp(String ip) {
        if (ip == null || ip.isEmpty()) return true;
        if (ip.contains(":")) {
            // IPv6 loopback / link-local / unique-local
            return ip.equals("::1") || ip.startsWith("fe80:")
                    || ip.startsWith("fc") || ip.startsWith("fd");
        }
        if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0")) return true;
        String[] p = ip.split("\\.");
        if (p.length != 4) return true;
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            if (a == 10) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 169 && b == 254) return true;
            if (a == 100 && b >= 64 && b <= 127) return true; // CGNAT 100.64.0.0/10
        } catch (NumberFormatException e) {
            return true;
        }
        return false;
    }
}
