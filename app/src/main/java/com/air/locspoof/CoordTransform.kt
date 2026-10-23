package com.air.locspoof

/**
 * 中国坐标系转换。
 *
 * 这个问题绕不开：高德/腾讯瓦片用的是 GCJ-02（火星坐标，国家强制偏移），
 * 而 Android 的 Location 是 WGS-84。两者在中国境内差几百米，
 * 直接混用会出现「地图上点的是 A 地，定位结果显示在 B 地」。
 *
 * 所以统一约定：**内部存储一律用 WGS-84**，
 * 地图显示时 WGS→GCJ，地图点击时 GCJ→WGS。
 */
object CoordTransform {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /** WGS-84 → GCJ-02 */
    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    /**
     * GCJ-02 → WGS-84
     * 没有解析解，用不动点迭代逼近。5 次迭代已经能到厘米级。
     */
    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var wgsLat = lat
        var wgsLon = lon
        repeat(5) {
            val (gLat, gLon) = wgs2gcj(wgsLat, wgsLon)
            wgsLat -= gLat - lat
            wgsLon -= gLon - lon
        }
        return wgsLat to wgsLon
    }
}
