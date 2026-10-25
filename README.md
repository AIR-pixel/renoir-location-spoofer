# LocSpoof — Android 定位伪造模块

面向 **Xiaomi 11 Lite 5G (renoir / M2101K9C) + 澎湃OS 4 移植包 (Android 17)** 编写，
但 hook 逻辑按 SDK_INT 与类存在性自适应，未绑定具体 ROM。

---

## 0. 目标设备的实测状态（2026-10-23，已跑通）

| 项 | 实测值 | 含义 |
|---|---|---|
| `ro.product.device` | `renoir` | 小米 11 Lite 5G，骁龙 780G |
| `ro.build.version.release` | `17` / SDK **37** | framework 确实是 Android 17 |
| `ro.mi.os.version.incremental` | `OS4.0.0.9.XPCCNXM \| by wannqn` | **第三方移植包**，非官方 |
| `ro.vendor.build.version.sdk` | `30` | vendor 层停在 Android 11 |
| `/proc/version` | `5.4.268-reUNST (wannq@DESKTOP-...)` | 作者自编内核，非 GKI |
| bootloader | **真解锁**（fastboot 实测 `Device unlocked: true`） | 见下方「prop 全是假的」 |
| root | **Magisk 30.7，su 可用** | boot.img 离线 patch 后 `flash boot_ab` |
| 框架 | ROM 自带 LSPosed v2.1.1 (7790) + Zygisk Next | 无需另装 |
| 定位 hook | **已生效**（App 侧改写 getter，见第 0.5 节） | 系统框架作用域已移除 |

### 0.5 架构变更：不挂 system_server 了（重要）

最初的设计是勾上「系统框架」，在 `Location.writeToParcel` 上做全局替换。**这条路在这台机器上必须放弃**，
因为它会稳定地把整机拖进重启循环：

```
sys.runtime.reboot.reason.last = ne#zygote64#...#unhandled
```

zygote 一死，init 就做框架级 runtime restart —— 用户看到的就是「手机自己重启」。
根因见第 9 节（A17 的 zygote fd 校验碰到 FIFO 直接 abort），**只要 system_server 因任何原因重开就会踩中**，
而我们的 writeToParcel hook 恰好会让 system_server 变得不稳定（高频路径上改共享 Location 对象）。

现在的架构：

| 位置 | 做什么 | 是否需要勾「系统框架」 |
|---|---|---|
| 目标 App 进程（`AppHooks`） | 改写 `Location` 全部 getter、`getLastKnownLocation`、伪造 `GnssStatus`、屏蔽 WiFi/基站 | 需要（勾目标 App） |
| `com.air.locspoof` 自身 | 同上，让「验证定位」按钮能自检 | 需要 |
| system_server（`SystemHooks`） | 全局 `writeToParcel` 替换 | **不勾，保持为空** |

坐标怎么给到 App 进程：`/data/system/locspoof.xml` 只有 system_server 读得到（SELinux category 隔离），
所以坐标改由 `settings put secure locspoof_on_lat/lon/acc/...` 中转，App 进程直接读 `Settings.Secure`。

代价与收益：只对作用域内的 App 生效，不再全系统伪造；换来的是**系统不再重启**。
对「让校园跑认错位置」这个目标来说，代价为零。


### prop 全是假的

系统里 `ro.boot.vbmeta.device_state=locked`、`verifiedbootstate=green`、`veritymode=enforcing`
三个值**全部是移植包作者伪造的**。进 fastboot 实测才是真的：

```
fastboot getvar unlocked        →  unlocked: yes
```

教训：第三方 ROM 上 `ro.boot.*` 一律不可信，涉及 bootloader 状态的判断只能进 bootloader 看。
我最初就是读了这几个 prop 就断言「已锁、要重新解锁清数据」，判断完全反了。

### KernelSU 走不通（已放弃）

现代 KSU（含 v3.x）只支持 **GKI 内核 5.10+**。renoir 是 5.4，且内核已是作者自编的
`-reUNST`，没有源码就无从下手。`me.weishu.kernelsu` 这个 app 装了也没用，可以卸。

### 实际走的路（已完成）

```bash
# 1. 从移植包 zip 里取 boot.img（201326592 字节，header v3）
# 2. Magisk app → 安装 → 选择并修补 boot.img（ramdisk +304160 字节）
# 3. 双槽都写，避免切槽丢 root
fastboot flash boot_ab magisk_patched_xxx.img
# 4. 重启，su 可用 → 装本模块 → LSPosed 启用 → 勾选目标 App（不要勾系统框架）→ 重启
```

renoir 是 A/B 分区、5.4 内核、**没有 init_boot**，patch 的是 `boot`。

---

## 1. 原理与 hook 点（已在 A17 上跑通并验证）

定位数据五层，本模块覆盖前三层：

```
App 进程      LocationManager / Location 对象
   ↓
system_server LocationManagerService（A17 已重构为壳）
   ↓
Provider      GNSS / Network / Fused
   ↓
GNSS HAL      android.hardware.gnss@2.1-impl-qti.so
   ↓
驱动 / 芯片
```

**核心思路：不追着分发点跑，卡在 `Location.writeToParcel()`。**

任何 `Location` 跨进程传输都必须经过 `writeToParcel`。在它的 `beforeHookedMethod`
里用 setter 改写字段，系统内部与所有 App 拿到的一律是假坐标，
与 Android 版本怎么重构内部分发完全无关。

