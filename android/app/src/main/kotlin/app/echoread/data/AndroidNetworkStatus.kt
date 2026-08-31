package app.echoread.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.echoread.core.net.NetworkStatus

/**
 * [NetworkStatus] 的 Android 实现。引擎用它区分「设备根本没网」与「服务商连不上」——
 * 前者重试是纯粹的等待（飞行模式下重试 4 次仍然连不上），后者值得退避重试。
 *
 * 保守策略：任何拿不准的情况都返回 true。误报「无网络」会让一次本可自愈的抖动变成硬失败，
 * 比多重试几次糟糕得多。
 */
class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun online(): Boolean {
        val manager = cm ?: return true
        return try {
            val net = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(net) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }
}
