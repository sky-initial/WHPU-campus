package com.zzupocket.app.data.whpu

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** In-memory only. WebView handoff checks its origin and navigation generation. */
class WhpuCardSession {
    private val mutex = Mutex()
    private var authorization: String? = null
    private var userAgent: String? = null
    private var revision = 0L

    suspend fun accept(token: String, tokenType: String = "bearer", userAgent: String,
        expectedRevision: Long? = null, isCurrent: () -> Boolean = { true }) = mutex.withLock {
        require(token.isNotBlank() && token.none { it.isWhitespace() || it.isISOControl() })
        require(tokenType.equals("bearer", ignoreCase = true))
        require(userAgent.isNotBlank() && '\r' !in userAgent && '\n' !in userAgent)
        if ((expectedRevision != null && revision != expectedRevision) || !isCurrent()) {
            throw WhpuCardException.SessionChanged()
        }
        authorization = "bearer $token"
        this.userAgent = userAgent
        revision += 1
    }

    suspend fun clear() = mutex.withLock {
        authorization = null
        userAgent = null
        revision += 1
    }

    internal suspend fun currentRevision(): Long = mutex.withLock { revision }

    internal suspend fun clearIfCurrent(expectedRevision: Long) = mutex.withLock {
        if (revision == expectedRevision) {
            authorization = null; userAgent = null; revision += 1
        }
    }

    internal suspend fun capture(): Lease = mutex.withLock { Lease(
        revision, authorization ?: throw WhpuCardException.SessionExpired(),
        userAgent ?: throw WhpuCardException.SessionExpired(),
    ) }

    internal suspend fun check(expectedRevision: Long) = withCurrent(expectedRevision) { }

    internal suspend fun <T> withCurrent(expectedRevision: Long, block: suspend () -> T): T = mutex.withLock {
        if (revision != expectedRevision) throw WhpuCardException.SessionChanged()
        block()
    }

    internal suspend fun check(lease: Lease) = check(lease.revision)

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
        return try { fetch(lease) } catch (e: WhpuCardException.SessionExpired) {
            session.clearIfCurrent(lease.revision)
            throw e
        }
    }

    private suspend fun fetch(lease: WhpuCardSession.Lease): WhpuCardSnapshot {
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
