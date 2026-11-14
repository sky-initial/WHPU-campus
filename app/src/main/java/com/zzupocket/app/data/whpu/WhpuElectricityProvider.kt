package com.zzupocket.app.data.whpu

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** Only directory and balance queries; never binds a room or creates a payment. */
class WhpuElectricityProvider(
    private val session: WhpuCardSession,
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()

    suspend fun directory(
        feeItemId: String = WHPU_GENERAL_ELECTRICITY,
        steps: List<WhpuElectricityStep> = emptyList(),
        expectedOwner: WhpuCardOwner? = null,
    ): WhpuElectricityDirectorySnapshot = withSession { lease ->
        require(steps.isEmpty() || expectedOwner != null)
        val owner = owner(lease)
        if (expectedOwner != null && owner != expectedOwner) throw WhpuElectricityException.SessionChanged()
        val directory = WhpuElectricityParser.directory(query(lease, feeItemId, steps, "select"))
        if (steps.size >= directory.levels.size || directory.levels.take(steps.size) != steps.map { it.field }) {
            throw WhpuElectricityException.Parse()
        }
        confirmOwner(lease, owner)
        WhpuElectricityDirectorySnapshot(lease.revision, owner, feeItemId, steps, directory)
    }

    suspend fun balance(room: WhpuElectricityRoom): WhpuElectricitySnapshot = withSession { lease ->
        WhpuElectricityParser.requestContext(room)
        val owner = owner(lease)
        if (owner != room.owner) throw WhpuElectricityException.SessionChanged()
        val amount = WhpuElectricityParser.balance(query(lease, room.feeItemId, room.steps, "IEC"), room)
        confirmOwner(lease, owner)
        WhpuElectricitySnapshot(lease.revision, room, amount)
    }

    private suspend fun owner(lease: WhpuCardSession.Lease): WhpuCardOwner =
        WhpuCardParser.owner(send(lease, "/berserker-base/user?synAccessSource=h5"))

    private suspend fun confirmOwner(lease: WhpuCardSession.Lease, expectedOwner: WhpuCardOwner) {
        if (owner(lease) != expectedOwner) throw WhpuElectricityException.SessionChanged()
        session.check(lease)
    }

    private suspend fun query(lease: WhpuCardSession.Lease, feeItemId: String,
        steps: List<WhpuElectricityStep>, type: String): String {
        require(feeItemId.toLongOrNull()?.let { it > 0 } == true)
        require(steps.map { it.field.level } == (1..steps.size).toList())
        require(steps.map { it.field.code }.toSet().size == steps.size)
        require(steps.all { it.field.code !in setOf("feeitemid", "type", "level") && it.option.value.isNotBlank() })
        val form = FormBody.Builder().add("feeitemid", feeItemId).add("type", type)
            .add("level", steps.size.toString())
        steps.forEach { form.add(it.field.code, it.option.value) }
        return send(lease, "/charge/feeitem/getThirdData", form.build())
    }

    private suspend fun send(lease: WhpuCardSession.Lease, path: String, form: FormBody? = null): String {
        session.check(lease)
        val request = Request.Builder().url("https://ecardapp.whpu.edu.cn$path")
            .header("synjones-auth", lease.authorization).header("User-Agent", lease.userAgent)
            .header("Accept", "application/json").apply { if (form != null) post(form) }.build()
        val body = client.newCall(request).awaitElectricityBody()
        session.check(lease)
        return body
    }

    private suspend fun <T> withSession(block: suspend (WhpuCardSession.Lease) -> T): T {
        val lease = try { session.capture() } catch (_: WhpuCardException.SessionExpired) {
            throw WhpuElectricityException.SessionExpired()
        }
        return try { block(lease)
        } catch (e: WhpuElectricityException.SessionExpired) {
            session.clearIfCurrent(lease.revision); throw e
        } catch (_: WhpuCardException.SessionExpired) {
            session.clearIfCurrent(lease.revision); throw WhpuElectricityException.SessionExpired()
        } catch (_: WhpuCardException.SessionChanged) { throw WhpuElectricityException.SessionChanged()
        } catch (_: WhpuCardException.Parse) { throw WhpuElectricityException.Parse()
        } catch (e: WhpuCardException.Business) { throw WhpuElectricityException.Business(e.code) }
    }
}

private suspend fun Call.awaitElectricityBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(WhpuElectricityException.Network())
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use {
                    if (it.code == 401 || it.isRedirect) throw WhpuElectricityException.SessionExpired()
                    if (!it.isSuccessful) throw WhpuElectricityException.Http(it.code)
                    it.body?.string() ?: throw WhpuElectricityException.Parse()
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (_: IOException) {
                if (continuation.isActive) continuation.resumeWithException(WhpuElectricityException.Network())
            } catch (e: WhpuElectricityException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