> 为什么不用 `Receiver.callLocationChangedLocked`：
> A17 的 `LocationManagerService` 内部类只剩 `LocalService / SystemInjector / Lifecycle`，
> **`$Receiver` 已不存在**；`getLastLocation` 也从 2 参变 4 参。
> 这条老路在 A14 之后就已经在腐烂，追它只会被版本变化反复打脸。

关键 hook：

| 目标 | 位置 | 作用 |
|---|---|---|
| **`Location.writeToParcel(Parcel, int)`** | system_server | **核心**。序列化前改写 lat/lon/acc/alt/speed/bearing |
| `LocationManagerService.getLastLocation` | system_server | 按返回类型 `Location` 匹配（不限定参数个数） |
| `addGnssMeasurementsListener` 等 5 个 | system_server | 注册返回 false，断 raw GNSS 旁路 |
| `Location.isMock()` / `isFromMockProvider()` | 目标进程 | 抹 mock 标记 |
| `Settings.Secure.getString(_, "mock_location")` | 目标进程 | 抹全局设置 |

被屏蔽的 5 个 GNSS 方法（A17 实测命中）：

```
addGnssAntennaInfoListener
addGnssMeasurementsListener
addGnssNavigationMessageListener
registerGnssNmeaCallback        ← 最容易漏：App 可从这里读原始 $GPGGA
registerGnssStatusCallback
```

**三个必须知道的坑**

1. **改 getter 没用**。Binder 序列化读的是字段，不是 getter 返回值。
   hook `getLatitude()` 只影响本进程内的读取，跨进程传出去的还是原值。
   必须改**对象字段**（setter）。
2. **`elapsedRealtimeNanos` 为 0 会被丢弃**，伪造 Location 时时间字段要给真实值。
3. **`Prefs` 的读取位置决定成败**，见下一节。

### 注入的 Location 必须自洽，否则 SDK 会直接丢弃

2026-10-23 实测踩出来的：只在 `writeToParcel` 里改经纬度是不够的，改造出来的对象
会带着**原始 provider 和原始 extras**，于是出现物理上不可能的组合：

```
Location[network 32.210530,114.471221 hAcc=5.0 ...]   ← network 定位给 5m 精度？
```

网络定位现实精度是 30m~2km，`network + 5m` 这种组合会被高德/百度这类 SDK 判为脏数据
直接扔掉，然后回落到自研 WiFi 指纹定位 —— 用户看到的现象就是
**「我设了 A，App 显示 B，还提示 GPS 弱信号」**。

所以 `writeToParcel` 里必须同时把元数据改成自洽的 GPS fix：

```kotlin
l.provider = LocationManager.GPS_PROVIDER          // 精度 5m 必须配 gps
l.extras = Bundle(l.extras ?: Bundle()).apply {    // 信号格读这里
    putInt("satellites", 12)
    putInt("maxCn0", 42)
    putInt("meanCn0", 34)
}
```

实测后的对象：

```
Location[gps 32.210530,114.471221 hAcc=5.0 alt=45.0 vel=0.0
         {satellites=12, maxCn0=42, meanCn0=34, ...}]
```

### `registerGnssStatusCallback` 千万别挡

最初为了「断 raw GNSS 旁路」，把这一条也一起返回 false 了。这是个自伤操作：

- `GnssStatus` 只给卫星编号、方位角、仰角、信噪比，**不含设备位置**，挡了没有任何防作弊收益；
- 挡掉之后全系统 App 拿到 0 颗卫星，`dumpsys location` 里 `Status Provider: service: unregistered`；
- 所有 App 的 UI 都会显示 **GPS 弱信号 / 无信号**，跑步打卡类 App 甚至据此判定定位不可用。

现在只挡真正危险的几条：`GnssMeasurements`（载波相位/多普勒能反推位置）、
`GnssNavigationMessage`、`GnssBatching`、NMEA。

### 但屏蔽 raw GNSS 不能一视同仁（2026-10-23 踩坑）

只挡上面那几条还不够安全 —— **必须按调用者 uid 过滤，系统组件一律放行**：

```kotlin
val uid = Binder.getCallingUid()
if (uid < Process.FIRST_APPLICATION_UID) return   // 系统/厂商进程放行
```

不这么做的后果：高通的 `com.qualcomm.location`（IZat，uid 1000）在初始化 ES 状态接收器时
会注册 GNSS 观测回调，拿到 `false` 之后内部 `gnssService` 为 null，抛
`RuntimeException: gnssService is null!`，然后那个线程反复重启 —— 实测一分钟 105 次，
连带把 Location 服务和 system_server 拖进重启风暴。

改成 uid 过滤后，第三方 App 的防作弊能力完整保留，系统组件不再被误伤。

### 光挡不够，弱信号要「伪造」成强信号

`GnssStatus` 不含位置，但它决定两件事：App 的信号格，以及高德/百度这类 SDK 对 GPS 的**权重**。
室内实测只有 2~4 颗星，SDK 就会把 GPS 降权、让权重回到自研 WiFi 定位，结果坐标又跳回真实位置。

所以要在目标 App 进程里把 `GnssStatus` 整形成强信号（`AppHooks.fakeGnssStatus`）：
卫星数 12、PRN 用真实星座号（3/7/8/10/15/16/18/21/23/26/27/29）、信噪比 38~40、全部 `usedInFix`。
卫星数必须和 `Location.extras` 里的 `satellites` 一致，两处对不上反而更像假数据。

同理，`Location.time` 和 `elapsedRealtimeNanos` 也要刷成「现在」——
SDK 拿到时间戳落后于当前时钟的 fix 会当陈旧数据丢掉。

