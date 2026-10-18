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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.taskassistant.MainActivity
import com.example.taskassistant.R
import com.example.taskassistant.util.AppLog

/**
 * 训练期间的前台服务。
 *
 * 通知有两种构建方式，由调用方通过 [EXTRA_COMPAT] 选择：
 *   1. 默认：RemoteViews 自定义布局（折叠态就带 3 个按钮），兼容性最好但依赖 ROM 支持；
 *   2. 兼容模式：纯标准通知（setContentText + addAction），部分 ROM 上自定义布局会被丢弃时用这个。
 * 另外 [onStartCommand] 对 startForeground 做了 try-catch：自定义布局失败会自动退化成
 * 标准通知，再失败则弹 Toast 并结束服务，绝不静默失败（之前"通知彻底消失"就是静默失败）。
 */
class WorkoutTimerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.d("Service: 收到 ACTION_STOP，停止服务")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val compat = intent?.getBooleanExtra(EXTRA_COMPAT, false) ?: false

        AppLog.d("Service: onStartCommand 进入 | compat=$compat | text=$text")
        createChannelSafely()

        // 依次尝试：自定义布局 → 标准通知 → 失败提示
        if (!compat) {
            val okCustom = tryStartForeground(buildNotification(title, text, customViews = true), "自定义布局")
            if (okCustom) return START_NOT_STICKY
            AppLog.e("Service: 自定义布局失败，退化为标准通知")
        }

        val okPlain = tryStartForeground(buildNotification(title, text, customViews = false), "标准通知")
        if (okPlain) return START_NOT_STICKY

        AppLog.e("Service: 两种通知都启动失败，结束服务")
        toast("通知显示失败，训练计时仅在应用内生效（设置→导出日志 可查看原因）")
        stopSelf()
        return START_NOT_STICKY
    }

    /** 包一层 try-catch：startForeground 在 API 34 可能因类型/权限抛异常。 */
    private fun tryStartForeground(notification: Notification, label: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE 是 API 34 引入的常量。
                // API 29~33 传 0，让系统采用 Manifest 声明的类型，
                // 避免个别 ROM 因未知类型值抛 IllegalArgumentException。
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AppLog.d("Service: startForeground 成功（$label）")
            true
        } catch (t: Throwable) {
            AppLog.e("Service: startForeground 抛出异常（$label）", t)
            false
        }
    }

    override fun onDestroy() {
        AppLog.d("Service: onDestroy")
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        }
    }

    private fun createChannelSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) {
                val existing = manager.getNotificationChannel(CHANNEL_ID)
                AppLog.d("Service: 通知频道已存在 importance=${existing?.importance}")
                return
            }
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.workout_channel_name),
                // IMPORTANCE_HIGH：HyperOS/MIUI 对低重要性常驻通知折叠极激进。
                // 声音/震动在下面关掉，避免每次进度刷新都响。
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.workout_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
            AppLog.d("Service: 已创建通知频道 $CHANNEL_ID（IMPORTANCE_HIGH）")
        }.onFailure { AppLog.e("Service: 创建通知频道失败", it) }
    }

    private fun buildNotification(title: String, text: String, customViews: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            // SINGLE_TOP + CLEAR_TOP + Manifest 的 singleTask：三者一起保证点通知只是
            // 把已有 Activity 唤到前台（走 onNewIntent），而不是新建实例重建 ViewModel。
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_WORKOUT, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF4CAF50.toInt())
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // 标准 action 始终保留：即使 ROM 忽略自定义视图，这些也能用
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

        if (customViews) {
            runCatching {
                builder.setCustomContentView(buildRemoteViews(text, big = false, contentIntent = contentIntent))
                builder.setCustomBigContentView(buildRemoteViews(text, big = true, contentIntent = contentIntent))
            }.onFailure { AppLog.e("Service: 构建 RemoteViews 失败", it) }
        }

        return builder.build()
    }

    /**
     * 构建 RemoteViews。布局里只用 LinearLayout + TextView（RemoteViews 支持的最小子集），
     * 背景用纯颜色而不是 shape drawable，尽量降低 ROM 挑剔导致整条通知被丢弃的概率。
     */
    private fun buildRemoteViews(text: String, big: Boolean, contentIntent: PendingIntent): RemoteViews {
        val layout = if (big) R.layout.notification_workout_big else R.layout.notification_workout_compact
        return RemoteViews(packageName, layout).apply {
            setTextViewText(R.id.notification_text, text)
            setOnClickPendingIntent(R.id.notification_root, contentIntent)
            setOnClickPendingIntent(R.id.action_add_rep, actionIntent(WorkoutActionBus.ADD_REP, REQUEST_ADD_REP))
            setOnClickPendingIntent(
                R.id.action_complete_set,
                actionIntent(WorkoutActionBus.COMPLETE_SET, REQUEST_COMPLETE_SET),
            )
            setOnClickPendingIntent(R.id.action_finish, actionIntent(WorkoutActionBus.FINISH, REQUEST_FINISH))
        }
    }

    /** 每个按钮独立的 requestCode，避免 PendingIntent 被复用成同一个。 */
    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, WorkoutActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun toast(message: String) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        /**
         * 频道 id 带 _v3：NotificationChannel 的重要性创建后不可修改。
         * v1=LOW、v2=DEFAULT 在该设备上都被折叠得看不到按钮，v3 用 IMPORTANCE_HIGH。
         */
        const val CHANNEL_ID = "workout_timer_v3"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        /** true = 兼容模式：不用 RemoteViews，只用标准通知。 */
        const val EXTRA_COMPAT = "notification_compat"

        /** 点通知进入 App 时带上，用于直接把界面切到训练页。 */
        const val EXTRA_OPEN_WORKOUT = "open_workout"

        const val ACTION_STOP = "com.example.taskassistant.action.STOP_WORKOUT_TIMER"

        private const val REQUEST_CONTENT = 0
        private const val REQUEST_ADD_REP = 11
        private const val REQUEST_COMPLETE_SET = 12
        private const val REQUEST_FINISH = 13

        /**
         * 启动/更新训练前台服务。
         *
         * @param canPostNotifications API 33+ 是否已授予通知权限。
         *        **未授权时不启动服务**（Android 14 会因不可见通知抛异常），
         *        调用方应提示用户并退回界面内计时。
         * @param compat true = 兼容模式（标准通知，不用 RemoteViews）。
         * @return true 表示已请求启动；false 表示条件不满足或无权限。
         */
        fun start(
            context: Context,
            title: String,
            text: String,
            canPostNotifications: Boolean,
            compat: Boolean,
        ): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPostNotifications) {
                AppLog.d("Service.start: 通知权限未授予，跳过前台服务（界面内计时继续）")
                return false
            }
            val intent = Intent(context, WorkoutTimerService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_COMPAT, compat)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLog.d("Service.start: startForegroundService 已调用 | compat=$compat")
                true
            } catch (t: Throwable) {
                // 后台启动限制等情况下不要崩溃，退回界面内计时
                AppLog.e("Service.start: 启动前台服务失败", t)
                false
            }
        }

        /** 停止服务（训练结束时调用）。 */
        fun stop(context: Context) {
            AppLog.d("Service.stop: 请求停止前台服务")
            runCatching { context.stopService(Intent(context, WorkoutTimerService::class.java)) }
                .onFailure { AppLog.e("Service.stop: 停止前台服务失败", it) }
        }

        /** 判断是否已授予通知权限（API 32 及以下视为已授予）。 */
        fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
