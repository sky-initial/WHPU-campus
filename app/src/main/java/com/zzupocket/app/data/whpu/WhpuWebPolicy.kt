package com.zzupocket.app.data.whpu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object WhpuWebPolicy {
    const val LOGIN = "https://jwglxt.whpu.edu.cn/xtgl/login_slogin.html"
    const val SCHEDULE = "https://jwglxt.whpu.edu.cn/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
    const val GRADES = "https://jwglxt.whpu.edu.cn/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005"
    const val GRADES_PAGE = "https://jwglxt.whpu.edu.cn/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default"
    /** Only the origin is suitable for UI diagnostics; paths and queries may contain CAS tickets. */
    fun navigationOrigin(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return "非 HTTP(S) 跳转"
        return "${parsed.scheme}://${parsed.host}:${parsed.port}"
    }
    fun allows(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.port == 443 && parsed.username.isEmpty() &&
            parsed.password.isEmpty() && parsed.host in setOf("jwglxt.whpu.edu.cn", "cas.whpu.edu.cn", "sec.whpu.edu.cn")
    }
    /** The observed school return URL uses HTTP; consume it through the same host's HTTPS endpoint. */
    fun academicHttpsRedirect(url: String, method: String, isMainFrame: Boolean): String? {
        if (!isMainFrame || method != "GET") return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" || parsed.host != "jwglxt.whpu.edu.cn" || parsed.port != 80 ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return parsed.newBuilder().scheme("https").port(443).build().toString()
    }
    fun isAcademic(url: String): Boolean = allows(url) && url.toHttpUrlOrNull()?.host == "jwglxt.whpu.edu.cn"
}