验证是否被采信，看 `dumpsys location` 里 Location 的 extras：
出现 `amap=1, amap_location_resultCode=0` 就是高德 SDK 自己回写的「定位成功」。

### 目标 App 必须在模块作用域里

系统层 `writeToParcel` 只管**走 LocationManager 的那条路**。高德/百度 SDK 还有两条
并行通道：WiFi BSSID 指纹、基站小区号，都直接发给自家服务器反查经纬度，
完全不碰 LocationManager。

这两条只能在目标 App 进程里挡（`AppHooks.blockWifiScan` / `blockCellInfo`），
所以**目标 App 必须被勾进 LSPosed 作用域**，否则系统层改得再好也会被 SDK 的自研定位覆盖。

判断有没有生效，看模块日志：

```bash
su -c "ls -t /data/adb/lspd/log/modules_*.log | head -1"
# 有 [LocSpoof] initZygote + blocked wifi scan 才算注入了
```

---

## 2. 配置为什么必须放 `/data/system`

这是本项目踩得最久的一个坑。

最初把配置放在 `/data/data/com.air.locspoof/shared_prefs/locspoof.xml`，
`chmod 644` 之后 `su` 能读，但 **system_server 永远读到 null**，
表现为「hook 全部显示 hooked，但坐标一动不动」。

根因：Android 10+ 给每个 App 的私有目录打了 SELinux **category**
（如 `u:object_r:app_data_file:s0:c83,c257,c512,c768`），
而 system_server 的域是 `u:r:system_server:s0`（无 category），
跨 category 访问会被拒，**且这类拒绝常常不刷 avc 日志**，所以查 dmesg 也看不出来。

正解：配置放 `/data/system/locspoof.xml`，并

```bash
chmod 644 /data/system/locspoof.xml
chown system:system /data/system/locspoof.xml
restorecon /data/system/locspoof.xml     # → u:object_r:system_data_file:s0
```

App 无法直接写 `/data/system`，所以点「写入配置」时通过 `su` 执行上述复制 + 改属性。
**这一步需要授予 root 权限**；如果 App 里提示同步失败，用 root shell 手动执行一次即可。

### 更隐蔽的一层：Android 17 把 SharedPreferences 搬走了

原本的实现是「App 正常写自己的 prefs，写完 `cp` 到 `/data/system`」。
在**这台 A17 移植包上，这个 `cp` 拷的是永远不会更新的旧文件**：

```
/data/data/com.air.locspoof/shared_prefs/locspoof.xml      ← 13:23，遗留物，再也没动过
/data/misc/apexdata/<uuid>/prefs/com.air.locspoof/locspoof.xml   ← 真正在写的那份
```

A17 把 App 的 SharedPreferences 落盘位置挪到了 `/data/misc/apexdata/<uuid>/prefs/<pkg>/`，
`/data/data/<pkg>/shared_prefs/` 下的旧文件作为遗留物留在原地。

后果非常恶心：界面显示上海、点「写入配置」、`cp` 成功、`chmod/chown` 成功、退出码 0、
toast 提示「已生效」——**但写进去的是北京**。因为 `cp` 的源文件从头到尾没变过。
判断依据只有一条：`/data/system/locspoof.xml` 的 mtime 变了但内容没变。

正解：**不要从 prefs 文件路径拷**。App 自己序列化 XML 写到 `cacheDir`，
再 `su cp` 过去（`Prefs.pushToSystem`）。这样与 prefs 存在哪无关。

```kotlin
val tmp = File(ctx.cacheDir, "locspoof.push.xml")
tmp.writeText(render(cfg, enabled))   // 自己拼 SharedPreferences 同格式的 xml
// su -c "cp $tmp /data/system/locspoof.xml && chmod 644 ... && chown system:system ... && restorecon ..."
```

排查这类问题的通用手法：`find /data -name "<prefs名>.xml"`，一条就出真相。

读取侧加了 500ms 缓存：`writeToParcel` 在定位更新时每秒可能被调几十次，
每次读文件会有明显的 IO 抖动。

---

## 3. 开关与「恢复真实定位」

### 为什么一个开关要写两个地方

伪造分两段，分别住在两个进程里，**它们能读到的通道不一样**：

| 段 | 跑在 | 读什么 | 为什么不能读另一处 |
|---|---|---|---|
| `writeToParcel` 改写 | system_server | `/data/system/locspoof.xml` | —— |
| 抹 mock 标记、屏蔽 WiFi 扫描 | 目标 App 进程 | `Settings.Secure.locspoof_on` | 目标 App 读不到 `/data/system`（SELinux category 拦） |

所以「关掉伪造」必须同时写两处，**少写一处就是半残**：
系统坐标已经是真的，但目标 App 还在屏蔽 WiFi 扫描、还在抹 mock 标记。

```
/data/system/locspoof.xml        enabled=false   → system_server 放行真实坐标
Settings.Secure.locspoof_on = 0                 → 目标 App 进程撤掉屏蔽
```

Settings.Secure 用 `su settings put secure ...` 写（root 下无权限问题）。

### 关掉是即时的，不用重启

`writeToParcel` 每次调用都重新判断配置（500ms 缓存），App 侧 hook 每次回调都读一次
`Settings.Secure`（1s 缓存）。所以：

- **不需要重启设备**
- **不需要 force-stop 目标 App**
- **不需要卸载或关掉 LSPosed 模块**

按一下「恢复真实定位」，最多 1 秒后全链路就是真实定位。

