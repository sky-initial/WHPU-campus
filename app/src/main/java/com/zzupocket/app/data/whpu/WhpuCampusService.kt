package com.zzupocket.app.data.whpu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class WhpuCampusService(val title: String, val entry: String, val instruction: String) {
    CARD("校园卡", PORTAL_ENTRY, "在融合门户选择“虚拟校园卡”，查看余额与账单。"),
    ELECTRICITY("宿舍电费", PORTAL_ENTRY, "在融合门户选择“虚拟校园卡 → 电费缴纳”，再选择对应楼栋。若学校服务暂时不可用，请稍后重试。"),
    SEAT("座位预约", SEAT_ENTRY, "使用学校官方页面选座、预约和签到。"),
    ;

    fun allows(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val hosts = when (this) {
            CARD, ELECTRICITY -> setOf("e.whpu.edu.cn", "cas.whpu.edu.cn", "sec.whpu.edu.cn", "ecardapp.whpu.edu.cn")
            SEAT -> setOf("office.chaoxing.com", "passport2.chaoxing.com")
        }
        return parsed.scheme == "https" && parsed.port == 443 && parsed.username.isEmpty() &&
            parsed.password.isEmpty() && parsed.host in hosts
    }
}

const val PORTAL_ENTRY = "https://e.whpu.edu.cn/"
const val SEAT_ENTRY = "https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=6ec64fc018ca2c0f&mappId=7521502"
const val LIBRARY_ENTRY = "http://opac.lib.whpu.edu.cn/reader/redr_info.php"

/** The card service routes desktop clients to an unavailable PC electricity page. */
fun campusMobileUserAgent(agent: String): String {
    val browser = agent.replace("; wv", "").replace(" Version/4.0", "")
    // Tablet WebViews omit Mobile; use the same verified mobile portal and card flow.
    return if (browser.contains(" Mobile ")) browser else browser.replace(" Safari/", " Mobile Safari/")
}
