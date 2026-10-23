package com.air.locspoof

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 跨进程配置。
 *
 * 设计取舍：hook 在 system_server 内运行，拿不到本 App 的 Context（system_server 的
 * getSystemContext() 指向 /data/system，读不到包私有目录）。createPackageContext() 虽然能
 * 绕过权限校验，但底层的文件权限检查仍以 system uid 执行，包目录默认 711 可穿越、
 * prefs 文件默认 600 不可读，所以依然失败。
 *
 * 因此这里放弃 SharedPreferences 的跨进程共享，改为：
 *   写入侧（App 进程）用正常 SharedPreferences，写完把 xml chmod 644；
 *   读取侧（system_server）直接按绝对路径解析那个 xml，绕开所有 Context 与内存缓存问题。
 *
 * 副作用是每次 hook 都要读一次文件。实测开销在微秒级（文件通常 <1KB），可以接受；
 * 换来的是「改坐标立刻生效、不用重启目标 App」，比缓存方案实用得多。
 */
object Prefs {

    private const val TAG = "LocSpoof/Prefs"
    private const val NAME = "locspoof"

    /**
     * 必须放在 system_server 有权读的位置。
     *
     * 放 /data/data/<pkg>/ 会让 hook 侧永远读到 null：Android 10+ 给 App 私有目录加了
     * SELinux category（如 s0:c83,c257,c512,c768），system_server 的域里没有这些 category，
     * 跨域访问会被拒，而这类拒绝常常不刷 avc 日志，表现成「hook 装上了但毫无反应」。
     * /data/system 是 system_data_file，system_server 天然可读。
     */
    private val XML: File
        get() = File("/data/system/locspoof.xml")

    const val K_ENABLED = "enabled"
    const val K_LAT = "lat"
    const val K_LON = "lon"
    const val K_ACC = "acc"
    const val K_SPEED = "speed"
    const val K_BEARING = "bearing"
    const val K_ALT = "alt"

    /**
     * 跨进程开关通道。
     *
     * /data/system/locspoof.xml 只有 system_server 读得到，作用域内的目标 App
     * （AppHooks 跑在里面）读不到——SELinux 的 category 隔离。但 **Settings.Secure
     * 任何 App 都能读**，所以用它当开关的广播通道：App 侧写，目标进程侧读。
     *
     * 有了这条通道，关掉伪造对目标 App 是**立即生效**的，不用重启进程。
     */
    const val SECURE_KEY = "locspoof_on"

    // SharedPreferences 序列化后的形态：
    //   <boolean name="enabled" value="true" />
    //   <float name="lat" value="22.5431" />
    // 只处理 boolean/float 两种，够用且不必引入 XmlPullParser 的状态机。
    private val ENTITY = Regex(
        """<(boolean|float|long|int|string)\s+name="([^"]+)"\s+value="([^"]*)"\s*/>"""
    )

