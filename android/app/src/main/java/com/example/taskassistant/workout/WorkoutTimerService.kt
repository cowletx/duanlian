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
import android.widget.RemoteViews
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
            // IMPORTANCE_HIGH：HyperOS/MIUI 对低重要性常驻通知的折叠非常激进，
            // 低重要性会连"展开箭头"都不给。HIGH 保证通知有完整的卡片与展开能力。
            // 声音/震动在下面关掉，避免每次进度更新都响。
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.workout_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建通知。
     *
     * 采用【RemoteViews 自定义布局】作为主要显示方式（见 res/layout/notification_workout_*.xml）：
     *   - setCustomContentView：折叠态。**3 个按钮放在这里**，因为 HyperOS 常驻通知
     *     经常折叠到无法展开，按钮只放在 big view 里就永远看不到；
     *   - setCustomBigContentView：展开态。有了它系统才提供下拉箭头。
     *
     * 同时仍然调用 addAction：万一某些 ROM 忽略自定义视图，系统会退回标准布局，
     * 此时 3 个标准 action 依然存在（双保险）。
     *
     * 注意这里**不设 setStyle(BigTextStyle)**：自定义内容视图与 Style 同时存在时，
     * 不同系统版本的处理优先级不一致，容易出现"布局被覆盖成纯文本"。只用 RemoteViews。
     */
    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            // 关键（Bug 2）：SINGLE_TOP + CLEAR_TOP + Manifest 的 singleTask，
            // 三者一起保证点击通知只是把已有 Activity 唤到前台（走 onNewIntent），
            // 而不是新建一个实例（那样会新建 ViewModel，训练进度就"归零"了）。
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_WORKOUT, true)
            },
            // 固定 requestCode=0 且 UPDATE_CURRENT：复用同一个 PendingIntent，
            // 避免每次更新通知都生成新的（会导致点击行为不一致）。
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            // 说明：这里不设 Style。自定义内容视图（下面 setCustomContentView /
            // setCustomBigContentView）就是最终显示的内容，避免 Style 与 RemoteViews
            // 的优先级差异导致布局被替换成系统模板。
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF4CAF50.toInt())
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // 标准 action：ROM 忽略自定义视图时仍可用
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
            // 自定义布局（主显示方式）
            .setCustomContentView(buildRemoteViews(text, big = false, contentIntent = contentIntent))
            .setCustomBigContentView(buildRemoteViews(text, big = true, contentIntent = contentIntent))

        return builder.build()
    }

    /**
     * 构建 RemoteViews。
     *
     * 注意 RemoteViews 的能力限制：只支持少数布局/控件，且所有要更新的 id
     * 必须在布局里真实存在，否则运行时直接抛异常（不是编译错误）。
     */
    private fun buildRemoteViews(text: String, big: Boolean, contentIntent: PendingIntent): RemoteViews {
        val layout = if (big) R.layout.notification_workout_big else R.layout.notification_workout_compact
        return RemoteViews(packageName, layout).apply {
            setTextViewText(R.id.notification_text, text)
            // 点正文 → 回到 App（单实例）
            setOnClickPendingIntent(R.id.notification_root, contentIntent)
            // 三个操作按钮
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

    companion object {
        /**
         * 频道 id 带 _v3：NotificationChannel 的重要性创建后不可修改。
         * v1 是 LOW、v2 是 DEFAULT，HyperOS 都折叠得看不到按钮；
         * 换新 id 才能让系统按 IMPORTANCE_HIGH 重建频道。
         */
        const val CHANNEL_ID = "workout_timer_v3"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

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
