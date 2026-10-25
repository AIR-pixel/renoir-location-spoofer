package com.air.locspoof

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var swEnable: SwitchCompat
    private lateinit var tvState: TextView
    private lateinit var tvCoordSummary: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvFix: TextView
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var etAcc: EditText
    private lateinit var etSpeed: EditText
    private lateinit var cbGcj: CheckBox
    private lateinit var llFavs: LinearLayout
    private lateinit var llCities: LinearLayout

    private var marker: Marker? = null

    /** 程序改开关时置 true，避免触发监听器造成递归保存。 */
    private var suppressSwitch = false

    private val K_FAVS = "favs"
    private val REQ_LOC = 1001

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            verifyLocation()
        }
    }

    /**
     * 高德瓦片。osmdroid 不内置国内源，OSM 官方瓦片在国内基本不可用，
     * 这里直接用高德的公开 XYZ 接口（无需 key）。
     * 注意这套瓦片是 GCJ-02 坐标，与 WGS-84 之间要靠 CoordTransform 换算。
     */
    private val amapSource = object : OnlineTileSourceBase(
        "AMap", 3, 18, 256, "",
        arrayOf("https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8"),
        "© AutoNavi"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "$baseUrl&x=$x&y=$y&z=$z"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)
        bindViews()
        setupMap()
        loadConfig()
        renderFavs()
        populateCities()
        tvStatus.text = diagnose()
        selfTestNetwork()
    }

    /**
     * 直连测一次瓦片接口，用来区分「App 网络不通」和「osmdroid 下载器失效」。
     * 结果只写 logcat（tag LocSpoofUI），不打扰界面。
     */
    private fun selfTestNetwork() {
        Thread {
            val url = "https://webrd01.is.autonavi.com/appmaptile" +
                "?lang=zh_cn&size=1&scale=1&style=8&x=3372&y=1690&z=12"
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", packageName)
                conn.connect()
                val code = conn.responseCode
                val bytes = conn.inputStream.readBytes()
                android.util.Log.i("LocSpoofUI", "tile test OK: code=$code bytes=${bytes.size}")
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("LocSpoofUI", "tile test FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun bindViews() {
        map = findViewById(R.id.map)
        swEnable = findViewById(R.id.swEnable)
        tvState = findViewById(R.id.tvState)
        tvCoordSummary = findViewById(R.id.tvCoordSummary)
        tvStatus = findViewById(R.id.tvStatus)
        tvFix = findViewById(R.id.tvFix)
        etLat = findViewById(R.id.etLat)
        etLon = findViewById(R.id.etLon)
        etAcc = findViewById(R.id.etAcc)
        etSpeed = findViewById(R.id.etSpeed)
        cbGcj = findViewById(R.id.cbGcj)
        llFavs = findViewById(R.id.llFavs)
        llCities = findViewById(R.id.llCities)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save(showToast = true) }
        findViewById<Button>(R.id.btnFav).setOnClickListener { addFav() }
        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnVerify).setOnClickListener { verifyLocation() }
        findViewById<Button>(R.id.btnRestore).setOnClickListener { restoreReal() }

        swEnable.setOnCheckedChangeListener { _, _ ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            save(showToast = false)
            refreshHeader()
        }
    }

    private fun setupMap() {
        map.setTileSource(amapSource)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.controller.setZoom(11.0)

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
    }

    /**
     * 地图给的是 GCJ-02，内部统一存 WGS-84，所以要反解一次。
     * 反过来，把已有坐标画到地图上时要先 WGS→GCJ。
     */
    private fun onMapTap(gcjLat: Double, gcjLon: Double) {
        val (wLat, wLon) = CoordTransform.gcj2wgs(gcjLat, gcjLon)
        etLat.setText(String.format(Locale.US, "%.6f", wLat))
        etLon.setText(String.format(Locale.US, "%.6f", wLon))
        cbGcj.isChecked = false
        dropMarker(wLat, wLon)
        refreshHeader()
    }

    private fun dropMarker(wgsLat: Double, wgsLon: Double) {
        val (gLat, gLon) = CoordTransform.wgs2gcj(wgsLat, wgsLon)
        val point = GeoPoint(gLat, gLon)
        marker?.let { map.overlays.remove(it) }
        marker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "目标位置"
        }
        map.overlays.add(marker)
        map.controller.animateTo(point)
        map.invalidate()
    }

    /**
     * 优先读 /data/system 里真正生效的配置。
     * 否则会出现「App 显示深圳、hook 实际用北京」的错位——
     * 用户以为改了没生效，其实改的是本地副本。
     */
    private fun loadEffectiveConfig(): Pair<Double, Double>? = runCatching {
        val proc = ProcessBuilder("su", "-c", "cat /data/system/locspoof.xml")
            .redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val lat = Regex("""name="lat"\s+value="([-\d.]+)"""").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()
        val lon = Regex("""name="lon"\s+value="([-\d.]+)"""").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()
        if (lat != null && lon != null) lat to lon else null
    }.getOrNull()

    private fun loadConfig() {
        val p = Prefs.write(this)
        val eff = loadEffectiveConfig()
        val lat = eff?.first ?: p.getFloat(Prefs.K_LAT, 39.908722f).toDouble()
        val lon = eff?.second ?: p.getFloat(Prefs.K_LON, 116.397499f).toDouble()
        etLat.setText(String.format(Locale.US, "%.6f", lat))
        etLon.setText(String.format(Locale.US, "%.6f", lon))
        etAcc.setText(p.getFloat(Prefs.K_ACC, 5f).toString())
        etSpeed.setText(p.getFloat(Prefs.K_SPEED, 0f).toString())

        suppressSwitch = true
        swEnable.isChecked = p.getBoolean(Prefs.K_ENABLED, false)
        suppressSwitch = false

        dropMarker(lat, lon)
        refreshHeader()
    }

    private fun refreshHeader() {
        val on = swEnable.isChecked
        tvState.text = getString(if (on) R.string.state_on else R.string.state_off)
        tvState.alpha = if (on) 1f else 0.55f
        tvCoordSummary.text = "${etLat.text}, ${etLon.text}"
    }

    /**
     * @param showToast 从开关触发时不弹 toast，从按钮触发时弹
     */
    private fun save(showToast: Boolean) {
        val latRaw = etLat.text.toString().toDoubleOrNull()
        val lonRaw = etLon.text.toString().toDoubleOrNull()
        if (latRaw == null || lonRaw == null) {
            if (showToast) toast("经纬度格式不对")
            return
        }

        // 勾了 GCJ-02 说明用户是从高德/腾讯地图上查到的坐标，先换成 WGS-84 再存
        val (lat, lon) = if (cbGcj.isChecked) {
            CoordTransform.gcj2wgs(latRaw, lonRaw)
        } else {
            latRaw to lonRaw
        }
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            toast("坐标超出范围")
            return
        }

        val acc = etAcc.text.toString().toFloatOrNull() ?: 5f
        val speed = etSpeed.text.toString().toFloatOrNull() ?: 0f

        val r = push(
            Config(lat = lat, lon = lon, acc = acc, speed = speed, bearing = 0f, alt = 45f),
            enabled = swEnable.isChecked
        )
        refreshHeader()
        dropMarker(lat, lon)
        tvStatus.text = diagnose()
        if (showToast) {
            toast(
                if (r.isSuccess) "已生效，无需重启"
                else "本地已写，但同步失败：${r.exceptionOrNull()?.message}"
            )
        }
    }

    /**
     * 关掉伪造，让系统回到真实定位。
     *
     * 三件事必须一起做，少一件都不算「恢复」：
     * 1. `/data/system/locspoof.xml` 写 enabled=false —— system_server 的 `writeToParcel` 随即放行真实坐标；
     * 2. `Settings.Secure.locspoof_on=0` —— 目标 App 进程里的 WiFi 扫描屏蔽随之解除
     *    （这个通道是给作用域内的 App 用的，它们读不到 /data/system）；
     * 3. 主动要一次定位，把真实值显示出来，让用户当场看见恢复成功。
     *
     * 不需要重启、不需要卸载、也不需要关掉 LSPosed 模块——伪造是在每次
     * `writeToParcel` 时按配置决定的，配置一改就即时生效。
     */
    private fun restoreReal() {
        suppressSwitch = true
        swEnable.isChecked = false
        suppressSwitch = false

        // 关掉伪造时坐标本身用什么无所谓（hook 会直接放行），但保住用户填的值，
        // 下次重新开启还是这个点。
        val (lat, lon) = runCatching {
            etLat.text.toString().toDouble() to etLon.text.toString().toDouble()
        }.getOrElse { loadEffectiveConfig() ?: (39.9042 to 116.4074) }

        val acc = etAcc.text.toString().toFloatOrNull() ?: 5f
        val speed = etSpeed.text.toString().toFloatOrNull() ?: 0f

        val r = push(
            Config(lat = lat, lon = lon, acc = acc, speed = speed, bearing = 0f, alt = 45f),
            enabled = false
        )
        refreshHeader()
        tvStatus.text = diagnose()
        tvFix.text = ""

        if (r.isSuccess) {
            toast("已关闭伪造，正在取一次真实坐标…")
            tvFix.postDelayed({ verifyLocation() }, 900)
        } else {
            toast("关闭失败：${r.exceptionOrNull()?.message}")
        }
    }

    /**
     * 把配置推到两个通道：/data/system（system_server）+ Settings.Secure（目标 App 进程）。
     * 两处都成功才算成功——只推一处会出现「系统是真的、App 还是假的」这种半残状态。
     */
    private fun push(cfg: Config, enabled: Boolean): Result<String> {
        // prefs 只用作本界面的记忆；真正生效的配置由 pushToSystem 直接落到 /data/system。
        Prefs.write(this).edit()
            .putBoolean(Prefs.K_ENABLED, enabled)
            .putFloat(Prefs.K_LAT, cfg.lat.toFloat())
            .putFloat(Prefs.K_LON, cfg.lon.toFloat())
            .putFloat(Prefs.K_ACC, cfg.acc)
            .putFloat(Prefs.K_SPEED, cfg.speed)
            .putFloat(Prefs.K_BEARING, cfg.bearing)
            .putFloat(Prefs.K_ALT, cfg.alt)
            .commit()

        val sys = Prefs.pushToSystem(this, cfg, enabled)
        val flag = Prefs.syncFlag(enabled)
        // 坐标也要进 Settings.Secure：不开 system_server 作用域时，
        // 目标 App 进程里的 getter hook 全靠这条通道拿目标坐标。
        val geo = Prefs.syncGeo(cfg)
        android.util.Log.i(
            "LocSpoofUI",
            "push: ${cfg.lat},${cfg.lon} enabled=$enabled " +
                "system=${sys.isSuccess}:${sys.exceptionOrNull()?.message ?: ""} " +
                "flag=${flag.isSuccess}:${flag.exceptionOrNull()?.message ?: ""} " +
                "geo=${geo.isSuccess}:${geo.exceptionOrNull()?.message ?: ""}"
        )

        // 成功判定只看 App 侧通道（flag + geo）。
        //
        // `/data/system/locspoof.xml` 只有 system_server 才读得到，而当前架构
        // （README 0.5 节）只勾目标 App、不勾系统框架 —— 这条路根本不在生效链路上。
        // 把它当成失败条件，会在 root 环境受限的机器上（虚拟机、部分 ROM）
        // 明明坐标已经写进 Settings.Secure 了，界面却报「同步失败」。
        return when {
            !flag.isSuccess -> Result.failure(
                IllegalStateException("写开关失败（目标 App 侧仍会屏蔽）：${flag.exceptionOrNull()?.message}")
            )
            !geo.isSuccess -> Result.failure(
                IllegalStateException("写坐标失败（目标 App 拿不到目标坐标）：${geo.exceptionOrNull()?.message}")
            )
            !sys.isSuccess -> Result.success(
                "已生效（App 侧通道正常）；/data/system 同步失败：" +
                    "${sys.exceptionOrNull()?.message} —— 只勾目标 App 时不影响生效"
            )
            else -> Result.success("ok")
        }
    }

    /**
     * 向系统要一次定位，把回包原样显示出来。
     *
     * 这是唯一能回答「到底生效没有」的检查：前面那些自检只证明配置写得进去，
     * 而这里证明的是 system_server 真的把伪装值发给了定位请求方。
     */
    private fun verifyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQ_LOC
            )
            toast("先给定位权限，再点一次")
            return
        }

        val target = loadEffectiveConfig()
        val spoofing = swEnable.isChecked
        tvFix.text = "取定位中…"
        val lm = getSystemService(LocationManager::class.java)
            ?: run { tvFix.text = "拿不到 LocationManager"; return }

        fun describe(src: String, l: Location): String {
            val same = target != null &&
                Math.abs(l.latitude - target.first) < 1e-4 &&
                Math.abs(l.longitude - target.second) < 1e-4
            // 关掉伪造时，回包「和配置不一致」正是我们要的结果，别让它显示成错误
            val verdict = when {
                !spoofing -> "已关闭伪造，这是系统真实坐标"
                same -> "✓ 与配置一致"
                else -> "✗ 与配置不一致"
            }
            return String.format(
                Locale.US,
                "回包[%s] %.6f, %.6f\n精度 %.1fm   mock=%s   %s",
                src, l.latitude, l.longitude, l.accuracy,
                if (l.isFromMockProvider) "是" else "否",
                verdict
            )
        }

        fun logit(src: String, l: Location?) {
            android.util.Log.i(
                "LocSpoofUI",
                "verify[$src] ${l?.latitude},${l?.longitude} acc=${l?.accuracy} mock=${l?.isFromMockProvider}"
            )
        }

        // 先亮出系统缓存值：这一条已经足以证明 hook 生效，
        // 因为 Location 跨进程必然走 writeToParcel。
        runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
            .getOrNull()?.let {
                logit("缓存-network", it)
                tvFix.text = describe("缓存-network", it)
            }

        val gotFresh = java.util.concurrent.atomic.AtomicBoolean(false)
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) {
            tvFix.text = "${tvFix.text}\n没有任何可用 provider"
            return
        }

        lateinit var listener: LocationListener
        val stop = {
            runCatching { lm.removeUpdates(listener) }
        }
        listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                gotFresh.set(true)
                logit("新鲜", l)
                tvFix.text = describe("新鲜", l)
                stop()
            }
        }

        providers.forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
        }

        tvFix.postDelayed({
            stop()
            if (!gotFresh.get()) {
                tvFix.text = "${tvFix.text}\n（8s 内没等到新定位，上面是系统缓存值）"
            }
        }, 8000)
    }

    private fun addFav() {        val lat = etLat.text.toString().takeIf { it.isNotBlank() } ?: return
        val lon = etLon.text.toString().takeIf { it.isNotBlank() } ?: return
        val p = Prefs.write(this)
        val list = (p.getString(K_FAVS, "") ?: "")
            .split(";").filter { it.isNotBlank() }.toMutableList()
        val entry = "$lat,$lon"
        list.remove(entry)
        list.add(0, entry)
        while (list.size > 6) list.removeAt(list.size - 1)
        p.edit().putString(K_FAVS, list.joinToString(";")).commit()
        renderFavs()
        toast("已收藏")
    }

    /** 城市坐标用 WGS-84，离线可用。地图依赖网络，城市按钮不依赖。 */
    private data class City(val name: String, val lat: Double, val lon: Double)

    private val CITIES = listOf(
        City("北京", 39.9042, 116.4074),
        City("上海", 31.2304, 121.4737),
        City("广州", 23.1291, 113.2644),
        City("深圳", 22.5431, 114.0579),
        City("杭州", 30.2741, 120.1551),
        City("成都", 30.5728, 104.0668),
        City("武汉", 30.5928, 114.3055),
        City("西安", 34.3416, 108.9398),
        City("南京", 32.0603, 118.7969),
        City("重庆", 29.5630, 106.5516),
    )

    private fun populateCities() {
        llCities.removeAllViews()
        for (c in CITIES) {
            val b = Button(this).apply {
                text = c.name
                textSize = 12f
                setPadding(30, 0, 30, 0)
                setOnClickListener {
                    etLat.setText(String.format(Locale.US, "%.6f", c.lat))
                    etLon.setText(String.format(Locale.US, "%.6f", c.lon))
                    cbGcj.isChecked = false
                    save(showToast = true)
                }
            }
            llCities.addView(
                b,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10 }
            )
        }
    }

    private fun renderFavs() {
        llFavs.removeAllViews()
        val list = (Prefs.write(this).getString(K_FAVS, "") ?: "")
            .split(";").filter { it.isNotBlank() }
        if (list.isEmpty()) return

        for (item in list) {
            val parts = item.split(",")
            if (parts.size != 2) continue
            val lat = parts[0].toDoubleOrNull() ?: continue
            val lon = parts[1].toDoubleOrNull() ?: continue

            val b = Button(this).apply {
                text = String.format(Locale.US, "%.4f, %.4f", lat, lon)
                textSize = 11f
                setPadding(24, 0, 24, 0)
                setOnClickListener {
                    etLat.setText(String.format(Locale.US, "%.6f", lat))
                    etLon.setText(String.format(Locale.US, "%.6f", lon))
                    cbGcj.isChecked = false
                    save(showToast = true)
                }
                setOnLongClickListener {
                    val p = Prefs.write(this@MainActivity)
                    val cur = (p.getString(K_FAVS, "") ?: "")
                        .split(";").filter { it.isNotBlank() && it != item }
                    p.edit().putString(K_FAVS, cur.joinToString(";")).commit()
                    renderFavs()
                    toast("已移除收藏")
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            llFavs.addView(b, lp)
        }
    }

    /** 支持 "39.9,116.4"、"39.9 116.4"，以及从地图 App 复制出来的任意含两个小数的文本。 */
    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isBlank()) {
            toast("剪贴板是空的")
            return
        }
        val nums = Regex("""-?\d{1,3}\.\d{3,}""").findAll(text)
            .map { it.value.toDouble() }.toList()
        if (nums.size < 2) {
            toast("没找到一对经纬度")
            return
        }
        etLat.setText(String.format(Locale.US, "%.6f", nums[0]))
        etLon.setText(String.format(Locale.US, "%.6f", nums[1]))
        cbGcj.isChecked = true // 从地图 App 复制的多半是 GCJ-02
        refreshHeader()
        toast("已粘贴。若来源是高德/腾讯，保持「输入是GCJ-02」勾选")
    }

    private fun diagnose(): String = buildString {
        appendLine("root      : ${if (hasSu()) "✓" else "✗ 需要授予 root"}")
        appendLine(
            "配置同步  : " + if (suTest("-f /data/system/locspoof.xml"))
                "✓ /data/system/locspoof.xml" else "✗ 未同步"
        )
        val flag = runCatching {
            android.provider.Settings.Secure.getInt(contentResolver, Prefs.SECURE_KEY, 1)
        }.getOrDefault(1)
        appendLine(
            "App 侧开关: " + if (flag != 0) "开（作用域内 App 的 WiFi 扫描被屏蔽）"
            else "关（真实扫描已放行）"
        )
        val hasLspd = File("/data/adb/lspd").exists() || suTest("-d /data/adb/lspd")
        append("框架      : ${if (hasLspd) "LSPosed 已装" else "未见 LSPosed"}")
        append("\n模块在 LSPosed 中启用，并勾选目标 App（不必勾「系统框架」），改动后重启一次")
    }

    private fun hasSu(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() == 0 && out.contains("uid=0")
    }.getOrDefault(false)

    private fun suTest(expr: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "test $expr && echo 1")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() == 0 && out.endsWith("1")
    }.getOrDefault(false)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        map.onDetach()
    }
}
