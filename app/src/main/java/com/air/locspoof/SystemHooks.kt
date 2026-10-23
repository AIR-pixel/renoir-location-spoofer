package com.air.locspoof

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * system_server 内的 hook。
 *
 * 全部用「按方法名扫描 declaredMethods」而不是 findAndHookMethod 固定签名，原因是
 * 小米移植包对 framework 有改动，A14→A17 之间这些方法的参数类型也变过。
 * 按名字匹配 + 类型检查，比硬编码签名抗折腾。
 */
object SystemHooks {

    private const val TAG = "LocSpoof/Sys"
    private var installed = false

    /** 伪造出来的可见卫星数。12 颗 + 中等信噪比，是开阔地正常水平。 */
    private const val SAT_COUNT = 12

    /** A14 起部分 ROM 把它挪进了 com.android.server.location 包，两个都试。 */
    private val LMS_CANDIDATES = listOf(
        "com.android.server.LocationManagerService",
        "com.android.server.location.LocationManagerService",
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        val cl = lpparam.classLoader

        val lms = LMS_CANDIDATES.firstNotNullOfOrNull { name ->
            runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
        }
        if (lms == null) {
            log("LocationManagerService not found, abort")
            return
        }
        log("found ${lms.name}")

        hookLocationParcel(lms.classLoader)
        hookReceiverClass(lms)
        hookGetLastLocation(lms)
        hookCurrentLocation(lms)
        hookWifi(lms.classLoader)
        blockRawGnss(lms)
        installed = true
    }

