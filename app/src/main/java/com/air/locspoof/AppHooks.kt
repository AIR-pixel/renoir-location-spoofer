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

    /** 常见 GPS 星座 PRN 号，避免出现 1~12 这种一眼假的连续编号。 */
    private val PRN = intArrayOf(3, 7, 8, 10, 15, 16, 18, 21, 23, 26, 27, 29)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        forgeLocation(cl)
        hookMockFlag(cl)
        hookMockSetting(cl)
        fakeGnssStatus(cl)
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
        if (now - geoAt < 1000L) return geoCache
        geoAt = now
        geoCache = runCatching {
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
        return geoCache
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

    private fun buildLocation(g: Geo): android.location.Location =
        android.location.Location("gps").apply {
            latitude = g.lat
            longitude = g.lon
            accuracy = g.acc
            altitude = g.alt.toDouble()
            speed = g.speed
            bearing = g.bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            extras = android.os.Bundle().apply {
                putInt("satellites", SAT_COUNT)
                putInt("maxCn0", 42)
                putInt("meanCn0", 34)
            }
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
     */
    private fun hookMockSetting(cl: ClassLoader) {
        val secure = runCatching {
            XposedHelpers.findClass("android.provider.Settings\$Secure", cl)
        }.getOrNull() ?: return log("Settings.Secure missing")

        listOf("getString", "getInt", "getLong", "getFloat").forEach { name ->
            secure.declaredMethods
                .filter { it.name == name && it.parameterTypes.size == 2 }
                .forEach { m ->
                    safeHook(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!spoofOn()) return
                            val key = param.args[1] as? String ?: return
                            if (key != "mock_location") return
                            param.result = when (m.returnType) {
                                String::class.java -> "0"
                                java.lang.Integer.TYPE -> 0
                                java.lang.Long.TYPE -> 0L
                                java.lang.Float.TYPE -> 0f
                                else -> null
                            }
                        }
                    })
                }
        }
    }

    private fun safeHook(m: Method, cb: XC_MethodHook) {
        runCatching { XposedBridge.hookMethod(m, cb) }
            .onFailure { log("hook fail ${m.name}: ${it.message}") }
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}