    /** App 侧：拿正常的 SharedPreferences 写。 */
    fun write(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * hook 侧：直接解析文件。返回 null 表示未启用或数据不完整，调用方应放行真实坐标。
     *
     * 加 500ms 缓存：writeToParcel 在定位更新时可能每秒被调几十次，
     * 每次读文件会造成明显的 IO 抖动。
     */
    fun snapshot(): Config? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cachedAt < 500L) return cachedCfg
        cachedAt = now
        cachedCfg = readFromDisk()
        return cachedCfg
    }

    @Volatile private var cachedCfg: Config? = null
    @Volatile private var cachedAt = 0L

    private fun readFromDisk(): Config? {
        val f = XML
        if (!f.exists()) return null

        val text = try {
            f.readText()
        } catch (e: IOException) {
            // 最常见原因：文件权限 600，system_server 读不到。见 README「自检」。
            Log.w(TAG, "read failed: ${e.message}; path=${f.absolutePath}")
            return null
        }

        val kv = HashMap<String, String>(8)
        ENTITY.findAll(text).forEach { m ->
            kv[m.groupValues[2]] = m.groupValues[3]
        }

        if (kv[K_ENABLED] != "true") return null
        val lat = kv[K_LAT]?.toDoubleOrNull() ?: return null
        val lon = kv[K_LON]?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        return Config(
            lat = lat,
            lon = lon,
            acc = kv[K_ACC]?.toFloatOrNull() ?: 5f,
            speed = kv[K_SPEED]?.toFloatOrNull() ?: 0f,
            bearing = kv[K_BEARING]?.toFloatOrNull() ?: 0f,
            alt = kv[K_ALT]?.toFloatOrNull() ?: 30f,
        )
    }

    /**
     * App 侧写完 prefs 后，把配置推到 system_server 可读的位置。
     *
     * 这里**不能**再 `cp` 本 App 的 prefs 文件：Android 17（这台 renoir 的移植包里实测）
     * 已经把 SharedPreferences 的落盘位置从 `/data/data/<pkg>/shared_prefs/`
     * 搬到了 `/data/misc/apexdata/<uuid>/prefs/<pkg>/`，而旧路径下那份文件会作为
     * 遗留物一直留着、永不更新。照着旧路径 cp 的后果是「界面显示上海、实际生效北京」，
     * 而且因为 cp 成功、退出码 0，界面上完全看不出异常。
     *
     * 所以改成自己序列化 XML 直接落 /data/system，绕开 prefs 文件位置这个变量。
     */
    fun pushToSystem(ctx: Context, cfg: Config, enabled: Boolean): Result<String> = runCatching {
        val xml = render(cfg, enabled)

        // 先写进自己的私有目录，再借 root 拷过去：避免在 shell 命令里拼 XML（引号地狱）。
        val tmp = File(ctx.cacheDir, "locspoof.push.xml")
        tmp.writeText(xml)

        val cmd = "cp ${tmp.absolutePath} ${XML.absolutePath} && " +
            "chmod 644 ${XML.absolutePath} && " +
            "chown system:system ${XML.absolutePath} && " +
            "restorecon ${XML.absolutePath}"
        val p = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        if (code != 0) error("命令退出码 $code: $out")
        "已同步到 ${XML.absolutePath}（system_server 可读）"
    }

    /**
     * 把启用状态写到 Settings.Secure，供目标进程里的 AppHooks 读取。
     *
     * 用 `settings put` 而不是直接操作 SettingsProvider：走公开 CLI，
     * root 下不需要额外权限声明，也不用担心 provider 的调用者校验。
     */
    fun syncFlag(enabled: Boolean): Result<Unit> = runCatching {
        val v = if (enabled) 1 else 0
        val p = ProcessBuilder("su", "-c", "settings put secure $SECURE_KEY $v")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        if (code != 0) error("settings put 退出码 $code: $out")
    }

    /**
     * 把坐标本身也写进 Settings.Secure。
     *
     * 为什么需要第二条通道：目标 App 进程读不到 `/data/system/locspoof.xml`
     * （SELinux category 隔离），但它能读 Settings.Secure。App 侧要自己改
     * `Location` 的 getter，就必须知道目标坐标，所以只能从这儿取。
     *
     * 经纬度用字符串存，避免 float 精度损失（float 会把 114.471221 截成 114.47122）。
     */
    fun syncGeo(cfg: Config): Result<Unit> = runCatching {
        val cmds = listOf(
            "settings put secure ${SECURE_KEY}_lat ${cfg.lat}",
            "settings put secure ${SECURE_KEY}_lon ${cfg.lon}",
            "settings put secure ${SECURE_KEY}_acc ${cfg.acc}",
            "settings put secure ${SECURE_KEY}_speed ${cfg.speed}",
            "settings put secure ${SECURE_KEY}_bearing ${cfg.bearing}",
            "settings put secure ${SECURE_KEY}_alt ${cfg.alt}",
        )
        val p = ProcessBuilder("su", "-c", cmds.joinToString(" && "))
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        if (code != 0) error("settings put geo 退出码 $code: $out")
    }

    /** 与 SharedPreferences 序列化格式保持一致，hook 侧才解析得到。 */
    private fun render(cfg: Config, enabled: Boolean): String = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        append("<map>\n")
        append("    <boolean name=\"$K_ENABLED\" value=\"$enabled\" />\n")
        append("    <float name=\"$K_LAT\" value=\"${cfg.lat}\" />\n")
        append("    <float name=\"$K_LON\" value=\"${cfg.lon}\" />\n")
        append("    <float name=\"$K_ACC\" value=\"${cfg.acc}\" />\n")
        append("    <float name=\"$K_SPEED\" value=\"${cfg.speed}\" />\n")
        append("    <float name=\"$K_BEARING\" value=\"${cfg.bearing}\" />\n")
        append("    <float name=\"$K_ALT\" value=\"${cfg.alt}\" />\n")
        append("</map>\n")
    }
}

data class Config(
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val speed: Float,
    val bearing: Float,
    val alt: Float,
)
