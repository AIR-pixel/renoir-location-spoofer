package com.air.locspoof

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。xposed_init 里声明的类名。
 *
 * **作用域只勾目标 App，不要勾「系统框架」。**
 * 在 system_server 里挂 `Location.writeToParcel` 会把整机拖进重启循环：
 * A17 的 zygote 在 fork system_server 时要枚举 fd，碰到 FIFO 直接 JNI abort，
 * 而 init 对 zygote 死亡的反应是框架级 runtime restart（`sys.runtime.reboot.reason.last`
 * 里能看到 `ne#zygote64#...#unhandled`）。详见 README 第 0.5 / 9 节。
 *
 * 现在的主力是 AppHooks：在目标进程内改写 Location 的 getter，
 * 不碰系统框架，同样能覆盖走 LocationManager 的所有路径。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        log("initZygote, path=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android", "com.android.phone" -> SystemHooks.install(lpparam)
            // 其余包由 LSPosed 作用域控制，装了 hook 才生效
            else -> AppHooks.install(lpparam)
        }
    }

    private fun log(s: String) = XposedBridge.log("[LocSpoof] $s")
}