### 实测（本机，2026-10-23）

```
开  → verify[新鲜] 32.210530,114.471221 acc=5.0   ← 配置值
关  → verify[新鲜] 32.212950,114.471017 acc=30.0  ← 真实值（精度 30m 是网络定位的正常水平）
再开 → verify[新鲜] 32.210530,114.471221 acc=5.0   ← 回到配置值
```

判据：**看精度**。配置里写的是 5.0m，系统真实网络定位给的是 30m 量级，
一眼就能分出哪个是伪造、哪个是真的。

### 一个容易误判的细节

刚关掉的 1~2 秒内，`getLastKnownLocation` 可能还会回一次旧的伪造值——
那是系统在给新注册的监听器补发「最后一次已知位置」，用的是关掉之前缓存的对象。
等一下再验证就是真实值了，不是没生效。

---

## 4. 怎么确认 hook 真的生效

### 最快：点 App 里的「验证定位」

它会真的向系统要一次定位，把回包原样打在按钮下面。实测输出（本机，配置为广州）：

```
回包[新鲜] 23.129100, 113.264400
精度 5.0m   mock=否   ✓ 与配置一致
```

`mock=否` 说明 AppHooks 抹标记也生效了。点一次会先亮出系统缓存值
（`[缓存-network]`，这一条已足够证明 hook 生效，因为 Location 跨进程必走 `writeToParcel`），
再等一次新定位覆盖成 `[新鲜]`；8 秒没等到就补一句「上面是系统缓存值」。

对应日志（`LocSpoofUI`）：

```
verify[缓存-network] 23.1291,113.2644 -> 回包[缓存-network] ... ✓ 与配置一致
verify[新鲜] 23.1291,113.2644 acc=5.0 mock=false
```

### 不要看地图 App

高德、几何天气都有自研的 WiFi / 基站指纹定位，
压根不向系统 Location 请求（`dumpsys location` 的 `listeners` 一直是空的），
它们显示真实位置不代表 hook 失败。

### 命令行验证：系统 test provider

判据明确，可以脱离本 App 独立复核：

```bash
su -c "cmd location providers add-test-provider test --requiresNetwork"
su -c "cmd location providers set-test-provider-enabled test true"
su -c "cmd location providers set-test-provider-location test --location 22.5,114.0 --accuracy 10"
dumpsys location | grep -A3 "test provider \[mock\]"
```

注入 `22.5,114.0` 后应当读出配置里的坐标：

```
last location=Location[test 39.908722,116.397499 hAcc=5.0 alt=45.0 vel=0.0 bear=0.0]
```

同时模块日志里会出现：

```
LocSpoof/Sys writeToParcel: injected 39.908722,116.397499 #0
```

日志位置：`/data/adb/lspd/log/modules_<时间戳>.log`（Xposed 日志**不写 logcat**）。

验证完记得清掉测试 provider：

```bash
su -c "cmd location providers remove-test-provider test"
```

---

## 5. 跨进程配置的实现细节

system_server 拿不到本 App 的 Context（`getSystemContext()` 指向 `/data/system`）；
`createPackageContext(..., CONTEXT_IGNORE_SECURITY)` 虽绕过权限校验，但 SELinux
category 这层拦不住，照样失败。

所以最终方案是：**App 侧自己序列化 xml → 写到 `cacheDir` → `su` 搬到 `/data/system/`；
hook 侧按绝对路径直接解析那个 xml**，完全绕开 Context、SharedPreferences 缓存、
prefs 落盘位置的版本差异和 SELinux 隔离。

本地 SharedPreferences 只用来记住界面状态（上次坐标、收藏），不参与生效链路。

---

## 6. 编译

```bash
cd renoir-location-spoofer
gradle assembleDebug -Dorg.gradle.java.home="<你的 JDK 17+ 路径>"
```

- JDK 17+（Gradle 8.11 / AGP 8.7 要求）。`gradle.properties` 里注释了 `org.gradle.java.home`，
  按本机路径改，或在命令行传
- `compileSdk = 37`。本机只有 `platforms/android-37.0`（带 `.0`），已复制成 `android-37`
  并改掉 `source.properties` 里的 `AndroidVersion.ApiLevel`——AGP 只认整数 `37`。
  只有 `android-35` 的话把 `compileSdk` 降到 35 也能编
- 装不上时（MIUI 拦 adb install）：`adb push` 到 `/data/local/tmp` + `su -c 'pm install -r -d ...'`

Kotlin 编译守护进程偶尔报 `daemon has terminated unexpectedly`，不影响产物，构建仍成功。

---

## 7. 激活（root 路线）

1. 刷 **Vector**（`github.com/JingMatrix/Vector`），不是 LSPosed。
   原版 LSPosed 停更于 2023-10，README 只写到 Android 14；Vector v2.0 支持到 17 Beta。
2. Magisk 里启用 Zygisk，重启。
3. 装本模块 APK，在 Vector 里启用。
4. **作用域只勾目标 App**（外加模块自己 `com.air.locspoof`，界面上的「验证定位」靠它自检）。
   **不要勾「系统框架」/ `android` / `com.android.phone`** —— 当前架构走 App 进程内改 getter，
   根本不挂 system_server，勾上系统框架反而会在 A17 上把整机拖进 runtime restart 循环（见 0.5 节）。
   模块的默认作用域已改成只含自身。
