package com.example.taskassistant.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收通知栏操作按钮的广播，转发给 [WorkoutActionBus]。
 *
 * 用广播而不是 PendingIntent.getService 的原因：
 *   - 广播不涉及"5 秒内必须 startForeground"的前台服务启动约束，不会因为
 *     用户在后台点按钮而触发 ForegroundServiceDidNotStartInTimeException；
 *   - 不需要在 Service 的 onStartCommand 里区分"更新通知"和"处理按钮"两种意图。
 *
 * 在 Manifest 中以 android:exported="false" 注册（见 AndroidManifest.xml），
 * 只有本应用能发这个广播，外部应用无法伪造训练操作。
 */
class WorkoutActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        WorkoutActionBus.emit(action)
    }
}
