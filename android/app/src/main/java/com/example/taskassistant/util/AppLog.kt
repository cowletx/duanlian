package com.example.taskassistant.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简的文件日志。
 *
 * 为什么需要：这台设备（HyperOS）上通知/前台服务出问题时，logcat 不方便抓，
 * 所以把关键事件同时写进应用私有目录的文件，用户可以在「设置 → 导出日志」里
 * 一次性导出，不需要 adb、不需要存储权限。
 *
 * 写入位置：`getExternalFilesDir(null)/logs/app.log`
 *   - 这是应用专属外部目录（/sdcard/Android/data/<包名>/files/logs/），
 *     API 19+ 不需要任何存储权限；
 *   - 文件超过 256KB 自动轮转成 app.log.1，避免无限增长。
 *
 * 注意：所有方法都不会抛异常（内部 runCatching），日志本身绝不能影响主流程。
 */
object AppLog {

    private const val TAG = "TaskAssistant"
    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /** 在 Application.onCreate 里调用一次。 */
    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
            val dir = File(base, "logs")
            runCatching { if (!dir.exists()) dir.mkdirs() }
            val f = File(dir, "app.log")
            runCatching {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val old = File(dir, "app.log.1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
            }
            logFile = f
        }
        d(
            "==== App 启动 | Android ${Build.VERSION.RELEASE}(SDK ${Build.VERSION.SDK_INT}) | " +
                "${Build.MANUFACTURER} ${Build.MODEL} ===="
        )
    }

    fun d(message: String) = write("D", message, null)

    fun e(message: String, throwable: Throwable? = null) = write("E", message, throwable)

    private fun write(level: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ').append(level).append(' ').append(message)
            if (throwable != null) {
                append(" || ").append(throwable.javaClass.name).append(": ").append(throwable.message)
            }
        }
        runCatching {
            if (level == "E") Log.e(TAG, line, throwable) else Log.d(TAG, line)
        }
        synchronized(lock) {
            val f = logFile ?: return
            runCatching { f.appendText(line + "\n", Charsets.UTF_8) }
        }
    }

    /** 读取全部日志（用于导出）。 */
    fun read(): String = synchronized(lock) {
        val f = logFile ?: return "（日志尚未初始化）"
        runCatching {
            if (!f.exists()) "（暂无日志）" else f.readText(Charsets.UTF_8)
        }.getOrElse { "（读取日志失败：${it.message}）" }
    }

    /** 日志文件绝对路径（设置页展示用）。 */
    fun path(): String = synchronized(lock) {
        logFile?.absolutePath ?: "（未初始化）"
    }
}
