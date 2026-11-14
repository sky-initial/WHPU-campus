package com.zzupocket.app.data.whpu

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** User-triggered evaluation on the exact school origin; never expose a persistent JS bridge. */
object WhpuCardWebHandoff {
    fun allows(url: String?): Boolean = url?.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.host == "ecardapp.whpu.edu.cn" && it.port == 443 &&
            it.username.isEmpty() && it.password.isEmpty()
    } == true

    val script = """
        (() => {
          if (location.origin !== 'https://ecardapp.whpu.edu.cn') return null;
          const token = sessionStorage.getItem('access_token');
          if (!token) return null;
          return JSON.stringify({token: token, type: sessionStorage.getItem('token_type') || 'bearer'});
        })()
    """.trimIndent()

    class Credentials internal constructor(val token: String, val type: String)

    fun decodeCallback(value: String): Credentials = WhpuCardParser.parse {
        val outer = Json.parseToJsonElement(value)
        if (outer == JsonNull) throw WhpuCardException.SessionExpired()
        val payload = (outer as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid callback")
        val data = Json.parseToJsonElement(payload).jsonObject
        val token = (data["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing token")
        val type = (data["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing type")
        require(token.isNotBlank() && token.none { it.isWhitespace() || it.isISOControl() })
        require(type.equals("bearer", ignoreCase = true))
        Credentials(token, type)
    }
}
