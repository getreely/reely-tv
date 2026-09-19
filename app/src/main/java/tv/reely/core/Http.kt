package tv.reely.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * For the XMLTV guide, which can be tens of megabytes and take minutes to arrive.
     * No call timeout at all: the read timeout is what guards against a stalled transfer.
     */
    val bulk: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Short-timeout client for probing candidate server addresses. */
    val probe: OkHttpClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}
