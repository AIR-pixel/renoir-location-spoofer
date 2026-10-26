package com.air.locspoof

import android.content.ContentResolver
import android.location.Location
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * 目标进程内的 hook。
 *
 * 这层有两个用途：
 * 1. root 模式下做兜底——防止个别 App 通过 FusedLocationProvider 或缓存拿到未被
 *    系统服务改写的值；
 * 2. LSPatch 免 root 模式下是唯一的战场——此时没有 system_server 权限，
 *    只能靠系统自带的 mock provider 提供假坐标，再用这层把「这是 mock」的痕迹抹掉。
 *
 * 注意：这里不 hook getLatitude/getLongitude。系统层已经整体替换了 Location 对象，
 * 再叠一层容易和 Framework 内部的距离计算、时间校验打架，收益也接近零。
 */
object AppHooks {

    private const val TAG = "LocSpoof/App"

    /** 与 SystemHooks.SAT_COUNT 保持一致，两处对不上就会被判成脏数据。 */
    private const val SAT_COUNT = 12

    /** 每个 listener 最多主动投递几次假 fix。间隔按注册的 minTime，10 次约覆盖 10 秒。 */
    private const val DELIVER_MAX = 10

    /** 常见 GPS 星座 PRN 号，避免出现 1~12 这种一眼假的连续编号。 */
    private val PRN = intArrayOf(3, 7, 8, 10, 15, 16, 18, 21, 23, 26, 27, 29)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        forgeLocation(cl)
        hookMockFlag(cl)
        hookMockSetting(cl)
        fakeGnssStatus(cl)
        // 光改 getter 只能「改掉已经拿到手的坐标」。系统压根不产生 fix 时，
        // 调用方等的是回调，不是返回值 —— 必须自己把坐标投出去。
        hookCurrentLocation(cl)
        hookLocationUpdates(cl)
        blockWifiScan(cl)
        neutralizeWifiInfo(cl)
        blockCellInfo(cl)
    }

    // ---- 目标坐标（来自 Settings.Secure） ----

    private data class Geo(
        val lat: Double,
        val lon: Double,
        val acc: Float,
        val speed: Float,
        val bearing: Float,
        val alt: Float,
    )

    @Volatile private var geoCache: Geo? = null
    @Volatile private var geoAt = 0L

    /**
     * 从 Settings.Secure 读目标坐标，1s 缓存。
     *
     * 目标 App 读不到 /data/system/locspoof.xml（SELinux category 隔离），
     * 所以坐标改由 App 侧写进 Settings.Secure 中转 —— 这条通道任何 App 都能读。
     * ContentResolver 在 process fork 早期可能拿不到，拿不到就返回 null，
     * 调用方会放行真实值（宁可漏一次，也不要在没配置时瞎编坐标）。
     */
    private fun geo(): Geo? {
        val now = android.os.SystemClock.elapsedRealtime()
        // **只缓存成功结果。**
        // appbrand0 这类进程在 Application 就绪之前 currentApplication() 返回 null，
        // 原来读到 null 也照样把 geoAt 推进，于是一次失败就锁死 1 秒 ——
        // 而用户点签到恰恰就发生在那一秒里，表现就是「转圈然后没反应」。
        val cached = geoCache
        if (cached != null && now - geoAt < 1000L) return cached
        val g = runCatching {
            val app = android.app.AndroidAppHelper.currentApplication()
                ?: return@runCatching null
            val cr = app.contentResolver
            fun s(suffix: String): String? =
                Settings.Secure.getString(cr, Prefs.SECURE_KEY + suffix)
            val lat = s("_lat")?.toDoubleOrNull() ?: return@runCatching null
            val lon = s("_lon")?.toDoubleOrNull() ?: return@runCatching null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@runCatching null
            Geo(
                lat = lat,
                lon = lon,
                acc = s("_acc")?.toFloatOrNull() ?: 5f,
                speed = s("_speed")?.toFloatOrNull() ?: 0f,
                bearing = s("_bearing")?.toFloatOrNull() ?: 0f,
                alt = s("_alt")?.toFloatOrNull() ?: 45f,
            )
        }.getOrNull()
        if (g != null) {
            geoCache = g
            geoAt = now
        }
        return g
    }

    /**
     * 进程内改写 `Location` 的全部读取入口。
     *
     * 这是「不开 system_server 作用域」路线的主力：不进系统框架，就不会碰到
     * A17 zygote 的 fd 校验问题（`Unsupported st_mode for FD 5: FIFO`），
     * 也就不会把整机拖进 runtime restart。
     *
     * 代价是只对作用域内的 App 生效 —— 对本项目要解决的场景（校园跑打卡）完全够用。
     *
     * 为什么不改字段只改 getter：跨进程传过来的 Location 是 Parcel 反序列化出来的
     * 新对象，我们在 App 里能碰到的只有它的 getter。字段改了对 Binder 也不再生效。
     */
    private fun forgeLocation(cl: ClassLoader) {
        val loc = runCatching { XposedHelpers.findClass("android.location.Location", cl) }
            .getOrNull()
        if (loc == null) {
            log("Location missing")
            return
        }
        val byName = loc.declaredMethods
            .filter { it.parameterTypes.isEmpty() }
            .groupBy { it.name }

        fun hookOne(name: String, value: (Geo) -> Any) {
            val ms = byName[name] ?: return
            ms.forEach { m ->
                safeHook(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!spoofOn()) return
                        val g = geo() ?: return
                        param.result = value(g)
                    }
                })
            }
        }

        hookOne("getLatitude") { it.lat }
        hookOne("getLongitude") { it.lon }
        hookOne("getAccuracy") { it.acc }
        hookOne("getAltitude") { it.alt.toDouble() }
        hookOne("getSpeed") { it.speed }
        hookOne("getBearing") { it.bearing }
        // provider 与精度必须自洽：network + 5m 是物理上不可能的组合
        hookOne("getProvider") { "gps" }
        // 时间戳刷新，否则 SDK 会判「陈旧数据」丢弃
        hookOne("getTime") { System.currentTimeMillis() }
        hookOne("getElapsedRealtimeNanos") { android.os.SystemClock.elapsedRealtimeNanos() }
        hookOne("hasAccuracy") { true }
        hookOne("hasAltitude") { true }
        hookOne("hasSpeed") { true }
        hookOne("hasBearing") { true }
        hookOne("isMock") { false }
        hookOne("isFromMockProvider") { false }

        // 卫星数：很多 SDK（含高德）直接读 extras，缺了就画成 0 颗星 / 弱信号
        byName["getExtras"]?.forEach { m ->
            safeHook(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!spoofOn()) return
                    if (geo() == null) return
                    param.result = android.os.Bundle().apply {
                        putInt("satellites", SAT_COUNT)
                        putInt("maxCn0", 42)
                        putInt("meanCn0", 34)
                    }
                }
            })
        }

        // 一次性查询也得给值：很多 SDK 启动时先 getLastKnownLocation 拿个底
        val lm = runCatching { XposedHelpers.findClass("android.location.LocationManager", cl) }
            .getOrNull()
        lm?.declaredMethods
            ?.filter { it.name == "getLastKnownLocation" && it.parameterTypes.size == 1 }
            ?.forEach { m ->
                safeHook(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!spoofOn()) return
                        val g = geo() ?: return
                        param.result = buildLocation(g)
                    }
                })
            }

        log("forged location getters")
    }

    /**
     * 构造假 fix。
     *
     * `provider` 必须跟 listener 注册时用的那个一致：微信会按 provider 过滤，
     * 拿 passive listener 收到一条 provider=gps 的 fix 会直接丢掉 —— 表现就是
     * 「明明投递了，App 还是转圈」。
     *
     * 精度字段要补齐：只填经纬度的 Location 在结构化比对里很显眼，
     * 部分 SDK 会判成「数据不完整」降权。
     */
    private fun buildLocation(g: Geo, provider: String = "gps"): android.location.Location =
        android.location.Location(provider).apply {
            latitude = g.lat
            longitude = g.lon
            accuracy = g.acc
            altitude = g.alt.toDouble()
            speed = g.speed
            bearing = g.bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            verticalAccuracyMeters = g.acc
            speedAccuracyMetersPerSecond = 0.15f
            bearingAccuracyDegrees = 1.5f
            extras = android.os.Bundle().apply {
                putInt("satellites", SAT_COUNT)
                putInt("maxCn0", 42)
                putInt("meanCn0", 34)
            }
        }

    // ---- 主动投递：系统不产生 fix 时自己把坐标送过去 ----

    /**
     * `LocationManager.getCurrentLocation`（API 30+）—— 和 requestLocationUpdates
     * 完全独立的异步路径，新版微信 / 高德 / 各家 SDK 都优先走它。
     *
     * 原实现只在 SystemHooks 里 hook 过，而 system_server 作用域已经撤销（见 README 0.5），
     * 等于这条路径在目标 App 里完全裸奔 —— 这是「点一下转圈、然后没反应」的头号原因：
     * 这个方法必须等系统 fused 真的算出一个 fix 才回调。室内 + WiFi/基站被本模块屏蔽后，
     * 融合算法凑不出结果，于是既不回调也不报错，调用方只能一路超时到「定位异常」。
     *
     * 这里不等系统：直接短路原方法，把假坐标投给 Consumer。
     * 注意必须用 `beforeHookedMethod` + `param.result = null`（void 方法短路），
     * `afterHookedMethod` 里改不动参数、也拦不住原调用。
     */
    private fun hookCurrentLocation(cl: ClassLoader) {
        val lm = runCatching { XposedHelpers.findClass("android.location.LocationManager", cl) }
            .getOrNull()
        if (lm == null) {
            log("LocationManager missing")
            return
        }
        var n = 0
        lm.declaredMethods.filter { it.name == "getCurrentLocation" }.forEach { m ->
            safeHook(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!spoofOn()) return
                    probe("gCL hit provider=${param.args.getOrNull(0)}")
                    // 读不到目标坐标就放行原方法 —— 此时多半是进程太早期、
                    // Application 还没建好，硬短路会让调用方永远等不到回调。
                    val g = geo() ?: return
                    val consumer = param.args.lastOrNull() ?: return

                    // 已被取消的信号不能再回调，否则调用方会当成异常抛出来
                    val sig = param.args.filterIsInstance<android.os.CancellationSignal>()
                        .firstOrNull()
                    if (sig?.isCanceled == true) {
                        param.result = null
                        return
                    }

                    val loc = buildLocation(g)
                    val deliver = Runnable { acceptTo(consumer, loc) }
                    val ex = param.args.filterIsInstance<java.util.concurrent.Executor>()
                        .firstOrNull()
                    if (ex != null) {
                        runCatching { ex.execute(deliver) }
                            .onFailure {
                                android.os.Handler(android.os.Looper.getMainLooper()).post(deliver)
                            }
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post(deliver)
                    }
                    param.result = null   // void：跳过原方法，系统不用再去算 fix
                }
            })
            n++
        }
        log("hooked getCurrentLocation x$n")
    }

    /** 给 `Consumer<Location>` 投递。先试实现类的 accept，再退回接口方法。 */
    private fun acceptTo(consumer: Any, loc: android.location.Location) {
        val ok = runCatching {
            consumer.javaClass.methods
                .firstOrNull { it.name == "accept" && it.parameterTypes.size == 1 }
                ?.invoke(consumer, loc) != null
        }.getOrNull() ?: false
        if (ok) return
        runCatching {
            java.util.function.Consumer::class.java
                .getMethod("accept", Any::class.java)
                .invoke(consumer, loc)
        }.onFailure { log("getCurrentLocation deliver fail: ${it.message}") }
    }

    /**
     * `requestLocationUpdates` —— 注册监听器这条路。
     *
     * 只改 getter 不够：系统一直不产生 fix，listener 就永远收不到回调，
     * App 侧停在「等待定位」，UI 上就是转圈。室内这是常态。
     *
     * 所以注册成功后主动投递一次假 fix（延时 250ms，模拟真实冷启动耗时），
     * 再按 1s 间隔补几次，让「持续定位」类逻辑也能跑通。
     * 次数封顶 5 次：打卡场景一次就够，长驻 App 上无限打点既费电也更可疑。
     */
    private fun hookLocationUpdates(cl: ClassLoader) {
        val lm = runCatching { XposedHelpers.findClass("android.location.LocationManager", cl) }
            .getOrNull()
        if (lm == null) return
        val liClass = runCatching {
            XposedHelpers.findClass("android.location.LocationListener", cl)
        }.getOrNull() ?: return

        var n = 0
        lm.declaredMethods.filter { it.name == "requestLocationUpdates" }.forEach { m ->
            val liIdx = m.parameterTypes.indexOfFirst { liClass.isAssignableFrom(it) }
            if (liIdx < 0) return@forEach
            safeHook(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!spoofOn()) return
                    val li = param.args.getOrNull(liIdx)
                        as? android.location.LocationListener ?: return
                    // 回调必须落在 App 指定的 Looper 上，否则会在错误线程改 UI
                    val looper = param.args.filterIsInstance<android.os.Looper>()
                        .firstOrNull() ?: android.os.Looper.getMainLooper()
                    val provider = param.args.getOrNull(0) as? String ?: "gps"
                    // **必须尊重注册时的 minTime。**
                    // 微信注册的是 1s，我们原来 200ms 就投第一条，它的去重逻辑
                    // 会当成异常数据直接丢掉 —— 表现就是「投递成功但 App 毫无反应」。
                    val minGap = minIntervalOf(param.args).coerceIn(1000L, 5000L)
                    probe("rLU hit provider=$provider thread=${looper.thread.name} minGap=$minGap")
                    val h = android.os.Handler(looper)
                    var tries = 0
                    var delivered = 0
                    val tick = object : Runnable {
                        override fun run() {
                            if (!spoofOn()) return
                            val g = geo()
                            if (g == null) {
                                // 坐标还读不到（Application 未就绪）：继续等，别放弃。
                                // 原来这里直接 return，等于把「用户点签到」那一秒彻底放空。
                                probe("tick geo=null tries=$tries")
                            } else if (delivered < DELIVER_MAX) {
                                if (delivered == 0) {
                                    // 补全 LocationListener 的生命周期回调。
                                    // 不少老实现要等 onStatusChanged(AVAILABLE) 才认为
                                    // 「GPS 已就绪」，只给 onLocationChanged 会被无视。
                                    runCatching { li.onProviderEnabled(provider) }
                                        .onFailure { log("onProviderEnabled fail: ${it.message}") }
                                    runCatching {
                                        li.onStatusChanged(
                                            provider,
                                            android.location.LocationProvider.AVAILABLE,
                                            null,
                                        )
                                    }.onFailure { log("onStatusChanged fail: ${it.message}") }
                                }
                                runCatching { li.onLocationChanged(buildLocation(g, provider)) }
                                    .onFailure { log("deliver fail: ${it.message}") }
                                delivered++
                                probe("delivered #$delivered -> ${li.javaClass.simpleName}")
                            }
                            tries++
                            // 10 秒窗口：冷启动的小程序进程往往要几秒才让
                            // currentApplication() 就绪，窗口太短就白投了
                            if (tries < 20 && delivered < DELIVER_MAX) {
                                h.postDelayed(this, minGap)
                            }
                        }
                    }
                    h.postDelayed(tick, minGap)
                }
            })
            n++
        }
        log("hooked requestLocationUpdates x$n")
    }

    /**
     * 从 `requestLocationUpdates` 的参数里取「最小更新间隔」。
     *
     * 8 个重载里 minTime 的位置不固定：老 API 是 args[1] 的 long，
     * Criteria 版是 args[0]，LocationRequest 版得反射 `getIntervalMillis`。
     * 统一处理：先找 long，再找 LocationRequest，都没有就按 1s。
     */
    private fun minIntervalOf(args: Array<Any?>?): Long {
        if (args == null) return 1000L
        for (a in args) {
            if (a is Long && a > 0) return a
            if (a != null && a.javaClass.name == "android.location.LocationRequest") {
                val v = runCatching {
                    a.javaClass.getMethod("getIntervalMillis").invoke(a) as? Long
                }.getOrNull()
                if (v != null && v > 0) return v
            }
        }
        return 1000L
    }

    // ---- 开关 ----

    /**
     * 当前是否该伪造。
     *
     * 读 Settings.Secure 而不是 /data/system/locspoof.xml：后者被 SELinux
     * category 挡住，目标 App 进程读不到；Settings.Secure 谁都能读。
     *
     * 加 1s 缓存——这些 hook 会在高频路径上被调，每次跨进程读设置会抖。
     * 代价是关掉伪造后最多延迟 1s 才在目标 App 里失效，可以接受。
     *
     * 读不到时默认「按开启处理」：宁可继续伪装，也不要在用户没要求恢复的时候
     * 突然把真实坐标漏给目标 App。
     */
    private fun spoofOn(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - flagAt < 1000L) return flagOn
        flagAt = now
        flagOn = runCatching {
            val app = android.app.AndroidAppHelper.currentApplication()
                ?: return@runCatching true
            Settings.Secure.getInt(app.contentResolver, Prefs.SECURE_KEY, 1) != 0
        }.getOrDefault(true)
        return flagOn
    }

    @Volatile private var flagOn = true
    @Volatile private var flagAt = 0L

    @Volatile private var compatAt = 0L
    @Volatile private var compatOn = false

    /**
     * 兼容模式：**跳过** WiFi / 基站屏蔽。
     *
     * 默认关闭。开启后本模块不再拦 `getScanResults` / `getBSSID` / `getAllCellInfo`，
     * 让依赖多源融合的定位 SDK 能正常算出结果。
     *
     * 为什么需要这个：微信小程序定位走的是腾讯自研 LBS（WiFi 指纹 + 基站反查），
     * 不走 LocationManager。把它的输入全屏蔽掉，它既定不出位置也不会报错，
     * 表现就是「转圈 → 定位异常」。这种情况下继续屏蔽只会让情况更糟。
     *
     * 代价：这些 SDK 拿到真实 WiFi/基站后，可能绕开本模块的坐标伪造，
     * 直接反查到真实位置。所以只在「坐标已经是假的、但 App 定不出位置」时才开。
     *
     * 切换（不需要重启，force-stop 目标 App 即可）：
     *   `adb shell su -c 'settings put secure locspoof_compat 1'`
     *   `adb shell su -c 'settings put secure locspoof_compat 0'`  关
     */
    private fun compatMode(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - compatAt < 1000L) return compatOn
        compatAt = now
        compatOn = runCatching {
            val app = android.app.AndroidAppHelper.currentApplication()
                ?: return@runCatching false
            Settings.Secure.getInt(app.contentResolver, Prefs.K_COMPAT, 0) != 0
        }.getOrDefault(false)
        return compatOn
    }

    /**
     * 屏蔽 WiFi 扫描结果。
     *
     * 为什么放在 App 侧而不是 system_server：A11+ 起 WiFi 是 APEX 模块
     * （com.android.wifi），WifiServiceImpl 由独立 classloader 加载，
     * 用 system_server 的 classloader 找不到它（实测日志 `WifiServiceImpl missing`）。
     * 而 android.net.wifi.WifiManager 是 framework 类，任何 App 进程里都能拿到。
     *
     * 这里也听开关：关掉伪造后必须立刻把真实扫描结果放出去，
     * 否则「恢复真实定位」对靠 WiFi 指纹定位的 App 是半残的。
     */
    private fun blockWifiScan(cl: ClassLoader) {
        if (compatMode()) {
            log("compat mode: 放行 WiFi 扫描")
            return
        }
        val wm = runCatching { XposedHelpers.findClass("android.net.wifi.WifiManager", cl) }
            .getOrNull()
        if (wm == null) {
            log("WifiManager missing")
            return
        }
        var n = 0
        wm.declaredMethods.filter { it.name == "getScanResults" }.forEach { m ->
            safeHook(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!spoofOn()) return
                    param.result = emptyList<Any>()
                }
            })
            n++
        }
        log("blocked wifi scan x$n")
    }

    /**
     * 伪造 4 颗星的 GNSS 状态。
     *
     * 这是「显示弱信号」的真正解药。`GnssStatus` 里只有卫星编号、方位角、
     * 信噪比，**不含设备位置**，但它决定了所有 App 的信号格：室内实测只有
     * 2~4 颗星、信噪比 20 出头，UI 上就是两格灰条。
     *
     * 更麻烦的是高德/百度这类 SDK 会拿信号质量做「优先级投票」：GPS 判弱就
     * 降权，把权重让给自研 WiFi 指纹定位 —— 于是坐标又跳回真实位置。
     * 所以这里不只是修 UI，是让 GPS 结果被 SDK 采信。
     *
     * GnssStatus 是 Parcelable，system_server 里造的实例经过 createFromParcel
     * 到 App 进程会变成新对象，所以系统侧的 hook 管不到它，必须在这里补。
     *
     * 卫星数必须和 SystemHooks 写进 extras 的 `satellites` 一致，否则
     * 「extras 说 12 颗、GnssStatus 说 2 颗」这种自相矛盾反而更容易被判脏。
     */
    private fun fakeGnssStatus(cl: ClassLoader) {
        val gs = runCatching { XposedHelpers.findClass("android.location.GnssStatus", cl) }
            .getOrNull()
        if (gs == null) {
            log("GnssStatus missing")
            return
        }
        val CONSTELLATION_GPS = 1
        var n = 0

        fun install(m: Method, body: (Int) -> Any?) {
            safeHook(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!spoofOn()) return
                    val i = (param.args.getOrNull(0) as? Int) ?: 0
                    param.result = body(i)
                }
            })
            n++
        }

        gs.declaredMethods.forEach { m ->
            val idxOnly = m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == java.lang.Integer.TYPE
            when {
                m.name == "getSatelliteCount" && m.parameterTypes.isEmpty() ->
                    install(m) { SAT_COUNT }

                !idxOnly -> {}

                m.name == "getSvid" ->
                    install(m) { i -> PRN[i % PRN.size] }

                m.name == "getConstellationType" ->
                    install(m) { _ -> CONSTELLATION_GPS }

                m.name == "getCn0DbHz" ->
                    install(m) { i -> (40f - (i % 6) * 2) }

                m.name == "getBasebandCn0DbHz" ->
                    install(m) { i -> (38f - (i % 5) * 2) }

                m.name == "getElevationDegrees" ->
                    install(m) { i -> (35f + i * 4) }

                m.name == "getAzimuthDegrees" ->
                    install(m) { i -> ((i * 31) % 360).toFloat() }

                m.name == "usedInFix" ||
                m.name == "hasAlmanacData" ||
                m.name == "hasEphemerisData" ||
                m.name == "hasBasebandCn0DbHz" ->
                    install(m) { _ -> true }
            }
        }
        log("faked gnss status x$n")
    }

    /**
     * 抹掉当前连接的 WiFi 指纹。
     *
     * 只挡 `getScanResults` 不够：很多 SDK 连扫描都不做，直接读
     * `WifiInfo.getBSSID()` —— 一个 MAC 就足以让服务器反查到几十米内。
     * system_server 侧对 `WifiServiceImpl.getConnectionInfo` 也做了处理，
     * 但 A11+ WiFi 是 APEX 模块（com.android.wifi），classloader 不同，
     * 那条 hook 未必挂得上，所以这里补一层进程内的。
     */
    private fun neutralizeWifiInfo(cl: ClassLoader) {
        if (compatMode()) {
            log("compat mode: 放行 WiFi 连接信息")
            return
        }
        val wi = runCatching { XposedHelpers.findClass("android.net.wifi.WifiInfo", cl) }
            .getOrNull()
        if (wi == null) {
            log("WifiInfo missing")
            return
        }
        var n = 0
        wi.declaredMethods
            .filter {
                it.parameterTypes.isEmpty() &&
                    (it.name == "getBSSID" || it.name == "getSSID")
            }
            .forEach { m ->
                safeHook(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!spoofOn()) return
                        param.result =
                            if (m.name == "getSSID") "\"AndroidWifi\""
                            else "02:00:00:00:00:00"
                    }
                })
                n++
            }
        log("neutralized wifi info x$n")
    }

    /**
     * 屏蔽基站信息。
     *
     * WiFi 指纹被挡之后，AMap / 百度这类 SDK 的下一顺位是基站定位：
     * 把附近小区号（LAC/CID）发给自家服务器反查经纬度。这一步同样不经过
     * LocationManager，所以系统层的 writeToParcel 拦不住它。
     *
     * 断掉后 SDK 只剩系统 GPS 一条路，而那条已经是假坐标。
     * 这里同样是「屏蔽」而非「伪造成目标点附近的基站」——断掉后 SDK 会
     * 回落到 GPS，一般够用；真要完全自然，得按目标点伪造 CellInfo。
     */
    private fun blockCellInfo(cl: ClassLoader) {
        if (compatMode()) {
            log("compat mode: 放行基站信息")
            return
        }
        val tm = runCatching { XposedHelpers.findClass("android.telephony.TelephonyManager", cl) }
            .getOrNull()
        if (tm == null) {
            log("TelephonyManager missing")
            return
        }
        var n = 0
        tm.declaredMethods
            .filter { it.name == "getAllCellInfo" || it.name == "getNeighboringCellInfo"
                || it.name == "getCellLocation" }
            .filter { it.parameterTypes.isEmpty() }
            .forEach { m ->
                safeHook(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!spoofOn()) return
                        param.result = when {
                            m.returnType == java.util.List::class.java -> emptyList<Any>()
                            else -> null
                        }
                    }
                })
                n++
            }
        log("blocked cell info x$n")
    }

    /**
     * Location.isMock()（API 31+）与已废弃的 isFromMockProvider()。
     * 两个都要改：老 SDK 走后者，新 SDK 走前者，很多 App 两处都查。
     */
    private fun hookMockFlag(cl: ClassLoader) {
        val loc = runCatching { XposedHelpers.findClass("android.location.Location", cl) }
            .getOrNull() ?: return log("Location class missing")

        loc.declaredMethods
            .filter {
                it.name == "isMock" || it.name == "isFromMockProvider"
            }
            .filter { it.parameterTypes.isEmpty() }
            .forEach { m -> safeHook(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!spoofOn()) return
                    param.result = false
                }
            }) }

        // Provider 名也一起抹：mock 位置有时把 provider 写成 "gps" 之外的奇怪值
        runCatching {
            XposedHelpers.findAndHookMethod(
                Location::class.java, "getProvider", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val p = param.result as? String ?: return
                        if (p != "gps" && p != "network" && p != "fused" && p != "passive") {
                            param.result = "gps"
                        }
                    }
                })
        }
    }

    /**
     * Settings.Secure 里的 mock_location 开关。
     * 这是最廉价的一道检测：读一下全局设置就知道有没有开模拟位置。
     *
     * 覆盖范围比看上去要紧，因为同一个 key 有好几条读取路径：
     *   - `getInt(cr, "mock_location")`                     2 参
     *   - `getInt(cr, "mock_location", 0)`                  3 参（带默认值，最常见）
     *   - `getStringForUser(cr, "mock_location", uid)`      3 参
     *   - `NameValueCache.getStringForUser(cr, tag, name, uid)`  4 参（内部汇聚点）
     * 只挡其中一条，剩下的都能读到真值 1 —— 而 `getInt(cr, key, 0)` 恰恰是
     * 检测 SDK 最爱用的写法。
     *
     * key 所在的下标各版本不一样（2 参时在 index 1，NameValueCache 里在 index 2），
     * 所以不按固定下标取，而是扫一遍所有 String 参数看有没有 "mock_location"。
     */
    private fun hookMockSetting(cl: ClassLoader) {
        val secure = runCatching {
            XposedHelpers.findClass("android.provider.Settings\$Secure", cl)
        }.getOrNull() ?: return log("Settings.Secure missing")

        hookMockGetters(secure)

        // NameValueCache 是 Settings 所有 get 的内部汇聚点，堵住它能顺带覆盖
        // 上面没枚举到的重载。它是 private static 嵌套类，类名照样能反射到。
        runCatching {
            XposedHelpers.findClass("android.provider.Settings\$NameValueCache", cl)
        }.getOrNull()?.let { hookMockGetters(it) }
    }

    private val MOCK_GETTERS = listOf(
        "getString", "getInt", "getLong", "getFloat", "getBoolean",
        "getStringForUser", "getIntForUser", "getLongForUser",
        "getFloatForUser", "getBooleanForUser",
    )

    private fun hookMockGetters(cls: Class<*>) {
        var n = 0
        MOCK_GETTERS.forEach { name ->
            cls.declaredMethods
                .filter { it.name == name && it.returnType != Void.TYPE }
                .forEach { m ->
                    safeHook(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!spoofOn()) return
                            val hit = param.args.filterIsInstance<String>()
                                .any { it == "mock_location" }
                            if (!hit) return
                            param.result = zeroFor(m.returnType)
                        }
                    })
                    n++
                }
        }
        log("masked mock_location getters on ${cls.simpleName} x$n")
    }

    /** 把 mock_location 读成「没开模拟位置」：数值型给 0，字符串型给 "0"。 */
    private fun zeroFor(t: Class<*>): Any? = when (t) {
        String::class.java -> "0"
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Boolean.TYPE -> false
        else -> null
    }

    private fun safeHook(m: Method, cb: XC_MethodHook) {
        runCatching { XposedBridge.hookMethod(m, cb) }
            .onFailure { log("hook fail ${m.name}: ${it.message}") }
    }

    /**
     * 诊断探针：只打前几条，用来确认目标 App 到底走了哪条定位路径。
     *
     * 如果签到失败时日志里**完全没有**这两条，说明目标 App 根本不用
     * LocationManager —— 那就是腾讯自研 LBS 那条路，得靠兼容模式解决，
     * 补 hook 再多也没用。这个判断比反复猜省事。
     */
    private var probeN = 0
    private fun probe(s: String) {
        if (probeN < 40) {
            probeN++
            log(s)
        }
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}
