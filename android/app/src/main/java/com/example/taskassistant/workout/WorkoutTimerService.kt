package com.example.taskassistant.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.taskassistant.MainActivity
import com.example.taskassistant.R

/**
 * 训练期间的前台服务：让休息倒计时在应用退到后台后仍准确，并让用户能看到训练进度。
 *
 * Android 版本适配（全部按系统规范实现，不做保活）：
 *  - API 26+：必须先建立 NotificationChannel；
 *  - API 29+：startForeground 可传 foregroundServiceType；
 *  - API 33+：POST_NOTIFICATIONS 是运行时权限。**未授权时不启动前台服务**，
 *    避免 Android 14+ 因不可见通知抛 ForegroundServiceStartNotAllowedException，
 *    此时界面内的倒计时仍然工作；
 *  - API 34+：specialUse 需要在 Manifest 里用 <property> 说明用途（见 AndroidManifest.xml）。
 *
 * 通知栏提供 3 个快捷操作（见 [buildNotification]）：
 *   「+1 次」  → [WorkoutActionBus.ADD_REP]
 *   「完成本组」→ [WorkoutActionBus.COMPLETE_SET]
 *   「结束训练」→ [WorkoutActionBus.FINISH]
 * 点击后经 [WorkoutActionReceiver] 广播进入 [WorkoutActionBus]，由 MainViewModel 处理。
 */
class WorkoutTimerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 兼容旧的「直接停服务」意图（保留以防外部/历史调用）
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()

        createChannel()
        val notification = buildNotification(title, text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 注意：FOREGROUND_SERVICE_TYPE_SPECIAL_USE 常量是 API 34 才引入的。
            // 在 API 29~33 上传 0，让系统使用 Manifest 里声明的类型，
            // 避免个别 ROM 因未知类型值直接抛 IllegalArgumentException。
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.workout_channel_name),
            // 用 DEFAULT 而不是 LOW：LOW 的常驻通知在多数 ROM 上会被折叠成一行，
            // 操作按钮要么不显示、要么必须手动展开，用户容易以为"没有按钮"。
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.workout_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF4CAF50.toInt())
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // 三个快捷操作。图标用系统内置 drawable（API 24+ 通知栏不显示操作图标，
            // 但传 0 在个别 ROM 上会导致整条 action 被丢弃，所以必须给真实资源）。
            .addAction(
                android.R.drawable.ic_input_add,
                getString(R.string.workout_action_add_rep),
                actionIntent(WorkoutActionBus.ADD_REP, REQUEST_ADD_REP),
            )
            .addAction(
                android.R.drawable.ic_menu_agenda,
                getString(R.string.workout_action_complete),
                actionIntent(WorkoutActionBus.COMPLETE_SET, REQUEST_COMPLETE_SET),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.workout_notification_stop),
                actionIntent(WorkoutActionBus.FINISH, REQUEST_FINISH),
            )
            .build()
    }

    /** 每个按钮独立的 requestCode，避免 PendingIntent 被复用成同一个。 */
    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, WorkoutActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /**
         * 频道 id 带 _v2：NotificationChannel 的重要性创建后不可修改，
         * 沿用旧的 "workout_timer"（IMPORTANCE_LOW）会让新设置不生效，
         * 所以换新 id 让系统按 DEFAULT 重建频道。
         */
        const val CHANNEL_ID = "workout_timer_v2"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val ACTION_STOP = "com.example.taskassistant.action.STOP_WORKOUT_TIMER"

        private const val REQUEST_ADD_REP = 11
        private const val REQUEST_COMPLETE_SET = 12
        private const val REQUEST_FINISH = 13

        /**
         * 启动/更新训练前台服务。
         *
         * @return true 表示已请求启动；false 表示当前系统不允许（API 33+ 未授权通知），
         *         调用方应退回「仅界面内计时」。
         */
        fun start(context: Context, title: String, text: String, canPostNotifications: Boolean): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPostNotifications) {
                return false
            }
            val intent = Intent(context, WorkoutTimerService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: IllegalStateException) {
                // 系统在后台启动限制下拒绝时不要崩溃，退回界面内计时
                false
            }
        }

        /** 停止服务（训练结束时调用）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WorkoutTimerService::class.java)) }
        }

        /** 判断是否已授予通知权限（API 32 及以下视为已授予）。 */
        fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
