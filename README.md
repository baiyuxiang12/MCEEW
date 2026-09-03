# MCEEW

A real-time Earthquake Early Warning (EEW) plugin for Minecraft servers and
Velocity, BungeeCord, and Waterfall proxy networks.

---

## 本 Fork 新增：玩家 S 波倒计时（地震预警）

> 在原版 MCEEW 基础上，为 **Bukkit 服务器**（Spigot/Paper/Folia）增加按玩家位置计算的地震 S 波到达倒计时：
> 定位玩家 → 按震中距推算 S 波到达时间 → BossBar / Title 显示剩余秒数。

### 功能特性

* **按玩家 IP 定位**，S 波到达倒计时（BossBar 进度条 / 强震 Title 每秒刷新）
* **在线 IP 定位增强（可选）**：腾讯位置服务，精确到区县并返回精确坐标；
  离线表（ip2region）只到城市级，在线定位失败自动降级，不影响倒计时
* **烈度衰减估算**：`I = a + b·M + c·ln(水平距离+d)`，按玩家位置算烈度（**与小米手机预警同口径**，
  默认 a=2.941 b=1.363 c=-1.494 d=7），显示实际估算值不虚高
* **分级措辞**：烈度 3 有感 / 4 明显 / 5 较强 / 6 强震 / 7 毁灭性，避免小题大做
* **智能过滤**：距离过滤随震级放宽（M4 → 250km … M10 → 6000km）、低烈度过滤、
  盲区兜底（近震中立即提示"已到达"）
* **倒计时语音（可选资源包）**：触发播预警音（按国标蓝/黄/橙/红）+ 逐秒中文报数，素材从小米安全中心提取；不装资源包则无声，不影响倒计时
* **多源支持**：日本 / 四川 / 福建 / 台湾 / 中国国家台网 / 重庆 的 EEW 均可触发

### 安装

