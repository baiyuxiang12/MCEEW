package jp.wolfx.mceew.countdown;

import jp.wolfx.mceew.format.LegacyTextFormatter;
import jp.wolfx.mceew.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Per-player S-wave arrival countdown for EEW alerts.
 * Location is derived from the player's IP via ip2region + an embedded city coordinate table.
 */
public final class CountdownManager {

    private static final class ActiveCountdown {
        final long endEpochMs;
        final double totalSeconds;
        final double distanceKm;
        final double depthKm;
        final String region;
        final double magnitude;
        final double feltIntensity;
        final boolean titleOnly;
        long lastStrongSentMs;

        ActiveCountdown(long endEpochMs, double totalSeconds, double distanceKm, double depthKm,
                        String region, double magnitude, double feltIntensity, boolean titleOnly) {
            this.endEpochMs = endEpochMs;
            this.totalSeconds = totalSeconds;
            this.distanceKm = distanceKm;
            this.depthKm = depthKm;
            this.region = region;
            this.magnitude = magnitude;
            this.feltIntensity = feltIntensity;
            this.titleOnly = titleOnly;
            this.lastStrongSentMs = 0;
        }
    }

    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final Logger logger;

    // configuration
    private boolean enabled;
    private double minMagnitude;
    private double minIntensity;
    private double maxDistanceKm;
    private final java.util.TreeMap<Double, Double> maxDistanceByMag = new java.util.TreeMap<>();
    private final List<double[]> speedTable = new ArrayList<>(); // {boundaryKm, speedKmPerS}
    // intensity attenuation: I = a + b*M + c*ln(R + d), R = focal distance (km)
    private boolean attenuationEnabled;
    private double attA;
    private double attB;
    private double attC;
    private double attD;
    private boolean autoColor;
    private String ipDbPath;
    private BarColor barColor;
    private String barTitleTemplate;
    private boolean onArriveTitle;
    private String arriveTitle;
    private String arriveSubtitle;
    private String arriveChat;
    private double strongThreshold;
    private final java.util.TreeMap<Integer, String> strongTitles = new java.util.TreeMap<>();
    private final java.util.TreeMap<Integer, String> strongSubtitles = new java.util.TreeMap<>();
    private long strongRepeatMs;
    private boolean logUnknownIp;
    private double[] fallbackLocation; // used when IP lookup yields no coordinates (null = disabled)
    private String fallbackIp; // non-null when fallback-location is configured as an IP address

    private Ip2RegionSearcher searcher;
    private OnlineLocationProvider onlineLocation;
    private final Map<String, double[]> cityCoords = new HashMap<>();
    private final Map<String, double[]> globalCities = new HashMap<>();   // "CC|省|市" / "CC|市" / "CC|省" -> 坐标（GeoNames）
    private final Map<String, double[]> globalCountries = new HashMap<>(); // "CC" -> 国家中心坐标
    private final Map<String, String> countryCodeByName = new HashMap<>(); // 国家名（英文小写/中文）-> ISO 码

    private final Map<UUID, ActiveCountdown> active = new HashMap<>();
    private final Map<UUID, BossBar> playerBars = new HashMap<>();
    private PlatformScheduler.TaskHandle tickerHandle;

    public CountdownManager(JavaPlugin plugin, PlatformScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.logger = plugin.getLogger();
    }

