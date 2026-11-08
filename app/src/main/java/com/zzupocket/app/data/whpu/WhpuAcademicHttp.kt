package com.zzupocket.app.data.whpu

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal suspend fun Call.awaitAcademicBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(WhpuAcademicException.Network(e))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.academicBody()
                if (continuation.isActive) continuation.resume(body)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

internal fun Response.academicBody(): String = use {
    when {
        code == 401 || isRedirect -> throw WhpuAcademicException.SessionExpired()
        code == 403 -> throw WhpuAcademicException.Forbidden()
        !isSuccessful -> throw WhpuAcademicException.Http(code)
    }
    val text = try { body?.string().orEmpty() } catch (e: IOException) {
        throw WhpuAcademicException.Network(e)
    }
    if (text.trimStart().startsWith("<")) {
        if (text.contains("waf拦截", ignoreCase = true)) throw WhpuAcademicException.Forbidden()
        if (Regex("type\\s*=\\s*['\"]?password|login_slogin|cas/login", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            throw WhpuAcademicException.SessionExpired()
        }
        throw WhpuAcademicException.Parse(IllegalArgumentException("Unexpected HTML response"))
    }
    text
}

internal inline fun <T> parseAcademicData(parse: () -> T): T = try { parse() } catch (e: IllegalArgumentException) {
    throw WhpuAcademicException.Parse(e)
} catch (e: IllegalStateException) {
    throw WhpuAcademicException.Parse(e)
}
