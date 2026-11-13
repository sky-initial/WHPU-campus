package com.zzupocket.app.data.whpu

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** In-memory only. The future WebView handoff must check its origin and navigation generation. */
class WhpuCardSession {
    private var authorization: String? = null
    private var userAgent: String? = null
    private var revision = 0L

    @Synchronized fun accept(token: String, tokenType: String = "bearer", userAgent: String) {
        require(token.isNotBlank() && token.none { it.isWhitespace() || it.isISOControl() })
        require(tokenType.equals("bearer", ignoreCase = true))
        require(userAgent.isNotBlank() && '\r' !in userAgent && '\n' !in userAgent)
        authorization = "bearer $token"
        this.userAgent = userAgent
        revision++
    }

    @Synchronized fun clear() {
        authorization = null
        userAgent = null
        revision++
    }

    @Synchronized internal fun capture(): Lease = Lease(
        revision, authorization ?: throw WhpuCardException.SessionExpired(),
        userAgent ?: throw WhpuCardException.SessionExpired(),
    )

    @Synchronized internal fun check(expectedRevision: Long) {
        if (revision != expectedRevision) throw WhpuCardException.SessionChanged()
    }

    internal fun check(lease: Lease) = check(lease.revision)

    // Not a data class: generated toString/copy must not expose credentials.
    internal class Lease(val revision: Long, val authorization: String, val userAgent: String)
}

/** Only the verified, read-only WHPU campus-card endpoints are used. No old CAS client. */
class WhpuCardProvider(
    private val session: WhpuCardSession,
    client: OkHttpClient = OkHttpClient(),
    private val maxPages: Int = 500,
) {
    private val client = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()

    suspend fun fetch(): WhpuCardSnapshot {
        val lease = session.capture()
        val owner = WhpuCardParser.owner(get(lease, "/berserker-base/user"))
        val type = WhpuCardParser.balanceType(get(lease, "/berserker-app/frontInfo"))
        val cards = WhpuCardParser.cards(get(lease, "/berserker-app/ykt/tsm/queryCard"), type)
        val transactions = if (cards.isEmpty()) emptyList() else collectWhpuCardPages(maxPages) { page ->
            WhpuCardParser.page(get(lease, "/berserker-search/search/personal/turnover",
                mapOf("size" to "8", "current" to page.toString())), cards.map { it.account }.toSet()).also {
                if (it.size != 8) throw WhpuCardException.Parse()
            }
        }
        if (owner != WhpuCardParser.owner(get(lease, "/berserker-base/user"))) {
            throw WhpuCardException.SessionChanged()
        }
        session.check(lease)
        return WhpuCardSnapshot(lease.revision, owner, cards, transactions)
    }

    private suspend fun get(lease: WhpuCardSession.Lease, path: String,
        query: Map<String, String> = emptyMap()): String {
        session.check(lease)
        val url = "https://ecardapp.whpu.edu.cn".toHttpUrl().newBuilder().encodedPath(path)
            .addQueryParameter("synAccessSource", "h5")
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).get()
            .header("synjones-auth", lease.authorization)
            .header("User-Agent", lease.userAgent)
            .header("Accept", "application/json").build()
        val body = client.newCall(request).awaitCardBody()
        session.check(lease)
        return body
    }
}

private suspend fun Call.awaitCardBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(WhpuCardException.Network())
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use {
                    if (it.code == 401 || it.isRedirect) throw WhpuCardException.SessionExpired()
                    if (!it.isSuccessful) throw WhpuCardException.Http(it.code)
                    it.body?.string() ?: throw WhpuCardException.Parse()
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (_: IOException) {
                if (continuation.isActive) continuation.resumeWithException(WhpuCardException.Network())
            } catch (e: WhpuCardException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
