package g.erp.satellite.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = when (resultCode) {
            0 -> "安装成功，刚安装好新版本。"
            2 -> "安装被取消。"
            3, 4 -> "安装被系统拦截（可能是签名不一致）。"
            8 -> "安装包无效。"
            else -> "安装未完成（代码 ${resultCode}）。"
        }
        notify(context, text)
    }

    private fun notify(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel(CHANNEL, "应用安装", NotificationManager.IMPORTANCE_DEFAULT)
                .also { manager.createNotificationChannel(it) }
            CHANNEL
        } else null
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("New Home 更新")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        } else {
            Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("New Home 更新")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        }
        manager.notify(1, notification)
    }

    companion object {
        const val ACTION = "g.erp.satellite.INSTALL_RESULT"
        private const val CHANNEL = "install"
    }
}