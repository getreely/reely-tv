package tv.reely.core

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What to say on screen when something didn't work: a short, calm sentence, the way a
 * streaming app says it, never an exception's name, a server's response or an error code.
 *
 * Failures that come from this app's own requests carry messages written for people
 * ("Incorrect username or password.") and are kept, less any code that crept into them.
 * Everything else, from the network or a library underneath, is put into plain words.
 */
object Friendly {
    const val GENERIC = "Something went wrong. Try again."
    const val OFFLINE = "Couldn't connect. Check your internet connection and try again."
    const val SLOW = "The server took too long to answer. Try again."
    const val SECURE = "Couldn't make a secure connection to the server."

    fun error(failure: Throwable): String {
        // The first cause that says something is the one to go by.
        val cause = generateSequence(failure) { it.cause }.take(5).toList()
        cause.forEach { when (it) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> return OFFLINE
            is SocketTimeoutException -> return SLOW
            is SSLException -> return SECURE
            else -> Unit
        } }
        if (cause.any { it is InterruptedIOException }) return SLOW
        // This app's own words come as IllegalArgument (require) or IllegalState (error).
        if (failure is IllegalArgumentException || failure is IllegalStateException) {
            return message(failure.message)
        }
        return GENERIC
    }

    /**
     * A message cleaned for the screen: without an "(error 503)" or "(404)" in it, and
     * replaced altogether when it's plainly not meant for people.
     */
    fun message(text: String?): String {
        val cleaned = text.orEmpty()
            .replace(CODE, "")
            .replace(Regex("\\s+([.,])"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (cleaned.isBlank() || looksTechnical(cleaned)) return GENERIC
        return cleaned
    }

    private val CODE = Regex("\\s*\\((?:error\\s*)?(?:code\\s*)?\\d{3}\\)", RegexOption.IGNORE_CASE)

    /** Stack-trace talk, JSON, URLs and the like: nothing a viewer should be shown. */
    private fun looksTechnical(text: String): Boolean =
        Regex("Exception|java\\.|kotlin\\.|org\\.json|https?://|\\{|\\}|\\bnull\\b|Expected|Unexpected|at line").containsMatchIn(text)
}
