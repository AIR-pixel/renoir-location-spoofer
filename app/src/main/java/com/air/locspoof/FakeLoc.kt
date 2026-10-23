package com.air.locspoof

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock

/**
 * 构造伪造的 Location 对象。
 *
 * 这里有个容易翻车的细节：Location 的 elapsedRealtimeNanos 如果为 0，某些接收方
 * （包括 LocationManagerService 内部的年龄校验、以及部分 App 的合理性判断）会直接丢弃这条
 * 位置，表现为「hook 装上了但没效果」。所以所有时间字段都必须给真实值。
 *
 * 另一个细节：字段不能只填经纬度。真实 GPS fix 一定带 bearing/speed/accuracy 等一整套
 * 运动学字段，缺字段的 Location 在结构化比对里很显眼。
 */
object FakeLoc {

    fun build(cfg: Config, template: Location? = null): Location {
        val l = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = cfg.lat
            longitude = cfg.lon
            accuracy = cfg.acc
            altitude = cfg.alt.toDouble()
            bearing = cfg.bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = cfg.acc
                // 速度精度与方位精度：给合理量级，不要给 0
                speedAccuracyMetersPerSecond = 0.15f
                bearingAccuracyDegrees = 1.5f
            }

            // 卫星数：App 的信号格通常读这里。缺了会被画成「0 颗星 / 弱信号」。
            extras = android.os.Bundle().apply {
                putInt("satellites", 12)
                putInt("maxCn0", 42)
                putInt("meanCn0", 34)
            }
        }

        // 有原始 fix 时继承其速度/方位，除非配置显式指定。
        // 静止伪造（speed=0）时保留原速度会更自然，但原地不动配 60km/h 更矛盾，
        // 所以默认以配置为准，只在配置为 0 且模板有值时折中取 0。
        l.speed = cfg.speed

        template?.let { t ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 保持与原始 fix 一致的 provider 之外的元数据可信度
                l.isMock = false
            }
            // 保留原始的运动学时间基准，避免「时间戳跳变」被识别
            if (l.elapsedRealtimeNanos <= 0L) {
                l.elapsedRealtimeNanos = t.elapsedRealtimeNanos
            }
        }

        return l
    }
}