5. 重启（作用域含 system_server 时必须重启，force-stop 不够）。
6. 打开本 App 写入坐标 → 点「自检」确认 su 可用、配置可读。
7. 看 Vector 日志里的 `[LocSpoof/Sys]` 确认 hook 装上了。

---

### 界面用法

App 打开就是地图（高德瓦片，不需要 key）+ 控制面板：

- **点地图选点** —— 自动把 GCJ-02 换算成 WGS-84 填进输入框，不用自己算
- **快捷城市** —— 北京/上海/广州/深圳/杭州/成都/武汉/西安/南京/重庆，一键跳转。
  **不依赖网络**，地图加载不出来时用这个（实测本机 WiFi 断开时地图必然是灰的，
  但定位本身完全正常，因为 hook 全在本地）
- **右上开关** —— 一键启用/停用，切换即写盘并同步，不用点保存
- **输入框手填** —— 如果你的坐标来自高德/腾讯地图，勾上「输入是GCJ-02」，保存时自动反解
- **收藏** —— 存 6 个常用点，点一下直接切换；长按删除
- **粘贴坐标** —— 从剪贴板里抓一对小数贴进来
- **验证定位** —— 向系统要一次坐标并把回包显示出来，附「与配置一致/不一致」判据
- **恢复真实定位** —— 一键关掉伪造，回到真实 GPS/网络定位，并自动验证给你看
- **底部诊断** —— 实时显示 root 状态、配置是否已同步到 `/data/system`、LSPosed 是否在位

**关于地图**：瓦片来自 `webrd01.is.autonavi.com`，**必须联网**。如果地图一直是灰色网格，
先确认设备真的连上了 WiFi——排查命令：

```bash
adb shell ping -c 1 www.baidu.com          # 整体网络
adb shell dumpsys wifi | grep mWifiInfo     # 看 Supplicant state 是否 DISCONNECTED
```

启动时会用一次直连请求自检网络，结果写在 logcat（tag `LocSpoofUI`）：

```bash
adb logcat -d | grep LocSpoofUI
```

坐标系这件事必须说清楚：高德/腾讯地图上看到的经纬度是 **GCJ-02（火星坐标）**，
Android 的 Location 是 **WGS-84**，中国境内两者差几百米。App 内部一律存 WGS-84，
地图显示时转 GCJ、点击时转回 WGS，所以你在图上点哪里，定位结果就在哪里。

另外启动时会**优先读 `/data/system/locspoof.xml`**（即真正生效的值）而不是本地副本，
避免出现「界面显示 A、实际生效 B」的错位。

---

## 8. 已知缺口（未实现，按需补）

- **NMEA 目前是「屏蔽」而不是「伪造」**：`registerGnssNmeaCallback` 被挡掉，
  App 读不到原始 `$GPGGA`（读不到 > 读到真实值），但读不到本身也是一种异常特征。
  更彻底的做法是 hook 回调做正则替换，喂一份与坐标自洽的 NMEA。
- **WiFi 指纹：已做 App 侧屏蔽（不是伪造）**。`WifiManager.getScanResults()` 在作用域内的
  App 里一律返回空列表——高德/几何天气/百度这类 SDK 拿不到 BSSID 指纹后，会回落到系统 GPS，
  而 GPS 已被 `writeToParcel` 改成假坐标。**关掉伪造后这层会自动放行真实结果**
  （读 `Settings.Secure.locspoof_on`，见第 3 节）。
  补充：system_server 层的 `com.android.server.wifi.WifiServiceImpl` 摸不到
  （A11+ WiFi 是 APEX 模块，独立 classloader，实测日志 `WifiServiceImpl missing`），
  所以只能从 App 侧下手。
  **但这只是「屏蔽」不是「伪造」**：如果目标把「一个 AP 都扫不到」本身当异常，仍可能被识别。
  更自然要伪造目标点附近的真实 BSSID（WiGLE / OpenCellID 有公开数据）。
- **基站（CellInfo）伪造**：现已做 App 侧**屏蔽**（`TelephonyManager.getAllCellInfo` →
  空列表、`getCellLocation` → null），同样受开关控制。WiFi 指纹被挡后 SDK 的下一顺位就是
  基站反查，不挡的话照样能算出真实位置。仍是「屏蔽」不是「伪造」。
- **传感器一致性**：加速度计/陀螺仪静默而坐标在动 = 最硬的物理矛盾。
  要么同步 hook `SensorManager`，要么用低速度 + OU 过程抖动。
- **IP 地理**：服务器侧比对，App 内 hook 无效，只能靠代理出口对齐。

**结论**：走系统 `LocationManager` 的 App 全部生效（含 GMS FusedLocation 的原料）。
靠 WiFi 指纹自研定位的 App，在扫描被屏蔽后通常会回落到系统 GPS，也就是拿到假坐标；
但这属于「可能被判定为异常」的方案，不是完美的伪造。
要做到真正自然，需要伪造目标点附近的真实 BSSID 指纹，那是另一个量级的工作。

---

## 9. 掉进 fastboot 怎么救（2026-10-23 实测）

这台机器（RENOIR，A17 移植包）第一次装完模块重启时曾经停在 fastboot，
`fastboot reboot` 返回 OKAY 却几秒后又回到 fastboot。**系统本身没坏**，是槽位被标记了：

```
fastboot getvar all | grep slot-
slot-unbootable:a: yes      slot-retry-count:a: 0      slot-successful:a: no
```

ABL 每启动失败一次扣一次重试计数，扣到 0 就把槽位标成不可引导，之后连尝试都不尝试。
恢复只要两条命令，别的都不用做：

