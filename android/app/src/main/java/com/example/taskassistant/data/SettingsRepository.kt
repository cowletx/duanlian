package com.example.taskassistant.data

import com.example.taskassistant.data.db.SettingsDao
import com.example.taskassistant.data.db.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设置项键名。 */
object SettingKeys {
    const val PERSISTENT_NOTIFICATION = "persistent_notification"
    const val VIBRATE_ON_REST_END = "vibrate_on_rest_end"

    /** 兼容模式：不用 RemoteViews 自定义通知布局，退回标准通知。 */
    const val NOTIFICATION_COMPAT = "notification_compat"
}

/** 设置项的默认值。 */
data class AppSettings(
    val persistentNotification: Boolean = true,
    val vibrateOnRestEnd: Boolean = true,
    /** 部分 ROM（如 HyperOS）会把自定义布局的通知整条丢弃，打开这个可退回标准通知。 */
    val notificationCompat: Boolean = false,
)

/**
 * 设置读写。原型里这些开关只是静态文案（点了没反应），
 * Android 端做成真实生效的持久化设置。
 */
class SettingsRepository(private val dao: SettingsDao) {

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        val map = all.associate { it.key to it.value }
        AppSettings(
            persistentNotification = map[SettingKeys.PERSISTENT_NOTIFICATION]?.toBooleanStrictOrNull() ?: true,
            vibrateOnRestEnd = map[SettingKeys.VIBRATE_ON_REST_END]?.toBooleanStrictOrNull() ?: true,
            notificationCompat = map[SettingKeys.NOTIFICATION_COMPAT]?.toBooleanStrictOrNull() ?: false,
        )
    }

    suspend fun setPersistentNotification(enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.put(SettingEntity(SettingKeys.PERSISTENT_NOTIFICATION, enabled.toString()))
    }

    suspend fun setVibrateOnRestEnd(enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.put(SettingEntity(SettingKeys.VIBRATE_ON_REST_END, enabled.toString()))
    }

    suspend fun setNotificationCompat(enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.put(SettingEntity(SettingKeys.NOTIFICATION_COMPAT, enabled.toString()))
    }
}