    /** Reads config, loads the ip database and the embedded city coordinate table. */
    public void load() {
        enabled = plugin.getConfig().getBoolean("Countdown.enable", false);
        minMagnitude = plugin.getConfig().getDouble("Countdown.min-magnitude", 4.0);
        minIntensity = plugin.getConfig().getDouble("Countdown.min-intensity", 1.0);
        maxDistanceKm = plugin.getConfig().getDouble("Countdown.max-distance-km", 500.0);
        maxDistanceByMag.clear();
        for (String entry : plugin.getConfig().getStringList("Countdown.max-distance-by-magnitude")) {
            String[] kv = entry.split(":");
            if (kv.length != 2) continue;
            try {
                double mag = Double.parseDouble(kv[0].trim());
                double dist = Double.parseDouble(kv[1].trim());
                if (mag > 0 && dist > 0) {
                    maxDistanceByMag.put(mag, dist);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (maxDistanceByMag.isEmpty()) {
            maxDistanceByMag.put(4.0, 250.0);
            maxDistanceByMag.put(5.0, 500.0);
            maxDistanceByMag.put(6.0, 800.0);
            maxDistanceByMag.put(7.0, 1500.0);
            maxDistanceByMag.put(8.0, 2500.0);
            maxDistanceByMag.put(9.0, 4000.0);
            maxDistanceByMag.put(10.0, 6000.0);
        }
        attenuationEnabled = plugin.getConfig().getBoolean("Countdown.intensity-attenuation.enable", true);
        attA = plugin.getConfig().getDouble("Countdown.intensity-attenuation.a", 3.0);
        attB = plugin.getConfig().getDouble("Countdown.intensity-attenuation.b", 1.5);
        attC = plugin.getConfig().getDouble("Countdown.intensity-attenuation.c", -2.058);
        attD = plugin.getConfig().getDouble("Countdown.intensity-attenuation.d", 10.0);
        autoColor = plugin.getConfig().getBoolean("Countdown.bossbar.auto-color-by-intensity", true);
        ipDbPath = plugin.getConfig().getString("Countdown.ip-db-path", "plugins/MCEEW/ip2region.xdb");
        logUnknownIp = plugin.getConfig().getBoolean("Countdown.log-unknown-ip", false);
        String fallback = plugin.getConfig().getString("Countdown.fallback-location", "");

        speedTable.clear();
        for (String entry : plugin.getConfig().getStringList("Countdown.s-wave-speed")) {
            String[] kv = entry.split(":");
            if (kv.length != 2) continue;
            String[] range = kv[0].trim().split("-");
            if (range.length != 2) continue;
            try {
                double boundary = Double.parseDouble(range[1].trim());
                double speed = Double.parseDouble(kv[1].trim());
                speedTable.add(new double[]{boundary, speed});
            } catch (NumberFormatException ignored) {
            }
        }
        if (speedTable.isEmpty()) {
            speedTable.add(new double[]{50, 3.6});
            speedTable.add(new double[]{100, 3.8});
            speedTable.add(new double[]{300, 4.0});
            speedTable.add(new double[]{10000, 4.5});
        }
        speedTable.sort((a, b) -> Double.compare(a[0], b[0]));

        barColor = parseBarColor(plugin.getConfig().getString("Countdown.bossbar.color", "YELLOW"));
        barTitleTemplate = LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.title",
                        "&c⚠ &e地震预警 &7| &6{region} &bM{mag} &7深&a{depth}km &7| &e距你 &a{distance}km &7| &c烈度 &4{intensity}&7({intensity_desc}) &7| &b{seconds}s &7后到达"));
        onArriveTitle = plugin.getConfig().getBoolean("Countdown.bossbar.on-arrive-title", true);
        arriveTitle = LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.on-arrive-title-text",
                        "&c⚠ &4地震已到达！"));
        arriveSubtitle = LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.on-arrive-subtitle-text",
                        "&6{region} &bM{mag} &7深&a{depth}km &7| &c你处烈度 &4{intensity}&7({intensity_desc}) &7| &e请就近躲避，注意余震"));
        arriveChat = LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.on-arrive-chat", ""));
        // 本地烈度 >= 阈值时，预警到达瞬间立即发 Title（不等倒计时归零）
        // 按烈度分级措辞，避免"烈度3也叫强震预警"吓人
        strongThreshold = plugin.getConfig().getDouble("Countdown.strong-title-threshold", 3.0);
        strongTitles.clear();
        strongSubtitles.clear();
        strongTitles.put(3, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-titles.3", "&e有感地震提醒")));
        strongTitles.put(4, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-titles.4", "&6明显有感")));
        strongTitles.put(5, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-titles.5", "&c较强震感")));
        strongTitles.put(6, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-titles.6", "&c⚠ &4强震预警")));
        strongTitles.put(7, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-titles.7", "&4☠ &4毁灭性地震")));
        strongSubtitles.put(3, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-subtitles.3",
                        "&6{region} &bM{mag} &7| 你处烈度 &4{intensity}&7({intensity_desc}) &7| &b预计 {seconds}s &7后到达，请留意安全")));
        strongSubtitles.put(4, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-subtitles.4",
                        "&6{region} &bM{mag} &7| 你处烈度 &4{intensity}&7({intensity_desc}) &7| &b预计 {seconds}s &7后到达，请就近避险")));
        strongSubtitles.put(5, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-subtitles.5",
                        "&c{region} &bM{mag} &7| 你处烈度 &4{intensity}&7({intensity_desc}) &7| &b预计 {seconds}s &7后到达，请立即避险")));
        strongSubtitles.put(6, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-subtitles.6",
                        "&4{region} &bM{mag} &7| 你处烈度 &4{intensity}&7({intensity_desc}) &7| &b预计 {seconds}s &7后到达，请立即避险，保护头部")));
        strongSubtitles.put(7, LegacyTextFormatter.legacyColors(
                plugin.getConfig().getString("Countdown.bossbar.strong-subtitles.7",
                        "&4{region} &bM{mag} &7| 你处烈度 &4{intensity}&7({intensity_desc}) &7| &b预计 {seconds}s &7后到达，请立即逃生，远离一切建筑物")));
        // 强震 Title 重复刷新间隔（秒），0 = 只发一次；默认 1 = 每秒刷新倒计时
        strongRepeatMs = (long) (plugin.getConfig().getDouble("Countdown.bossbar.strong-repeat-seconds", 1.0) * 1000);

        cityCoords.clear();
        try (InputStream in = plugin.getResource("city_coords.json")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    com.google.gson.JsonObject raw = (com.google.gson.JsonObject) parseJson(reader);
                    for (Map.Entry<String, com.google.gson.JsonElement> e : raw.entrySet()) {
                        com.google.gson.JsonObject v = e.getValue().getAsJsonObject();
                        double lat = v.get("lat").getAsDouble();
                        double lon = v.get("lon").getAsDouble();
                        cityCoords.put(e.getKey(), new double[]{lat, lon});
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to load city_coords.json: " + e.getMessage());
        }

        // 全球坐标表（GeoNames）：海外 IP（英文 国家|省|市）离线定位用，国家中心兜底
        globalCities.clear();
        globalCountries.clear();
        countryCodeByName.clear();
        try (InputStream in = plugin.getResource("global_geo.json")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    com.google.gson.JsonObject raw = (com.google.gson.JsonObject) parseJson(reader);
                    com.google.gson.JsonObject countries = raw.getAsJsonObject("countries");
                    for (Map.Entry<String, com.google.gson.JsonElement> e : countries.entrySet()) {
                        com.google.gson.JsonObject v = e.getValue().getAsJsonObject();
                        double lat = v.get("lat").getAsDouble();
                        double lon = v.get("lon").getAsDouble();
                        String iso = e.getKey();
                        globalCountries.put(iso, new double[]{lat, lon});
                        if (v.has("en") && !v.get("en").isJsonNull()) {
                            countryCodeByName.put(v.get("en").getAsString().toLowerCase(), iso);
                        }
                        if (v.has("zh") && !v.get("zh").isJsonNull()) {
                            String zh = v.get("zh").getAsString();
                            if (!zh.isEmpty()) {
                                countryCodeByName.put(zh, iso);
                            }
                        }
                    }
                    com.google.gson.JsonObject cities = raw.getAsJsonObject("cities");
                    for (Map.Entry<String, com.google.gson.JsonElement> e : cities.entrySet()) {
                        com.google.gson.JsonArray a = e.getValue().getAsJsonArray();
                        globalCities.put(e.getKey(), new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()});
                    }
                }
                logger.info("Global geo loaded: " + globalCountries.size() + " countries, "
                        + globalCities.size() + " cities (offline overseas location).");
            } else {
                logger.warning("global_geo.json missing from jar - overseas offline location disabled.");
            }
        } catch (Exception e) {
            logger.warning("Failed to load global_geo.json: " + e.getMessage());
        }

        Path db = Path.of(ipDbPath);
        searcher = Ip2RegionSearcher.load(db);
        if (searcher == null) {
            logger.warning("ip2region.xdb not found at " + db.toAbsolutePath()
                    + " - IP-based countdown will be disabled. Put ip2region.xdb into the MCEEW plugin folder.");
        } else {
            logger.info("ip2region.xdb loaded (" + cityCoords.size() + " city entries).");
        }

        // 在线 IP 定位增强：离线表只到城市级，配置腾讯 key 后玩家加入时异步预取区县级定位
        boolean onlineEnabled = plugin.getConfig().getBoolean("Countdown.online-location.enable", false);
        String onlineProvider = plugin.getConfig().getString("Countdown.online-location.provider", "tencent");
        String onlineKey = plugin.getConfig().getString("Countdown.online-location.key", "");
        String onlineSk = plugin.getConfig().getString("Countdown.online-location.sk", "");
        double onlineCacheHours = plugin.getConfig().getDouble("Countdown.online-location.cache-hours", 24.0);
        if (onlineEnabled) {
            OnlineLocationProvider candidate = new OnlineLocationProvider(onlineProvider, onlineKey, onlineSk, onlineCacheHours, logger);
            if (candidate.isEnabled()) {
                onlineLocation = candidate;
                logger.info("Online IP location enabled (provider=" + onlineProvider
                        + ", cache " + onlineCacheHours + "h, one call per IP per window).");
            } else {
                onlineLocation = null;
                logger.warning("Countdown.online-location.enable is true but the key is empty or the "
                        + "provider is unsupported; online lookup disabled, using offline ip2region only.");
            }
        } else {
            onlineLocation = null;
        }

        // fallback 必须在 cityCoords 加载之后解析（查表需要坐标数据）
        fallbackIp = null;
        fallbackLocation = parseFallbackLocation(fallback);
        if (fallbackLocation != null) {
            logger.info("Countdown fallback location: ("
                    + fallbackLocation[0] + ", " + fallbackLocation[1] + ")"
                    + (fallbackIp != null ? " [from IP " + fallbackIp + "]" : ""));
        }
        // fallback 配置为 IP 时, 异步预取在线定位, 使内网玩家也能用区县级兜底坐标
        if (fallbackIp != null && onlineLocation != null && onlineLocation.isEnabled()
                && !OnlineLocationProvider.isPrivateIp(fallbackIp)) {
            final String fip = fallbackIp;
            scheduler.runAsync(() -> {
                OnlineLocationProvider.Result r = onlineLocation.lookup(fip);
                if (r != null) {
                    logger.info("Online location (fallback IP): " + fip + " -> " + r);
                } else {
                    logger.info("Online location (fallback IP) failed: " + fip
                            + " (using offline city center)");
                }
            });
        }
    }

    public void shutdown() {
        if (tickerHandle != null) {
            tickerHandle.cancel();
            tickerHandle = null;
        }
        for (BossBar bar : playerBars.values()) {
            bar.removeAll();
        }
        playerBars.clear();
        active.clear();
    }

    /** Called when an EEW alert arrives (from any source). target != null = only that player (test mode). */
    public void onEew(String source, double lat, double lon, double depthKm, double magnitude,
                      double maxIntensity, String rawOriginTime, String pattern, String zone,
                      String regionName, org.bukkit.entity.Player target) {
        if (!enabled) return;
        if (Double.isNaN(magnitude) || magnitude < minMagnitude) return;
        // When attenuation is off, fall back to the report's maximum intensity filter.
        // When attenuation is on, per-player intensity is computed in handlePlayer().
        if (!attenuationEnabled && maxIntensity >= 0 && maxIntensity < minIntensity) return;

        long originEpoch;
        try {
            originEpoch = parseTime(rawOriginTime, pattern, zone);
        } catch (Exception e) {
            logger.warning("Unable to parse origin time '" + rawOriginTime + "' (" + source + "): " + e.getMessage());
            return;
        }

        if (target != null && target.isOnline()) {
            // 测试模式: 只发给指定玩家, 不打扰服务器其他人
            handlePlayer(target, lat, lon, depthKm, magnitude, originEpoch, regionName, maxIntensity);
            return;
        }
        scheduler.forEachPlayer(player -> handlePlayer(
                player, lat, lon, depthKm, magnitude, originEpoch, regionName, maxIntensity));
    }

    /** Clears all countdown bars (e.g. JMA cancel report). */
    public void onCancel() {
        if (!enabled) return;
        scheduler.runGlobal(this::clearAll);
    }

    private void handlePlayer(Player player, double lat, double lon, double depthKm,
                              double magnitude, long originEpoch, String regionName,
                              double reportMaxIntensity) {
        if (player == null || !player.isOnline()) return;
        double[] loc = locate(player);
        if (loc == null) {
            loc = fallbackLocation();
            if (loc == null) {
                if (logUnknownIp) {
                    logger.info("Countdown: no IP location for " + player.getName());
                }
                return;
            }
        }
        double distanceKm = haversineKm(lat, lon, loc[0], loc[1]);
        // 最大距离随震级放宽：取 基础值 与 震级表 中较大者（大震覆盖更远）
        double effectiveMax = maxDistanceKm;
        java.util.Map.Entry<Double, Double> magEntry = maxDistanceByMag.floorEntry(magnitude);
        if (magEntry != null && magEntry.getValue() > effectiveMax) {
            effectiveMax = magEntry.getValue();
        }
        if (distanceKm > effectiveMax) {
            logger.info("Countdown: " + player.getName() + " skipped (distance " + (int) distanceKm
                    + "km > " + (int) effectiveMax + "km for M" + magnitude + ")");
            return;
        }

        double focalKm = Math.sqrt(distanceKm * distanceKm + depthKm * depthKm);
        double feltIntensity;
        if (attenuationEnabled) {
            feltIntensity = attA + attB * magnitude + attC * Math.log(focalKm + attD);
            if (feltIntensity < 0) feltIntensity = 0;
            if (feltIntensity > 12) feltIntensity = 12;
        } else {
            feltIntensity = reportMaxIntensity;
        }
        if (feltIntensity < minIntensity) {
            logger.info("Countdown: " + player.getName() + " skipped (intensity " + feltIntensity + " < " + minIntensity + ")");
            return;
        }

        double travelSeconds = focalKm / speedFor(focalKm);
        long etaMs = originEpoch + (long) (travelSeconds * 1000.0) - System.currentTimeMillis();
        if (etaMs <= 0) {
            // 预警盲区：近震中玩家倒计时已为负，不发倒计时，但立即提示"已到达"
            logger.info("Countdown: " + player.getName() + " blind zone (ETA " + (etaMs / 1000.0)
                    + "s <= 0, " + (int) Math.round(distanceKm) + "km)");
            ActiveCountdown cd0 = new ActiveCountdown(System.currentTimeMillis(), travelSeconds,
                    distanceKm, depthKm, regionName, magnitude, feltIntensity, false);
            scheduler.runGlobal(() -> {
                if (onArriveTitle) {
                    player.sendTitle(arriveTitle, renderTemplate(arriveSubtitle, cd0, 0), 5, 40, 10);
                }
                if (arriveChat != null && !arriveChat.isEmpty()) {
                    player.sendMessage(renderTemplate(arriveChat, cd0, 0));
                }
            });
            return;
        }

        long endEpochMs = System.currentTimeMillis() + etaMs;
        // 本地烈度 >= 阈值 → 只发强震 Title，不再显示 BossBar
        final boolean titleOnly = feltIntensity >= strongThreshold;
        ActiveCountdown cd = new ActiveCountdown(endEpochMs, travelSeconds, distanceKm, depthKm,
                regionName, magnitude, feltIntensity, titleOnly);
        logger.info("Countdown -> " + player.getName() + ": " + (int) Math.round(distanceKm)
                + "km, felt " + String.format("%.1f", feltIntensity)
                + ", travel " + String.format("%.1f", travelSeconds) + "s, ETA " + (etaMs / 1000.0) + "s"
                + (titleOnly ? " [TITLE-ONLY]" : " [BOSS-BAR]"));
        scheduler.runGlobal(() -> {
            active.put(player.getUniqueId(), cd);
            ensureTicker();
            if (titleOnly) {
                long secs = Math.max(0, (cd.endEpochMs - System.currentTimeMillis()) / 1000);
                player.sendTitle(titleFor(cd.feltIntensity), renderTemplate(subtitleFor(cd.feltIntensity), cd, secs), 5, 60, 5);
            }
        });
    }

    /** 解析 "省|市|区" 名称 → 坐标（逐级降级匹配）。返回 {lat, lon} 或 null。 */
    public double[] resolveRegion(String province, String city, String district) {
        if (cityCoords.isEmpty()) return null;
        double[] c = null;
        if (province != null && city != null && district != null) {
            c = cityCoords.get(province + "|" + city + "|" + district);
        }
        if (c == null && city != null && district != null) {
            c = cityCoords.get(city + "|" + district);
        }
        if (c == null && district != null) {
            c = cityCoords.get(district);
        }
        if (c == null && province != null && city != null) {
            c = cityCoords.get(province + "|" + city);
        }
        if (c == null && city != null) {
            c = cityCoords.get(city);
        }
        if (c == null && province != null) {
            c = cityCoords.get(province);
        }
        return c;
    }

    private double[] locate(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return null;
        String ip = address.getAddress().getHostAddress();
        // 在线定位优先：玩家加入时已异步预取，缓存命中即有区县级精度与精确坐标
        if (onlineLocation != null) {
            OnlineLocationProvider.Result r = onlineLocation.cached(ip);
            if (r != null) {
                return new double[]{r.lat, r.lon};
            }
        }
        return offlineIpCoords(ip);
    }

    /**
     * 离线 IP → 坐标。兼容 ip2region v2（中国中文）与 v4（海外英文）字段布局：
     *   v2 中国: "中国|0|贵州省|贵阳市|移动"   → 中文省/市 → cityCoords
     *   v4 海外: "Japan|Hokkaido|Ishikari|0|JP" → 英文国家/省/市 → GeoNames 全球表
     * 查询链: 省|市 → 市 → 省 → 国家中心；返回 null 表示未知。
     */
    private double[] offlineIpCoords(String ip) {
        if (searcher == null || ip == null) return null;
        String region = searcher.search(ip);
        if (region == null) return null;
        String[] parts = region.split("\\|");
        if (parts.length == 0) return null;
        String countryName = parts[0];
        String province, city;
        if (parts.length > 1 && !parts[1].isEmpty() && !parts[1].equals("0")) {
            // v4 海外风格: 国家|省|市|0|国家码
            province = parts[1];
            city = parts.length > 2 && !parts[2].isEmpty() && !parts[2].equals("0") ? parts[2] : null;
        } else {
            // v2 中国风格: 国家|0|省|市|ISP
            province = parts.length > 2 ? parts[2] : null;
            city = parts.length > 3 ? parts[3] : null;
        }
        String cc = countryCode(countryName);
        if (cc == null) return null;
        if (isCnRegion(cc)) {
            // 中文区: 现有城市坐标表
            double[] c = null;
            if (province != null && city != null) {
                c = cityCoords.get(province + "|" + city);
            }
            if (c == null && city != null) {
                c = cityCoords.get(city);
            }
            if (c == null && province != null) {
                c = cityCoords.get(province);
            }
            if (c == null) {
                c = globalCountries.get(cc); // 港澳台等中文区兜底国家中心
            }
            return c;
        }
        // 海外: GeoNames 全球表, 省|市 → 市 → 省 → 国家中心
        if (!globalCities.isEmpty()) {
            if (province != null && city != null) {
                double[] c = globalCities.get(cc + "|" + province + "|" + city);
                if (c != null) return c;
            }
            if (city != null) {
                double[] c = globalCities.get(cc + "|" + city);
                if (c != null) return c;
            }
            if (province != null) {
                double[] c = globalCities.get(cc + "|" + province);
                if (c != null) return c;
            }
        }
        return globalCountries.get(cc);
    }

    /** 国家名（英文小写 / 中文）→ ISO 码；未知返回 null。 */
    private String countryCode(String name) {
        if (name == null || name.isEmpty()) return null;
        String iso = countryCodeByName.get(name);
        if (iso == null) {
            iso = countryCodeByName.get(name.toLowerCase());
        }
        return iso;
    }

    private boolean isCnRegion(String cc) {
        return "CN".equals(cc) || "TW".equals(cc) || "HK".equals(cc) || "MO".equals(cc);
    }

    /**
     * 玩家加入时异步预取在线定位（每 IP 每缓存窗口最多一次 HTTP，不阻塞主线程）。
     * 查询成功即写入 provider 缓存，EEW 到达时同步命中。
     */
    public void prefetchPlayerLocation(Player player) {
        if (onlineLocation == null || !onlineLocation.isEnabled()) return;
        if (player == null || !player.isOnline()) return;
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return;
        String ip = address.getAddress().getHostAddress();
        if (OnlineLocationProvider.isPrivateIp(ip)) return; // 局域网/内网直接走 fallback，不浪费调用
        if (onlineLocation.cached(ip) != null) return;
        final String fip = ip;
        scheduler.runAsync(() -> {
            OnlineLocationProvider.Result r = onlineLocation.lookup(fip);
            if (r != null) {
                logger.info("Online location prefetch: " + fip + " -> " + r);
            } else {
                logger.info("Online location prefetch failed: " + fip + " (using offline/fallback on EEW)");
            }
        });
    }

    /** 调试用（/eew test ip）：异步查询在线定位，回调在主线程执行。 */
    public void lookupIpAsync(String ip, java.util.function.Consumer<OnlineLocationProvider.Result> callback) {
        if (onlineLocation == null || !onlineLocation.isEnabled()) {
            scheduler.runGlobal(() -> callback.accept(null));
            return;
        }
        final String fip = ip;
        scheduler.runAsync(() -> {
            OnlineLocationProvider.Result r = onlineLocation.lookup(fip);
            if (r != null) {
                logger.info("Online location (/eew test ip): " + fip + " -> " + r);
            } else {
                logger.info("Online location (/eew test ip) failed: " + fip);
            }
            scheduler.runGlobal(() -> callback.accept(r));
        });
    }

    /** 解析 "lat,lon" / 城市或省份名 / IP 地址 → 坐标。返回 null 表示未设置或未知。 */
    private double[] parseFallbackLocation(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        String[] parts = trimmed.split(",");
        if (parts.length == 2) {
            try {
                return new double[]{
                        Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim())};
            } catch (NumberFormatException ignored) {
            }
        }
        // IP 地址: 用在线定位缓存 / 离线表解析，玩家定位不到时按该 IP 的位置兜底
        if (isIpAddress(trimmed)) {
            fallbackIp = trimmed;
            return resolveIpCoords(trimmed);
        }
        double[] c = cityCoords.get(trimmed);
        if (c == null) {
            c = cityCoords.get(trimmed + "市");
        }
        return c;
    }

    /** fallback 位置: 配置为 IP 时优先用在线定位缓存（区县级），否则用离线解析坐标。 */
    private double[] fallbackLocation() {
        if (fallbackIp != null && onlineLocation != null) {
            OnlineLocationProvider.Result r = onlineLocation.cached(fallbackIp);
            if (r != null) {
                return new double[]{r.lat, r.lon};
            }
        }
        return fallbackLocation;
    }

    /** 用在线定位缓存 / ip2region 离线表解析一个 IP → 坐标（省市区 → cityCoords / GeoNames）。 */
    private double[] resolveIpCoords(String ip) {
        if (onlineLocation != null) {
            OnlineLocationProvider.Result r = onlineLocation.cached(ip);
            if (r != null) {
                return new double[]{r.lat, r.lon};
            }
        }
        return offlineIpCoords(ip);
    }

    private static boolean isIpAddress(String value) {
        return value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private void ensureTicker() {
        if (tickerHandle != null) return;
        tickerHandle = scheduler.runAsyncDelayed(this::tick, 1, TimeUnit.SECONDS);
    }

    private void tick() {
        scheduler.runGlobal(this::updateBossBars);
        tickerHandle = null;
        if (!active.isEmpty()) {
            tickerHandle = scheduler.runAsyncDelayed(this::tick, 1, TimeUnit.SECONDS);
        }
    }

    private void updateBossBars() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ActiveCountdown>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveCountdown> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            ActiveCountdown cd = entry.getValue();
            if (player == null || !player.isOnline()) {
                it.remove();
                removeBar(entry.getKey());
                continue;
            }
            long remainMs = cd.endEpochMs - now;
            if (remainMs <= 0) {
                it.remove();
                removeBar(entry.getKey());
                if (onArriveTitle) {
                    player.sendTitle(arriveTitle, renderTemplate(arriveSubtitle, cd, 0), 5, 40, 10);
                }
                if (arriveChat != null && !arriveChat.isEmpty()) {
                    player.sendMessage(renderTemplate(arriveChat, cd, 0));
                }
                continue;
            }
            // titleOnly 模式：只保留到达提示，不创建/更新 BossBar；
            // 强震 Title 按 strongRepeatMs 间隔重复刷新（带最新剩余秒数）
            if (cd.titleOnly) {
                if (strongRepeatMs > 0 && now - cd.lastStrongSentMs >= strongRepeatMs) {
                    cd.lastStrongSentMs = now;
                    long secs = Math.max(0, (cd.endEpochMs - now) / 1000);
                    player.sendTitle(titleFor(cd.feltIntensity), renderTemplate(subtitleFor(cd.feltIntensity), cd, secs), 5, 60, 5);
                }
                continue;
            }
            BossBar bar = getBar(player);
            if (autoColor) {
                bar.setColor(colorFor(cd.feltIntensity));
            }
            bar.setProgress(Math.max(0.0, Math.min(1.0, remainMs / (cd.totalSeconds * 1000.0))));
            bar.setTitle(renderTitle(cd, remainMs));
        }
        if (active.isEmpty()) {
            for (BossBar bar : playerBars.values()) {
                bar.removeAll();
            }
            playerBars.clear();
        }
    }

    private BossBar getBar(Player player) {
        BossBar bar = playerBars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar("", barColor, BarStyle.SOLID);
            bar.addPlayer(player);
            playerBars.put(player.getUniqueId(), bar);
        }
        return bar;
    }

    private BarColor colorFor(double feltIntensity) {
        if (feltIntensity >= 3) {
            return BarColor.RED;
        } else if (feltIntensity >= 1) {
            return BarColor.YELLOW;
        }
        return BarColor.GREEN;
    }

    private void removeBar(UUID playerId) {
        BossBar bar = playerBars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
        }
    }

    private void clearAll() {
        for (BossBar bar : playerBars.values()) {
            bar.removeAll();
        }
        playerBars.clear();
        active.clear();
    }

    private String renderTitle(ActiveCountdown cd, long remainMs) {
        long seconds = Math.max(0, remainMs / 1000);
        return renderTemplate(barTitleTemplate, cd, seconds);
    }

    /** 统一占位符渲染: {region} {mag} {depth} {distance} {intensity} {intensity_desc} {seconds} */
    private String renderTemplate(String template, ActiveCountdown cd, long seconds) {
        String title = template;
        title = title.replace("{region}", cd.region == null ? "?" : cd.region);
        title = title.replace("{mag}", String.valueOf(cd.magnitude));
        title = title.replace("{depth}", String.valueOf((int) Math.round(cd.depthKm)));
        title = title.replace("{distance}", String.valueOf((int) Math.round(cd.distanceKm)));
        title = title.replace("{intensity}", String.valueOf((int) Math.round(cd.feltIntensity)));
        title = title.replace("{intensity_desc}", intensityDesc(cd.feltIntensity));
        title = title.replace("{seconds}", String.valueOf(seconds));
        return title;
    }

    /** 中国烈度等级 → 感受描述 */
    static String intensityDesc(double intensity) {
        if (intensity >= 7) return "毁灭性（建筑物倒塌）";
        if (intensity >= 6) return "剧烈（破坏性）";
        if (intensity >= 5) return "强烈有感（站立不稳）";
        if (intensity >= 4) return "明显有感（室内多数人有感）";
        if (intensity >= 3) return "有感（门窗作响）";
        if (intensity >= 1) return "微有感";
        return "无感";
    }

    /** 按烈度选档：取 <= felt 的最高档文案 */
    private String titleFor(double felt) {
        Integer tier = strongTitles.floorKey((int) Math.floor(felt));
        if (tier == null) {
            tier = strongTitles.isEmpty() ? null : strongTitles.lastKey();
        }
        return tier == null ? "" : strongTitles.get(tier);
    }

    private String subtitleFor(double felt) {
        Integer tier = strongSubtitles.floorKey((int) Math.floor(felt));
        if (tier == null) {
            tier = strongSubtitles.isEmpty() ? null : strongSubtitles.lastKey();
        }
        return tier == null ? "" : strongSubtitles.get(tier);
    }

    private double speedFor(double focalKm) {
        for (double[] entry : speedTable) {
            if (focalKm <= entry[0]) {
                return entry[1];
            }
        }
        return speedTable.isEmpty() ? 3.5 : speedTable.get(speedTable.size() - 1)[1];
    }

    // --- helpers ---

    static long parseTime(String text, String pattern, String zone) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
        LocalDateTime local = LocalDateTime.parse(text, fmt);
        return local.atZone(ZoneId.of(zone)).toInstant().toEpochMilli();
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** Parses "10km", "10", "?", null → km. Unknown returns 10 (default crust depth). */
    public static double parseDepthKm(String raw) {
        if (raw == null) return 10;
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) return 10;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public static double parseDoubleSafe(String raw, double fallback) {
        if (raw == null) return fallback;
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) return fallback;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static BarColor parseBarColor(String name) {
        try {
            return BarColor.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return BarColor.YELLOW;
        }
    }

    private static Object parseJson(InputStreamReader reader) throws Exception {
        return new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
    }
}