```
fastboot --set-active=a     # 重置 retry-count=7、unbootable=no
fastboot reboot
```

计数被烧光的底层原因（`/data/tombstones/` 里的原生栈）：

```
ZygoteInit.forkSystemServer
  → ForkCommon
  → FileDescriptorTable::Create
  → FileDescriptorInfo::CreateFromFd   ← JNI FatalError: Unsupported st_mode for FD 5: FIFO
```

zygote 在 fork system_server 前要枚举并校验继承下来的 fd，A17 的校验比 A14 严，
碰到 FIFO 就直接 abort。这是 Zygisk Next + LSPosed 在这套移植包上的老问题，
**崩在 fork 阶段，模块的 Java 代码还没开始执行**，改模块治不了。
表现为：system_server 起不来 → 反复失败 → 计数烧光 → fastboot。

顺带一个必须记住的顺序问题：**装完模块 APK 后的第一次重启，system_server 里未必加载到模块**
（日志里没有 `(system)` 开头的 `LocSpoof` 行，`dumpsys location` 的 last location 是 `null`）。
再重启一次就正常。

---

## 10. 合规边界

自用测试、隐私保护、开发调试没问题。用于伪造考勤、运动记录、里程、
骗取平台补贴属于伪造记录，责任在操作者。部分 App 的 ToS 会封号。

---

## 11. 目标 App 是加固包（2026-10-23 实测，决定了「能不能改包」）

导出 `base.apk`（330MB）后 `dexdump -f` 读 header：

```
file_size       : 71061232      # 单个 dex 71MB
string_ids_size : 1061
class_defs_size : 30            # 只有 30 个类
data_size       : 50244         # 真实 dex 只有 50KB
```

`lib/arm64-v8a/libijiami_1011.so`、`assets/ijiami_1011.VData`、`libzxprotect.so`
—— **爱加密 + zxprotect 加固**。那 71MB 是运行时才解密的加密载荷。

### 结论一：不能重打包，也就不能「改到别的设备上用」
- LSPatch 内嵌模块需要改写 dex 并重签名，加固包会直接拒绝启动（壳会校验 dex/签名完整性）。
- 想绕过就得先脱壳（dump 运行时内存 dex → 修复 → 重打包 → 过签名校验），
  这是独立的大工程，且每次 App 更新都要重做一遍。
- **跨设备的正确做法是同样的 root + LSPosed + 本模块**，而不是改 APK。

### 结论二：广告 SDK 类在 hook 安装时刻不存在
`XposedHelpers.findClass("com.bytedance.sdk.openadsdk.TTAdSdk")` 返回 null，
因为 hook 装在 Application 创建之前，那时只有壳的 30 个类。日志会打 `no ad sdk found`。

反过来说，**定位 hook 一直有效的原因**就在这里：`Location`、`GnssStatus`、`WifiManager`
都是 boot classpath 上的系统类，任何时刻都可见，不受加固影响。

### 结论三：DNS 层挡广告只挡一半
把 `InetAddress.getAllByName` 命中广告域名时抛 `UnknownHostException`，
实测确实命中并在日志里留下 `blocked ad dns: api-access.pangolin-sdk-toutiao.com`、
`blocked ad dns: v2mi.gdt.qq.com`，**但界面上的广告没消失**：
素材可能已缓存（清过 cache 仍在）、或从 CDN 域/直连 IP 取、或走的是别家联盟。

**「去广告只能脱壳改包」这个结论后来被推翻了。** 不碰 dex 也能做到 —— 广告最终要变成
屏幕上的 View，而 View 的资源 id 属于哪个包、实现类叫什么，加固壳都改不了。
这部分已拆成独立仓库：**`android-adcleaner`**（与本项目同一作者，README 里有完整方法论
与实测广告位清单）。本仓库不再包含去广告代码。

---

## 12. 跨设备部署：任何有 root 的设备（含 VMOS / Android 9）

模块**不绑定任何机型**：全部 hook 点都是 boot classpath 上的系统类
（`Location` / `LocationManager` / `GnssStatus` / `WifiManager` / `TelephonyManager` / `Settings.Secure`），
且一律「按方法名反射扫描 + 找不到就跳过」，不写死版本号。
换设备只需三件事：root + Xposed 框架 + 本模块。

### 12.1 版本兼容矩阵（已按 SDK `api-versions.xml` 逐条核对）

| 项 | 引入版本 | 本模块怎么处理 |
|---|---|---|
| `Location.setExtras(Bundle)` | **API 1** | 直接调用，A9 上安全 |
| `Location.getExtras()` | API 1 | hook |
| `Location.isFromMockProvider()` | API 18 | hook |
| `Location.isMock()` / `setMock()` | **API 31** | 反射按名扫描，A9 上不存在 → 自动跳过 |
| `GnssStatus.getBasebandCn0DbHz()` 等 | API 30 | 同上，自动跳过 |
| `LocationManager.getLastKnownLocation(String)` | API 1 | 公开重载只有 1 参，现有过滤条件正确 |
| `Settings.Secure.getInt(cr,key,def)` | API 1 | 3 参重载也 hook，见 12.4 |

`minSdk` 已从 30 降到 **28**（Android 9）。降 minSdk 不影响向上兼容，A17 照常。
产物是纯 dex、**不含 native 库**（`aapt2 dump badging` 无 `native-code` 条目），x86_64 / arm64 都能装。

### 12.2 框架与平台选型

