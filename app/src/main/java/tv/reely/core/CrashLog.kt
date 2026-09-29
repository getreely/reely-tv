package tv.reely.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
    /** When the newest exit the system told us about happened, so each is said once. */
    private const val EXIT_SEEN = "last-exit.seen"

    /** Records anything uncaught, then lets the system deal with it as it would have. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(app, previous))
        runCatching { recordLastExit(app) }
    }

    /*
     * What the uncaught handler above can't see. A crash inside a video decoder, or the
     * system closing the app for memory, ends the process without any Kotlin exception —
     * which is how four channels at once could take the app down and leave no report.
     * Android 11 and up keeps its own note of why each process ended; the newest one
     * somebody would have seen happen is read here on the next start and kept like any
     * other report.
     */
    private fun recordLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val seenFile = File(context.filesDir, EXIT_SEEN)
        val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        // A few back, not just the newest: installing an update ends the process too, and
        // would otherwise always be the latest thing the system has to say.
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .filter { it.timestamp > seen }
        if (exits.isEmpty()) return
        seenFile.writeText(exits.maxOf { it.timestamp }.toString())
        val exit = exits.firstOrNull { exitKind(it.reason, it.importance, it.status) != null } ?: return
        val kind = exitKind(exit.reason, exit.importance, exit.status) ?: return
        // A Kotlin crash written since is the better report; this one is older news.
        val existing = File(context.filesDir, FILE)
        if (existing.exists() && existing.lastModified() > exit.timestamp) return
        // An ANR's trace is text and says where it was stuck. A native crash's is a binary
        // tombstone on newer versions, so only the system's description is kept for it.
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        } else {
            null
        }
        File(context.filesDir, FILE).writeText(
            exitReport(
                kind = kind,
                description = exit.description,
                memoryKb = exit.pss,
                at = Date(exit.timestamp),
                trace = trace,
            ),
        )
        File(context.filesDir, UNSEEN).writeText("1")
    }

    /**
     * What an exit the system recorded was, in words, or null when it's nothing to report:
     * closed on purpose, a Kotlin crash the handler above already wrote down, or the app
     * being tidied away while it was in the background.
     */
    internal fun exitKind(reason: Int, importance: Int, status: Int): String? {
        // Background (cached) processes are closed all the time; nobody saw that happen.
        val onScreen = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return when (reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "It crashed in native code, most likely a video or audio decoder."
            ApplicationExitInfo.REASON_ANR -> "It stopped responding, and the system closed it."
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                "The system closed it to free memory.".takeIf { onScreen }
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "The system closed it for using too much."
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "It failed while starting up."
            // Killed by a signal: 9 is how the low-memory killer ends it on devices that
            // don't report memory as the reason.
            ApplicationExitInfo.REASON_SIGNALED ->
                "The system ended it (signal $status).".takeIf { onScreen }
            else -> null
        }
    }

    /** The report for an exit the system recorded, laid out as [report] lays out a crash. */
    internal fun exitReport(kind: String, description: String?, memoryKb: Long, at: Date, trace: String?): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(at)
        return buildString {
            appendLine("Reely TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(stamp)
            appendLine(kind)
            appendLine()
            if (!description.isNullOrBlank()) appendLine("System's note: $description")
            if (memoryKb > 0) appendLine("Memory in use: ${memoryKb / 1024} MB")
            if (!trace.isNullOrBlank()) {
                appendLine()
                append(trace.take(16_000))
            }
        }
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
