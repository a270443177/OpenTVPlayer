package com.opentv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentv.player.data.PlaybackPrefs

/**
 * 开机自启：收到开机（或快速开机）广播后拉起主界面。
 *
 * 说明：Android 10 起系统默认禁止后台启动 Activity，只有获得
 * 「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）权限的应用才被豁免；
 * 另外部分电视 ROM 还需要在系统设置的自启动白名单里单独放行。
 * 因此设置页提供了授权引导入口。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return

        val prefs = PlaybackPrefs(context)
        android.util.Log.i("OpenTVBoot", "收到开机广播 $action，bootAutoStart=${prefs.bootAutoStart}")
        if (!prefs.bootAutoStart) return

        val app = context.applicationContext
        val pending = goAsync()
        // 稍等片刻再拉起界面：避开开机瞬间系统繁忙导致的启动被忽略
        Thread {
            try {
                Thread.sleep(3000)
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                android.util.Log.i("OpenTVBoot", "开机自启已触发")
            } catch (e: Exception) {
                android.util.Log.e("OpenTVBoot", "开机自启失败（可能被系统限制后台启动）", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