VMOS Pro（3.1.8）自带 root 与 Xposed，两条路：虚拟机工具箱里直接激活内置 Xposed（省事），
或导入 Magisk APK 触发自动 root、再刷 `LSPosed-v1.9.2-7024-zygisk-release.zip`（可控）。

框架选型与各平台详细操作见 **第 13 节**（2026-10 已更新为三分天下的现状）。

### 12.3 VMOS 的三个坑（已处理两个，第三个要手动）

1. **`su` 不是标准 Magisk su**。VMOS Pro（3.1.8）自带 root 开关，是官方的「虚拟 root」，
   不是 Magisk 体系，`restorecon` 很可能不存在。
   已处理：`Prefs.pushToSystem()` 把 `restorecon` 拆成独立命令，失败只警告、不算失败。
   注意 VMOS Pro 官方已支持自带 Xposed，**不必再导 Magisk APK**（那是老版本的绕法）。
2. **「明明生效却报失败」**。当前架构只勾目标 App，不依赖 `/data/system/locspoof.xml`，
   但根目录写不进去时旧逻辑会整体报失败。已处理：`MainActivity.push()` 的成功判定只看
   App 侧通道（`Settings.Secure` 的 flag + 坐标），`/data/system` 同步失败降级成警告文案。
3. **VMOS 自带的位置模拟会叠加**。它走系统 test provider 机制，本模块在 App 进程内改 getter，
   两者不冲突但会互相盖：VMOS 先改系统定位 → 本模块再把 getter 改成目标坐标 → 最终以本模块为准。
   排查时容易分不清是谁在起作用，建议把 VMOS 的位置模拟关掉。

### 12.4 `mock_location` 痕迹的覆盖范围

`Settings.Secure` 的 `mock_location` 是最廉价的检测面，而同一个 key 有多条读取路径：

```
getInt(cr, "mock_location")                         2 参
getInt(cr, "mock_location", 0)                      3 参（带默认值，最常见）
getStringForUser(cr, "mock_location", uid)          3 参
NameValueCache.getStringForUser(cr, tag, name, uid) 4 参（内部汇聚点）
```

只挡 2 参那条，其余都能读到真值 1 —— 而 `getInt(cr, key, 0)` 恰恰是检测 SDK 最爱用的写法。
现在按方法名反射覆盖以上全部路径，并额外 hook `Settings$NameValueCache`
（所有 `Settings.get*` 的内部汇聚点，private static 嵌套类，类名照样反射得到）。
key 在参数里的位置各版本不同（2 参时在 index 1，NameValueCache 里在 index 2），
所以不按固定下标取，而是扫所有 String 参数找 `mock_location`。

### 12.5 加固 App 在虚拟机上的额外风险

目标 App 是爱加密 + zxprotect 加固包（见第 11 节）。加固壳除了校验 dex/签名，
还会检测运行环境（模拟器/虚拟化特征）——**VMOS 这类虚拟化容器更容易触发风控**。
本模块解决不了这一层：hook 装在壳解密真实 dex 之前，对系统类的 hook 不受影响，
但壳自身的环境检测走另一套逻辑，只能靠目标 App 自己的容忍度。

### 12.6 实测状态

⚠️ **VMOS + A9 这条路目前只做了代码级与平台机制层面的核对，没有实机验证**：

- `minSdk 28` 的产物已编译通过并验过 badging（`minSdkVersion:'28'` / `targetSdkVersion:'35'`）；
- 所有 API 引入版本已按 SDK `api-versions.xml` 逐条核对，无 A9 不可用的直接调用；
- 框架三分天下的现状与 VMOS Pro 的操作路径是 2026-10 检索确认的（含版本号与 ROM 兼容性），
  但**设备侧没有真机跑过**：`adb devices` 为空，VMOS 的激活也不走常规 `adb connect 5555`。

要实测得在 VMOS 虚拟机内直接操作（或用它的共享目录导日志），装模块、勾作用域、
写坐标、验证四步可以各跑一遍出对比数据。

---

## 13. 跨平台直接使用：操作手册

### 13.1 一句话

**任何能拿到 root 的 Android 环境都能用**：拿 root → 装框架 → 装本模块 → 勾目标 App → 写坐标。
模块是纯 dex、无 native 库、`minSdk 28`，架构无关，不绑定机型。

### 13.2 框架选型（2026-10 现状，三分天下）

| 框架 | 最后版本 | 源码 | Android | Root 要求 | 加载本模块 |
|---|---|---|---|---|---|
| LSPosed 1.9.2 | 2023-10，GitHub 已归档 | 公开 | 8.1 – 14 | Magisk 24+ | ✅ |
| LSPosed 2.x | 2.2.1（2026-10） | **闭源**，仅 Telegram / lsposed.zip | 9 – 17 | Magisk 27+ / KSU / APatch | ✅ |
| Vector 2.x | 2.2（2026-08），JingMatrix fork | 公开 GPL-3.0 | 8.1 – 17 | Magisk 27+ / KSU / APatch | ✅ |

本模块用 `de.robv.android.xposed:api:82`（**经典 Xposed API**），不属于被
Vector 2.1+ / LSPosed 2.x 拒绝的「仅按 libxposed API 100 构建」那一类，三家框架均可加载。
`xposedminversion` 已设为 **82**（最宽兼容；写 93 虽然三家都过，但会把只报 82/89 的小框架挡在外面，
且 LSPosed 2.2 起会据此弹兼容警告，官方建议就是 82）。

选型建议：

