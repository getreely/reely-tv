package tv.reely.core

import android.content.Context
import android.os.Build
import tv.reely.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last time the app fell over, kept on the device so it can be read afterwards.
 *
 * Nothing is sent anywhere. A sideloaded app has no crash reporting of its own, and the
 * person it happened to is the one who can pass it on — so it is kept, said sorry for once
 * on the next start, and readable in Settings, About until it is cleared.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val UNSEEN = "last-crash.unseen"

    /** Records anything uncaught, then lets the system deal with it as it would have. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(app, previous))
    }

    /** The report, if there is one. */
    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    /** Whether the report is new since the app last said so; asking marks it seen. */
    fun takeUnseen(context: Context): Boolean {
        val flag = File(context.filesDir, UNSEEN)
        return flag.exists().also { flag.delete() }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, UNSEEN).delete()
    }

    /** What goes in the file: when, which build on which device, and the trace. */
    internal fun report(error: Throwable, thread: String, now: Date = Date()): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)
        return buildString {
            appendLine("Reely TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(stamp)
            appendLine("Thread: $thread")
            appendLine()
            // Long traces are cut: the top is what says what happened.
            append(trace.take(16_000))
        }
    }

    private class Recorder(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                File(context.filesDir, FILE).writeText(report(error, thread.name))
                File(context.filesDir, UNSEEN).writeText("1")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