    /**
     * 最关键的一个点：所有通过 requestLocationUpdates 注册的 LocationListener，
     * 最终都由 Receiver.callLocationChangedLocked 派发。在这里换掉入参，
     * 等于从源头改了所有人的数据。
     *
     * A17 把 LocationManagerService 挪进了 com.android.server.location 包，
     * 内部类全名必须跟着外层类走，不能硬编码旧包名。
     */
    private fun hookReceiverClass(lms: Class<*>) {
        val cl = lms.classLoader
        val recvName = lms.name + "\$Receiver"
        val receiver = runCatching { XposedHelpers.findClass(recvName, cl) }.getOrNull()
        if (receiver == null) {
            log("Receiver missing: $recvName")
            log("declaredClasses=${lms.declaredClasses.map { it.simpleName }}")
            return
        }

        val targets = receiver.declaredMethods.filter {
            it.name == "callLocationChangedLocked" && it.parameterTypes.size == 1
        }
        if (targets.isEmpty()) {
            log("callLocationChangedLocked not found, dump=${receiver.declaredMethods.map { it.name }}")
            return
        }

        targets.forEach { m -> safeHook(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cfg = Prefs.snapshot() ?: return
                val orig = param.args.getOrNull(0) as? Location
                param.args[0] = FakeLoc.build(cfg, orig)
            }
        }) }
        log("hooked Receiver.callLocationChangedLocked x${targets.size}")
    }

    /** getLastLocation 走的是另一条路，不 hook 这里，一次性查询会拿到真实值。 */
    private fun hookGetLastLocation(lms: Class<*>) {
        // A17 里签名变成 getLastLocation/4，不再限定参数个数，按返回类型匹配
        val m = lms.declaredMethods.firstOrNull {
            it.name == "getLastLocation" && it.returnType == Location::class.java
        }
        if (m == null) {
            log("getLastLocation missing")
            log("LOC_METHODS=${lms.declaredMethods.map { "${it.name}/${it.parameterCount}" }.distinct().sorted()}")
            return
        }

        safeHook(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val cfg = Prefs.snapshot() ?: return
                param.result = FakeLoc.build(cfg, param.result as? Location)
            }
        })
        log("hooked getLastLocation")
    }

    /** A12+ 的 getCurrentLocation 是独立路径，不经过 Receiver。 */
    private fun hookCurrentLocation(lms: Class<*>) {
        lms.declaredMethods
            .filter { it.name == "getCurrentLocation" }
            .forEach { m ->
                safeHook(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = Prefs.snapshot() ?: return
                        val cb = param.args.lastOrNull() ?: return
                        runCatching {
                            val inv = cb.javaClass.getMethod(
                                "accept", Any::class.java
                            )
                            inv.invoke(cb, FakeLoc.build(cfg, null))
                        }
                    }
                })
            }
    }

    /**
     * A17 真正的命门。
     *
     * 原本想 hook LocationManagerService$Receiver.callLocationChangedLocked，
     * 但 A17 把 LocationManagerService 拆成了壳（内部类只剩 LocalService / SystemInjector /
     * Lifecycle），分发下沉到 LocationProviderManager，老 hook 点不存在了。
     *
     * 与其追着每个版本改内部结构，不如卡在最稳定的边界：Location.writeToParcel()。
     * 任何 Location 跨进程传输前都必须走它，在这里把字段改掉，
     * 系统内部与所有 App 拿到的就都是假坐标，且不依赖任何分发实现。
     *
     * 注意改的是对象本身（setter），不是 getter 返回值——Binder 序列化读的是字段，
     * getter hook 对跨进程传输无效。
     */
    private fun hookLocationParcel(cl: ClassLoader) {
        val loc = runCatching { XposedHelpers.findClass("android.location.Location", cl) }.getOrNull()
        if (loc == null) {
            log("android.location.Location missing")
            return
        }
        val m = loc.declaredMethods.firstOrNull { it.name == "writeToParcel" }
        if (m == null) {
            log("writeToParcel missing")
            return
        }
        safeHook(m, object : XC_MethodHook() {
            private var n = 0
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cfg = Prefs.snapshot()
                if (cfg == null) {
                    if (n < 2) log("writeToParcel: cfg=null (配置读不到，放行真实坐标)")
                    n++
                    return
                }
                val l = param.thisObject as? Location
                if (l == null) {
                    if (n < 2) log("writeToParcel: thisObject not Location")
                    n++
                    return
                }
                runCatching {
                    l.latitude = cfg.lat
                    l.longitude = cfg.lon
                    l.accuracy = cfg.acc
                    l.altitude = cfg.alt.toDouble()
                    l.speed = cfg.speed
                    l.bearing = cfg.bearing

                    // 时间戳也要跟上。高德/百度 SDK 拿到一条 elapsedRealtimeNanos
                    // 落后于当前时钟的 fix 会判成「陈旧数据」直接丢，尤其
                    // getLastKnownLocation 那条路上拿到的往往是几分钟前的对象。
                    // 既然坐标是假的，时间就当它刚刚测出来。
                    l.time = System.currentTimeMillis()
                    l.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

                    // provider 必须与精度自洽。
                    // 「network 定位给出 5m 精度」在物理上不可能（网络定位通常 30m~2km），
                    // 高德/百度这类 SDK 拿到这种组合会判为异常数据直接丢弃，
                    // 然后回落到自研 WiFi 指纹定位 —— 于是界面显示真实位置，
                    // 用户看到的就是「我设了 A，它显示 B」。
                    l.provider = android.location.LocationManager.GPS_PROVIDER

                    // 卫星数是信号格的直接数据源。很多 App（含高德 SDK）读的是
                    // Location.extras 里的 satellites / maxCn0，缺了这几个键就画成
                    // 0 颗星 / 弱信号，与「GPS 定位成功」自相矛盾。
                    l.extras = android.os.Bundle(l.extras ?: android.os.Bundle()).apply {
                        putInt("satellites", SAT_COUNT)
                        putInt("maxCn0", 42)
                        putInt("meanCn0", 34)
                    }
                }
                if (n < 3) log("writeToParcel: injected ${cfg.lat},${cfg.lon} #$n")
                n++
            }
        })
        log("hooked Location.writeToParcel")
    }

    /**
     * 断掉 WiFi 指纹定位。
     *
     * 高德、百度、几何天气这类 App 的「自研定位」核心就是 WiFi BSSID 指纹：
     * 扫描周边 AP，把 BSSID 列表发给自家服务器反查经纬度。这条路完全不经过
     * LocationManager，所以 writeToParcel 那条 hook 拦不住它们。
     *
     * 处理方式：让扫描结果返回空、把当前连接的 BSSID 抹成占位值。
     * 拿不到指纹之后，这类 SDK 会回落到系统 GPS —— 而 GPS 已经是假坐标了。
     *
     * 注意两种返回形态：A13 之前直接是 List<ScanResult>，之后包了一层 ParceledListSlice，
     * 后者没法简单造空对象，所以只对 List 生效，其余交给它自己失败。
     */
    private fun hookWifi(cl: ClassLoader) {
        val impl = runCatching {
            XposedHelpers.findClass("com.android.server.wifi.WifiServiceImpl", cl)
        }.getOrNull()
        if (impl == null) {
            log("WifiServiceImpl missing")
            return
        }

        var n = 0
        val listType = java.util.List::class.java

        impl.declaredMethods.filter { it.name == "getScanResults" }.forEach { m ->
            safeHook(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Prefs.snapshot() == null) return
                    if (listType.isAssignableFrom(m.returnType)) {
                        param.result = emptyList<Any>()
                    }
                }
            })
            n++
        }

        impl.declaredMethods.filter { it.name == "getConnectionInfo" }.forEach { m ->
            safeHook(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Prefs.snapshot() == null) return
                    val info = param.result ?: return
                    runCatching {
                        val c = info.javaClass
                        c.getMethod("setBSSID", String::class.java)
                            .invoke(info, "02:00:00:00:00:00")
                        c.getMethod("setSSID", String::class.java)
                            .invoke(info, "\"\"")
                    }
                }
            })
            n++
        }

        log("hooked wifi x$n")
    }

    /**
     * 断掉 raw GNSS 旁路。
     *
     * 这几条不做，App 可以绕过 Location 直接读卫星观测数据：
     *   GnssMeasurementsEvent  —— 载波相位、多普勒，能反推真实位置
     *   GnssNavigationMessage  —— 原始导航电文
     *   GnssBatching           —— 批量 fix，常被用来做后台轨迹
     * 统一让注册直接失败，返回值按签名给 false / 0。
     *
     * **`registerGnssStatusCallback` 不在名单里，这是踩过的坑。**
     * GnssStatus 只给卫星编号、方位角、仰角、信噪比，**不含设备位置**，
     * 挡它没有防作弊收益，却让所有 App 拿到 0 颗卫星 ——
     * 表现为「明明定位成功，UI 上却是 GPS 弱信号」，
     * 跑步打卡类 App 甚至会据此判定定位不可用而拒绝开始。
     * 现在让它正常注册，信号格按真实信号画。
     *
     * **只对第三方 App 生效，系统/厂商组件一律放行。**
     * 高通定位服务（com.qualcomm.location，uid 1000）在初始化 IZat 的 ES 状态接收器时
     * 会注册 GNSS 观测回调；一旦拿到 false，它内部 `gnssService` 为 null，
     * 抛 `RuntimeException` 崩溃 —— 实测这一步会让它进入无限重启循环（一分钟 105 次），
     * 连带把 Location 服务和 system_server 拖进重启风暴，最终表现为
     * 「系统反复软重启 → 每次重开 system_server 都撞上 zygote 的 FD 校验 bug →
     * 开机失败计数烧光 → 掉进 fastboot」。
     * 用 `Binder.getCallingUid()` 把系统进程过滤掉，防作弊能力对普通 App 依然完整。
     */
    private fun blockRawGnss(lms: Class<*>) {
        val names = setOf(
            "addGnssMeasurementsListener",
            "addGnssNavigationMessageListener",
            "addGnssBatchingCallback",
            "addGnssAntennaInfoListener",
            "registerGnssMeasurementsCallback",
            "registerGnssNavigationMessageCallback",
            "registerGnssNmeaCallback",
        )
        var n = 0
        val hit = mutableListOf<String>()
        lms.declaredMethods.filter { it.name in names }.forEach { m ->
            safeHook(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Prefs.snapshot() == null) return
                    // 系统组件（uid < 10000）放行，见上面的注释
                    val uid = runCatching { android.os.Binder.getCallingUid() }.getOrDefault(0)
                    if (uid < android.os.Process.FIRST_APPLICATION_UID) return
                    param.result = when (m.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            })
            hit += m.name
            n++
        }
        log("blocked raw gnss x$n $hit (3rd-party only)")
    }

    private fun safeHook(m: Method, cb: XC_MethodHook) {
        runCatching { XposedBridge.hookMethod(m, cb) }
            .onFailure { log("hook fail ${m.name}: ${it.message}") }
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}