- **A9–A14 的虚拟机 / 老机**：LSPosed 1.9.2 zygisk。A9 上唯一稳的老牌选择，资源占用也最小。
- **A15+ 物理机**：Vector 2.x（开源可审计）或 LSPosed 2.x（对检测的隐藏能力更强）。
- **一次只装一个框架**，混装会互相冲突。

### 13.3 通用六步（任何平台都一样）

```
① 拿 root（Magisk / KernelSU / APatch，Magisk 要在设置里开 Zygisk）
② 刷框架 zip → 重启
③ 装框架管理器 APK + 本模块 APK
④ 管理器里启用模块；作用域【只勾目标 App + com.air.locspoof】
   ⚠️ 绝对不要勾「系统框架」/ android / com.android.phone —— 见 0.5 节
⑤ 打开本 App → 点地图选点或填坐标 → 右上开关打开
⑥ 点「验证定位」，看回包是否等于目标坐标
```

### 13.4 各平台差异

| 平台 | root 怎么拿 | 框架怎么装 | 注意 |
|---|---|---|---|
| 普通物理机 | 解锁 BL → Magisk / KSU / APatch | Magisk → 模块 → 从本地安装 zip | BL 解锁会清数据，先备份 |
| **VMOS Pro（A9）** | 虚拟机内设置里一键开，不动真机 | 内置 Xposed 或刷 1.9.2 zip | VMOS 自带位置模拟要关，见 13.5 |
| 云手机 / 远程真机 | 看厂商是否给 root | — | 不给 root 就整条路线走不通 |
| 免 root（LSPatch） | 不需要 | 内嵌模块进目标 APK | **本项目目标 App 是加固包，不可行**，见第 11 节 |

### 13.5 VMOS Pro（A9）详细步骤

VMOS Pro 3.1.8（湖南小算科技）。官方支持 A9–A15，其中 **A9 64-bit ROM 最稳**；
A13/A14 有已知的「开机卡 99%」问题，官方自己都建议退回「安卓 9.0 尝鲜版」ROM。

```
1. VMOS Pro → 添加虚拟机 → 选「Android 9.0 (64-bit)」ROM
   RAM ≥ 3GB（真机总内存 6GB 以上时），存储 8–32GB
2. 启动虚拟机 → 设置 → 关于手机 → 连点版本号 → 开启开发者模式
3. 设置 → 系统设置 → 开发者选项 →【Root 开关】→ 按提示重启虚拟机
4. 关掉 VMOS 自带位置模拟：虚拟机内 设置 → 位置信息 → 关
   （不关它会和本模块互相盖：VMOS 改系统定位 → 本模块再改 getter → 最终以本模块为准。
     排查时分不清是谁在起作用）
5. 装框架，二选一：
   A. 虚拟机工具箱 → Xposed → 安装激活框架（省事）
   B. 导入 Magisk APK 触发自动 root → Magisk 模块 → 从本地安装
      LSPosed-v1.9.2-7024-zygisk-release.zip（可控，需 Magisk 开 Zygisk）
6. 装本模块 APK → 框架里启用 → 作用域只勾目标 App + com.air.locspoof
7. 重启虚拟机 → 打开本 App 写坐标 → 点「验证定位」
```

**adb 调试**：VMOS Pro 的激活走「vmos 助手 + 无线调试 + 配对码配对」，
不是常规的 `adb connect <ip>:5555`。要看日志就在虚拟机内直接抓，
或用 VMOS 的共享目录把 log 导出到真机。

### 13.6 装好后怎么确认真的生效

框架日志里搜 `[LocSpoof]`，正常的注入日志长这样：

```
[LocSpoof/App] forged location getters
[LocSpoof/App] faked gnss status x12
[LocSpoof/App] blocked wifi scan x1
[LocSpoof/App] neutralized wifi info x2
[LocSpoof/App] blocked cell info x1
[LocSpoof/App] masked mock_location getters on Secure xN
```

**缺 `forged location getters` 就是没注入**，按顺序查：
1. 作用域里有没有勾目标 App（最常见原因）；
2. 目标 App 是否 force-stop 后重开过（勾完作用域必须重启目标进程，光重启虚拟机不够）；
3. 框架是否真的装上了（管理器显示已激活）。

### 13.7 失败模式对照表

| 现象 | 原因 | 处理 |
|---|---|---|
| APK 装不上 | minSdk 不满足 | 本模块 minSdk 28，A9+ 都能装；还装不上查签名/渠道 |
| 模块在框架里显示，但不生效 | 作用域没勾 / 目标进程没重启 | 勾目标 App + force-stop 目标 App |
| 手机或虚拟机自己重启 | 勾了「系统框架」 | 取消勾选，见 0.5 节 |
| 界面报「/data/system 同步失败」但定位正常 | VMOS 的 su 写不了 /data/system | 已知且无害，只看坐标是否生效（12.3 节第 2 条） |
| 界面显示已生效，目标 App 却拿到真实坐标 | 目标 App 不在作用域，或走 WiFi 指纹 | WiFi/基站屏蔽只在作用域内的进程生效，确认目标 App 已勾 |
| 加固 App 闪退 | 壳检测到 hook / 虚拟化 | 与模块无关，换环境或放弃 |

---

## 14. 相关仓库

| 仓库 | 内容 |
|---|---|
| 本仓库 | 定位伪造：App 进程内 getter 改写、GnssStatus 伪造、WiFi/基站屏蔽、A17 踩坑档案 |
| `android-adcleaner` | 控件级去广告：按资源包名 / 类名 / 广告位 id 识别并隐藏广告 View，不依赖 dex |
