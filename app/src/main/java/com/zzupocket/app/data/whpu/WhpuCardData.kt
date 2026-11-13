package com.zzupocket.app.data.whpu

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.json.*

/** Identity comes from the campus-card service, independently of the academic account. */
data class WhpuCardOwner(val id: String)
data class WhpuCard(val account: String, val name: String, val balanceFen: Long)
data class WhpuCardTransaction(
    val cardAccount: String,
    val orderId: String,
    val amountFen: Long,
    val displayedAt: Long,
    val summary: String,
)
data class WhpuCardPage(
    val current: Int, val size: Int, val pages: Int, val total: Int,
    val records: List<WhpuCardTransaction>,
)
data class WhpuCardSnapshot(
    val sessionRevision: Long,
    val owner: WhpuCardOwner,
    val cards: List<WhpuCard>,
    val transactions: List<WhpuCardTransaction>,
)

sealed class WhpuCardException(message: String) : Exception(message) {
    class SessionExpired : WhpuCardException("校园卡登录已失效")
    class SessionChanged : WhpuCardException("校园卡登录状态已变化，请重新同步")
    class Network : WhpuCardException("校园卡网络请求失败")
    class Http(val code: Int) : WhpuCardException("校园卡服务暂不可用（HTTP $code）")
    class Business(val code: Int) : WhpuCardException("校园卡查询未成功（$code）")
    class Parse : WhpuCardException("校园卡数据无法完整解析，原缓存已保留")
}

/** Only verified fields are retained. Raw profiles and error responses never enter the model. */
object WhpuCardParser {
    private val timeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val zone = ZoneId.of("Asia/Shanghai")

    fun owner(body: String): WhpuCardOwner = parse {
        val id = data(body).required("id")
        require(id.toLong() > 0)
        WhpuCardOwner(id)
    }

    fun balanceType(body: String): String = parse {
        val config = Json.parseToJsonElement(data(body).required("getEcardConfig")).jsonObject
        config.required("type").also { require(it in setOf("1", "2")) }
    }

    fun cards(body: String, balanceType: String): List<WhpuCard> = parse {
        require(balanceType in setOf("1", "2"))
        val data = data(body)
        require(data.required("retcode") == "0")
        val cards = (data["card"] as? JsonArray ?: error("Missing card list")).map {
            val row = it.jsonObject
            val balance = if (balanceType == "1") row.minorUnits("elec_accamt") else
                Math.addExact(row.minorUnits("db_balance"), row.minorUnits("unsettle_amount"))
            WhpuCard(row.required("account"), row.text("cardname").orEmpty(), balance)
        }
        require(cards.map { it.account }.toSet().size == cards.size)
        cards
    }

    fun page(body: String, cardAccounts: Set<String>): WhpuCardPage = parse {
        val data = data(body)
        val current = data.required("current").toInt()
        val size = data.required("size").toInt()
        val pages = data.required("pages").toInt()
        val total = data.required("total").toInt()
        require(current >= 1 && size > 0 && pages >= 0 && total >= 0)
        val records = (data["records"] as? JsonArray ?: error("Missing records")).map {
            val row = it.jsonObject
            val account = row.required("fromAccount")
            require(account in cardAccounts)
            val magnitude = row.minorUnits("tranamt")
            require(magnitude >= 0)
            val amount = when (row.required("typeFrom")) {
                "1" -> magnitude
                "2" -> -magnitude
                else -> error("Unsupported direction")
            }
            val time = row.text("effectdateStr") ?: row.required("jndatetimeStr")
            WhpuCardTransaction(account, row.required("orderId"), amount,
                LocalDateTime.parse(time, timeFormat).atZone(zone).toInstant().toEpochMilli(),
                row.text("resume").orEmpty())
        }
        require(records.size <= size)
        WhpuCardPage(current, size, pages, total, records)
    }

    private fun data(body: String): JsonObject {
        val root = Json.parseToJsonElement(body).jsonObject
        val code = root.required("code").toInt()
        if (code == 401) throw WhpuCardException.SessionExpired()
        if (code != 200) throw WhpuCardException.Business(code)
        require((root["success"] as? JsonPrimitive)?.booleanOrNull == true)
        return root["data"] as? JsonObject ?: error("Missing data")
    }

    private fun JsonObject.text(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        else -> error("Invalid field")
    }
    private fun JsonObject.required(key: String): String = requireNotNull(text(key))
    private fun JsonObject.minorUnits(key: String): Long = BigDecimal(required(key)).longValueExact()

    internal inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: WhpuCardException) {
        throw e
    } catch (_: IllegalArgumentException) {
        throw WhpuCardException.Parse()
    } catch (_: IllegalStateException) {
        throw WhpuCardException.Parse()
    } catch (_: java.time.DateTimeException) {
        throw WhpuCardException.Parse()
    } catch (_: ArithmeticException) {
        throw WhpuCardException.Parse()
    }
}

/** Returns a complete snapshot only; neither partial pages nor truncation can be committed. */
internal suspend fun collectWhpuCardPages(
    maxPages: Int = 500,
    load: suspend (Int) -> WhpuCardPage,
): List<WhpuCardTransaction> = WhpuCardParser.parse {
    require(maxPages > 0)
    val first = load(1)
    require(first.current == 1 && first.size > 0 && first.total >= 0)
    if (first.total == 0) {
        require(first.pages == 0 && first.records.isEmpty())
        return@parse emptyList()
    }
    require(first.pages in 1..maxPages)
    require(first.pages.toLong() == (first.total.toLong() + first.size - 1) / first.size)
    val result = ArrayList<WhpuCardTransaction>()
    val keys = mutableSetOf<Pair<String, String>>()
    for (number in 1..first.pages) {
        val page = if (number == 1) first else load(number)
        require(page.current == number && page.size == first.size &&
            page.pages == first.pages && page.total == first.total)
        val expected = if (number < first.pages) first.size.toLong() else
            first.total.toLong() - (number - 1L) * first.size
        require(page.records.size.toLong() == expected)
        page.records.forEach { require(keys.add(it.cardAccount to it.orderId)) }
        result.addAll(page.records)
    }
    result
}
