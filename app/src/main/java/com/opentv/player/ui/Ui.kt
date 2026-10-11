package com.opentv.player.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.opentv.player.R
import com.opentv.player.data.PlaybackPrefs

/** 界面通用工具：统一深色对话框、Toast、剪贴板、PIN 交互。 */
object Ui {

    fun dialog(ctx: Context): AlertDialog.Builder = AlertDialog.Builder(ctx, R.style.Theme_OpenTV_Dialog)

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(ctx, ctx.getString(R.string.copied))
    }

    /** 生成一个 4 位数字输入框（密码样式）。 */
    fun pinInput(ctx: Context): EditText = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = "••••"
        isSingleLine = true
        setTextColor(ContextCompat.getColor(ctx, R.color.text))
    }

    fun wrap(ctx: Context, v: View): FrameLayout = FrameLayout(ctx).apply {
        setPadding(48, 24, 48, 0)
        addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
}

/** 校验 PIN 后执行（未启用家长控制时直接执行）。 */
fun Activity.askPin(prefs: PlaybackPrefs, title: CharSequence, onSuccess: () -> Unit) {
    if (!prefs.parentalEnabled || !prefs.hasPin()) {
        onSuccess()
        return
    }
    val input = Ui.pinInput(this)
    Ui.dialog(this)
        .setTitle(title)
        .setView(Ui.wrap(this, input))
        .setPositiveButton(R.string.ok) { _, _ ->
            val pin = input.text.toString().trim()
            if (prefs.verifyPin(pin)) onSuccess() else Ui.toast(this, getString(R.string.pin_wrong))
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** 设置 4 位 PIN（两次输入确认）。 */
fun Activity.setupPin(prefs: PlaybackPrefs, onDone: () -> Unit) {
    val first = Ui.pinInput(this)
    Ui.dialog(this)
        .setTitle(R.string.pin_set_title)
        .setView(Ui.wrap(this, first))
        .setPositiveButton(R.string.ok) { _, _ ->
            val p1 = first.text.toString().trim()
            if (p1.length != 4) {
                Ui.toast(this, getString(R.string.pin_need_4))
                return@setPositiveButton
            }
            val second = Ui.pinInput(this)
            Ui.dialog(this)
                .setTitle(R.string.pin_confirm_title)
                .setView(Ui.wrap(this, second))
                .setPositiveButton(R.string.ok) { _, _ ->
                    val p2 = second.text.toString().trim()
                    if (p1 != p2) {
                        Ui.toast(this, getString(R.string.pin_mismatch))
                    } else {
                        prefs.parentalPin = p1
                        prefs.parentalEnabled = true
                        Ui.toast(this, getString(R.string.pin_set_ok))
                        onDone()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