1. 将 `MCEEW-x.y.z.jar` 放入 `plugins/`，启动一次生成配置
2. 将 [ip2region.xdb](https://github.com/lionsoul2014/ip2region) 放入 `plugins/MCEEW/ip2region.xdb`
   （离线定位数据源，约 10MB，城市级）

### 配置（config.yml → `Countdown` 段）

```yaml
Countdown:
  enable: true                    # 总开关（必须显式写 true）
  min-magnitude: 4.0              # 低于该震级不提醒
  min-intensity: 1.0              # 玩家位置预估烈度低于该值不提醒
  max-distance-km: 500            # 基础最大提醒距离
  max-distance-by-magnitude:      # 距离随震级放宽
    - "4.0: 250"
    - "10.0: 6000"
  s-wave-speed:                   # S 波分段速度模型 (km/s)
    - "0-50: 3.6"
    - "300-10000: 4.5"
  intensity-attenuation:          # 烈度衰减系数
    enable: true
    a: 3.0
    b: 1.5
    c: -2.058
    d: 10
  ip-db-path: "plugins/MCEEW/ip2region.xdb"
  # 兜底位置（玩家定位不到时，如内网/局域网玩家）。三种格式任选：
  #   "城市名"（如 成都市）、"纬度,经度"（如 30.67,104.06）、
  #   "IP地址"（如 117.189.5.195，按该 IP 的在线/离线定位结果兜底，内网玩家即按服务器出口位置算）
  fallback-location: ""
  online-location:                # 在线 IP 定位增强（可选）
    enable: false
    provider: "tencent"
    key: ""                       # 腾讯位置服务 WebServiceAPI key
    sk: ""                        # 签名密钥（控制台"密钥管理"），带 sk 自动计算 sig
    cache-hours: 24               # 每 IP 缓存小时数（同 IP 一天只查一次）
  bossbar:                        # 文案/颜色/到达提示等，见 config.yml 内注释
    # ...
```

**腾讯 key 申请**：https://lbs.qq.com/ → 控制台 → 应用管理 → 创建应用 → 添加 WebServiceAPI。
个人开发者配额约 6000 次/天（各接口独立），本插件用量为"玩家数/天"，远低于配额；
配额满或失败时自动降级离线表，倒计时功能不受影响。

### 倒计时语音（可选资源包）

模仿手机地震预警的播报：触发时按国标播放预警音（烈度 <3 蓝 / 3-4 黄 / 5-6 橙 / 7+ 红），
预警音播完后**逐秒中文报数**剩余秒数。语音素材（数字 1~10、间隔音、预警音）**从小米安全中心提取**，
以 `MCEEW-yujing-sounds` 资源包形式提供（Release 附件）。

**不装资源包 = 完全无声**，倒计时照常工作；装好后再在 config.yml 开启：

```yaml
Countdown:
  sound:                    # 倒计时声音（可选）
    enable: false           # ← 装好资源包后改为 true
    namespace: "yujing"     # 数字/间隔音事件的命名空间（换资源包改这里）
    tick: "yujing:tick"     # 逐秒中文报数开关：非空启用，留空 = 只播预警音
    tick-delay-seconds: 6.5 # 报数延迟：等预警音播完再开始（秒）
    announce:               # 触发时按烈度播放的预警音（资源包需含 tag0~tag4）
      enable: false
      "1": "yujing:tag0"    # 烈度1
      "3": "yujing:tag2"    # 烈度3-4
      "5": "yujing:tag3"    # 烈度5-6
      "7": "yujing:tag4"    # 烈度7+
    volume: 1.0
    pitch: 1.0
```

**资源包安装（二选一）**：

1. **CraftEngine 服务器**：把资源包作为 CE 包放入 `plugins/CraftEngine/resources/yujing/`
   （`pack.yml` + `configuration/sounds.yml` + `resourcepack/assets/minecraft/sounds/*.ogg`），
   重启后 CE 自动下发；
2. **普通服务器/手动**：把 `MCEEW-yujing-sounds-v1.0.zip` 放进服务器资源包目录或
   玩家 `.minecraft/resourcepacks/` 手动加载（结构为标准原版资源包）。

> 注：资源包的声音事件（`one~ten`/`non`/`di`/`didi`/`tag0~tag4`）由插件用
> `namespace:事件` 播放，命名空间与资源包一致即可，配置里可随时换。

### 新命令（OP）

| 命令 | 说明 |
|---|---|
| `/eew test region <省> [市] [区] <震级> [玩家名]` | 在指定地点模拟地震（如 `/eew test region 贵州省 贵阳市 开阳县 8.0`）。**末尾加玩家名则只发倒计时给该玩家，且跳过全局广播**（如 `... 8.0 Steve`） |
| `/eew test ip <ip>` | 调试在线 IP 定位，显示该 IP 的定位结果（如 `/eew test ip 117.189.5.195`） |
| `/eew test yibin77` | 动态时间的宜宾 M7.7 模拟（保证倒计时为正） |
| `/eew test cenc / cq / sc / ...` | 原版各源测试（全部在线玩家） |

### 定位优先级与工作机制

1. 玩家加入时**异步预取**在线 IP 定位（私有 IP 跳过，不浪费调用）
2. EEW 到达时按 `玩家在线定位缓存 → 离线表 → fallback(在线缓存 → 离线解析)` 取坐标
3. 震源距 = √(水平距离² + 深度²)，S 波到达时间 = 震源距 ÷ 分段速度
4. 烈度按衰减公式估算；本地烈度 ≥ 阈值（默认 3）走强震 Title（每秒刷新），否则走 BossBar

### 注意事项

* 倒计时依赖服务器时钟：`ETA = 发震时间 + 传播时间 − 服务器时钟`，请确保服务器开启 NTP 对时
* 与 CraftEngine 等发包拦截插件共存时，请先实测 Title/BossBar 是否被拦截
* 配置会被 MCEEW 自动规范化重写：改坏 YAML 会被"自动修复"并重置字段，
  改完务必确认 `Countdown.enable` 仍为 `true`
* 烈度衰减采用小米手机地震预警同口径公式（逆向自 MIUI 安全中心 CalcCountdown），播报分级按国家标准；游戏内提示够用，不等于官方正式播报

---

## Features

* Receives JMA, CENC, Sichuan, Fujian, CWA, and Chongqing earthquake warnings
* Receives final earthquake-list reports from JMA and CENC
* Delivers configurable chat and title notifications; Bukkit and Velocity also
  support their configured sound channel
* Supports permission-based delivery and deterministic test alerts
* Provides cached earthquake information and safe configuration reload commands
* Supports standalone Spigot/Paper/Folia servers, Velocity proxies, and a
  BungeeCord build compatible with the final Waterfall release

## Choose the correct artifact

| Environment | Install |
|---|---|
| Standalone Spigot 1.13.2+ | `MCEEW-x.y.z.jar` |
| Standalone Paper 1.13.2+ | `MCEEW-x.y.z.jar` |
| Standalone Folia 1.19.4+ | `MCEEW-x.y.z.jar` |
| Velocity network | `MCEEW-Velocity-x.y.z.jar` in the proxy's `plugins/` directory only |
| BungeeCord network | `MCEEW-BungeeCord-x.y.z.jar` in the proxy's `plugins/` directory only |
| Waterfall final 1.21 release | The same `MCEEW-BungeeCord-x.y.z.jar` |
| Backend behind MCEEW-Velocity | Do not install the Bukkit MCEEW artifact |
| Backend behind MCEEW-BungeeCord | Do not install the Bukkit MCEEW artifact |

`mceew-core` is an internal library and is not a user-installed plugin.

### Important proxy deployment warning

When `MCEEW-Velocity` or `MCEEW-BungeeCord` is installed on a proxy, **do not
install the Bukkit MCEEW plugin on its backend servers**. The proxy plugin
already provides the network's earthquake connection, cache, notifications,
targeting, permission checks, commands, information lookup, and reload support.

Running both artifacts in the same network is unsupported and not recommended.
They are independent plugins: MCEEW does not detect, coordinate, disable, or
deduplicate them. Installing both may create duplicate Wolfx connections,
player notifications, and console output, with separate cache and lifecycle
state. Avoiding this deployment is the administrator's responsibility.

Installing only the Bukkit plugin on an individual backend remains valid
standalone Bukkit behavior, but it does not provide proxy-wide targeting.

## Installation

### Standalone Bukkit-family server

1. Download or build `MCEEW-x.y.z.jar`.
2. Place it in the Spigot, Paper, or Folia server's `plugins/` directory.
3. Start the server and edit the generated Bukkit configuration if needed.

This artifact retains the established Bukkit connection, cache, notifications,
commands, bStats, and updater behavior. No proxy settings are required.

### Velocity network

1. Download or build `MCEEW-Velocity-x.y.z.jar`.
2. Place it in `Velocity/plugins/`.
3. Start Velocity once so `plugins/mceew/config.yml` is created and loaded.
4. Configure notifications and network targeting as needed, then use
   `/eew reload`.
5. Remove the Bukkit MCEEW plugin from backend servers if it is present.

No backend MCEEW plugin is required for targeting, permission checks, chat,
titles, supported sound, commands, cache/info, or reload.

### BungeeCord / Waterfall network

1. Download or build `MCEEW-BungeeCord-x.y.z.jar`.
2. Place it in the proxy's `plugins/` directory.
3. Start the proxy once so `plugins/MCEEW/config.yml` is created and loaded.
4. Configure notifications and network targeting as needed, then use
   `/eew reload`.
5. Remove the Bukkit MCEEW plugin from backend servers if it is present.

MCEEW uses only the public BungeeCord API. The same artifact is compatible with
the final Waterfall 1.21 build 615, but Waterfall itself is archived and
end-of-life. There is no Waterfall-specific artifact or code path.

## Requirements and tested platforms

| Artifact | Plugin bytecode | Tested platform/runtime |
|---|---:|---|
| `MCEEW-x.y.z.jar` | Java 11 (major 55) | Spigot/Paper 1.13.2+; Folia 1.19.4+ |
| `MCEEW-Velocity-x.y.z.jar` | Java 17 (major 61) | Velocity 3.4.x on Java 17+, 3.5.x on Java 21+, and 4.x on Java 25+ |
| `MCEEW-BungeeCord-x.y.z.jar` | Java 11 (major 55) | BungeeCord builds 1999 and 2086; Waterfall 1.21 build 615, each on Java 17 |

The pinned automated compatibility smoke matrix is Velocity 3.4.0 build 566 on
Java 17, 3.5.1 build 615 on Java 21, 4.0.0 build 6 on Java 25, BungeeCord
builds 1999 and 2086 on Java 17, and Waterfall build 615 on Java 17. These
checks prove plugin discovery, lifecycle, disabled-runtime commands, reload,
shutdown, and linkage. They do not replace real-client notification E2E.

Newer Minecraft server releases can require a newer Java runtime than the
Bukkit plugin bytecode itself. Use the Java version required by the server or
proxy when it is higher.

Velocity-delivered sound requires a Minecraft Java client 1.19.3 or newer and
a current backend connection. When sound cannot be delivered, chat and title
notifications continue; only sound is skipped. There is no packet fallback.
BungeeCord and Waterfall support only `broadcast` (chat) and `title`; sound is
not supported because MCEEW's compatibility contract has no acceptable public
BungeeCord API path for it.

## Commands

`/mceew` is an alias of `/eew`.

| Command | Proxy permission | Behavior |
|---|---|---|
| `/eew` | None | Shows the plugin version and available command paths |
| `/eew info jma` | None | Shows the latest locally cached JMA earthquake-list entry |
| `/eew info cenc` | None | Shows the latest locally cached CENC earthquake-list entry |
| `/eew test forecast` | `mceew.admin` | Sends the deterministic JMA forecast test |
| `/eew test alert` | `mceew.admin` | Sends the deterministic JMA alert test |
| `/eew test sc` | `mceew.admin` | Sends the Sichuan test |
| `/eew test fj` | `mceew.admin` | Sends the Fujian/Taiwan test |
| `/eew test cwa` | `mceew.admin` | Sends the Taiwan CWA test |
| `/eew test cenc` | `mceew.admin` | Sends the China CENC test |
| `/eew test cq` | `mceew.admin` | Sends the Chongqing test |
| `/eew reload` | `mceew.admin` | Validates and atomically applies the proxy configuration |

The info commands read the local cache; they do not perform a fresh network
query. Data may be unavailable until the first earthquake-list update arrives.

The test command uses the normal proxy notification, targeting, channel, and
permission path. Its fixed test warning is command feedback sent separately to
the proxy console and connected players. It does not contact Wolfx or modify
the earthquake cache.

On Velocity and BungeeCord/Waterfall, `/eew reload` validates the complete new
configuration before it is committed. An invalid file leaves the working
configuration and runtime active. An enabled-to-enabled reload retains the
existing Wolfx connection and cache; source, notification, and target changes
apply to future data.

## Proxy permissions

### Velocity notification permissions

Velocity player delivery requires both `mceew.notify.all` and the applicable
source permission:

* `mceew.notify.jma.alert`
* `mceew.notify.jma.forecast`
* `mceew.notify.jma.eqlist`
* `mceew.notify.cenc.eqlist`
* `mceew.notify.sc`
* `mceew.notify.fj`
* `mceew.notify.cwa`
* `mceew.notify.cenc.eew`
* `mceew.notify.cq`

All notification permissions default to allowed, so ordinary players receive
notifications without explicit permission grants. Explicitly denying either
`mceew.notify.all` or the applicable source permission opts that player out.

### BungeeCord / Waterfall suppression permissions

BungeeCord and Waterfall use positive suppression permissions because their
generic permission API is boolean. With no suppression permission, a player
receives notifications. A `true` suppression permission opts that player out:

* `mceew.suppress.all`
* `mceew.suppress.jma.alert`
* `mceew.suppress.jma.forecast`
* `mceew.suppress.sc`
* `mceew.suppress.fj`
* `mceew.suppress.cwa`
* `mceew.suppress.cenc.eew`
* `mceew.suppress.cq`
* `mceew.suppress.jma.eqlist`
* `mceew.suppress.cenc.eqlist`

For a source, delivery requires both `mceew.suppress.all` and that source's
suppression node to resolve false. Do not use the Velocity `mceew.notify.*`
nodes on BungeeCord/Waterfall.

MCEEW queries only concrete nodes; the installed permission provider owns
wildcard semantics. Granting `mceew.*` through a wildcard-aware provider may
therefore grant both `mceew.admin` and `mceew.suppress.*`, suppressing that
administrator's notifications. Grant `mceew.admin` directly instead.

`mceew.admin` controls test and reload commands on both proxy editions. It is
default-deny and must be granted explicitly.

Proxy-console notifications are proxy-global. Player target membership, player
permissions, and backend-specific channel overrides do not govern console
delivery.

## Velocity configuration

The Velocity file is `plugins/mceew/config.yml`. Its current top-level schema is:

* `platform_config_version`: Velocity schema version; currently `1`
* `global.enabled`: enables or disables the one proxy-global Wolfx runtime
* `global.sources`: realtime processing switches `enable_jp`, `enable_sc`,
  `enable_fj`, `enable_cwa`, `enable_cenceew`, and `enable_cq`
* `notifications.time_format`: date-time format shared by rendered reports
* `notifications.defaults`: global `broadcast`, `title`, and `alert` delivery
  switches
* `notifications.sources`: source messages, titles, sounds, and optional
  source channel overrides
* `targets`: default and source-specific player-recipient rules
* `groups`: named sets of Velocity backend server names
* `servers`: backend-specific `broadcast`/`title`/`alert` overrides

`global.sources` controls realtime warning processing. JMA and CENC
earthquake-list cache updates remain independent of those realtime switches.
The independent report-delivery switches are
`notifications.sources.jma_eqlist.broadcast` and
`notifications.sources.cenc_eqlist.broadcast`. Disabling either switch stops
that report's broadcast while its local cache and `/eew info` output continue
to update.

With `global.enabled: false`, the operational Wolfx runtime is disabled, but
the plugin shell, `/eew`, `/mceew`, and `/eew reload` remain available. This is
an operational switch, not a proxy/backend coexistence mode.

### Targets

Target modes have these exact meanings:

* `all`: every player currently connected through this Velocity proxy
* `selected`: players currently connected to the explicitly listed backend
  servers or servers expanded from the listed groups
* `none`: no player recipients

A source-specific entry under `targets.sources` completely replaces
`targets.default`; server and group lists are not deep-merged.

Example:

```yaml
targets:
  default:
    mode: selected
    servers: [lobby]
    groups: [games]
  sources:
    jma_alert:
      mode: all

groups:
  games: [survival, creative]
```

Valid source keys are `jma_alert`, `jma_forecast`, `sichuan`, `fujian`, `cwa`,
`cenc_eew`, `chongqing`, `jma_eqlist`, and `cenc_eqlist`. The same keys are
used under `notifications.sources`, `targets.sources`, and server source
overrides.

### Delivery-channel precedence

For the `broadcast` (chat), `title`, and `alert` (sound-enable) switches, the
most specific configured value wins:

```text
server + source > server > source > global
```

Example backend overrides:

```yaml
servers:
  lobby:
    notifications:
      alert: false
    sources:
      jma_alert:
        title: false
```

The nested `sound` object under a notification source contains the Adventure
sound `key`, `volume`, and `pitch`; it is distinct from the boolean `alert`
delivery switch.

`groups` and `servers` describe delivery. They do not create per-backend
connections, caches, parsers, or runtimes: the proxy owns exactly one of each.

## BungeeCord / Waterfall configuration

The BungeeCord file is `plugins/MCEEW/config.yml` and uses
`platform_config_version: 1`. Its main sections are `global`, `notifications`,
`targets`, `groups`, and `servers`. Its canonical source keys are `jma_alert`,
`jma_forecast`, `sichuan`, `fujian`, `cwa`, `cenc_eew`, `chongqing`,
`jma_eqlist`, and `cenc_eqlist`. Its notification channels are only
`broadcast` and `title`; `alert` and `sound` are rejected as unsupported.

Target modes are `all`, `selected`, and `none`. A selected target is the union
of its explicit backend server names and the backend names expanded from its
selected groups. Multiple matching server/group paths are UUID-deduplicated,
so one player receives one notification. A source-specific target completely
replaces the default target; it does not merge with it.

For both Bungee channels, the most specific configured value wins:

```text
server + source > server > source > global
```

Proxy-console broadcast uses the global value followed by the source override.
It is independent of player targeting, suppression permissions, current
backend, groups, and server-specific overrides.

## Configuration lineage

The artifacts use intentionally separate configuration schemas:

* Bukkit: `config-version: 9`
* Velocity: `platform_config_version: 1`
* BungeeCord / Waterfall: `platform_config_version: 1`

These are independent platform schemas. Do not copy one platform's
configuration over another platform's file. Bukkit's deployment behavior
remains standalone and contains no proxy mode.

## Building

Run:

```shell
mvn -B clean package
```

The user-installable outputs are:

```text
mceew-bukkit/target/MCEEW-x.y.z.jar
mceew-velocity/target/MCEEW-Velocity-x.y.z.jar
mceew-bungeecord/target/MCEEW-BungeeCord-x.y.z.jar
```

The root Maven `${revision}` property is the single current-version authority
for module versions, plugin metadata, artifact filenames, and command version
output.

## Downloads

* [SpigotMC](https://www.spigotmc.org/resources/mceew-earthquake-early-warning.104549/)
  — Bukkit-family server artifact
* [Modrinth](https://modrinth.com/plugin/mceew)
  — Bukkit, Velocity, and BungeeCord/Waterfall artifacts; proxy editions must
  be downloaded here

## Screenshots

![1.png](https://s2.loli.net/2024/02/29/IwmO7C4foXhk2ZP.png)
![2.png](https://s2.loli.net/2024/02/29/G9EjJDSUtwyVgMQ.png)
![3.png](https://s2.loli.net/2024/02/29/kUsoMQPlBz98DcW.png)
![4.png](https://s2.loli.net/2024/02/29/ncFAuWD4wEsqIah.png)
![5.png](https://s2.loli.net/2024/04/03/QltcV4RZfe8kwIm.png)
![6.png](https://s2.loli.net/2025/09/13/GNYrfU8JQTP7IdE.png)
![7.png](https://files.seeusercontent.com/2026/08/09/8gkT/2026-08-09_233526.png)
![8.png](https://s2.loli.net/2024/02/29/OSGKuyq9zE8ChTY.png)
![9.png](https://s2.loli.net/2024/02/29/tuXgnVqkrxQoYGJ.png)

## bStats

The standalone Bukkit artifact uses bStats project 17261:
[![bStats](https://bstats.org/signatures/bukkit/MCEEW.svg)](https://bstats.org/plugin/bukkit/MCEEW/17261)

The Velocity artifact uses its separately registered bStats project 33363:
[![bStats](https://bstats.org/signatures/velocity/MCEEW.svg)](https://bstats.org/plugin/velocity/MCEEW/33363)

The BungeeCord/Waterfall artifact uses its separately registered bStats project 33371:
[![bStats](https://bstats.org/signatures/bungeecord/MCEEW.svg)](https://bstats.org/plugin/bungeecord/MCEEW/33371)
