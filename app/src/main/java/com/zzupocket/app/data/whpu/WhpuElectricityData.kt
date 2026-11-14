package com.zzupocket.app.data.whpu

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Verified public fee item. Other school groups remain available through the official page. */
const val WHPU_GENERAL_ELECTRICITY = "182"

@Serializable data class WhpuElectricityLevel(val code: String, val name: String, val level: Int)
@Serializable data class WhpuElectricityOption(val name: String, val value: String)
@Serializable data class WhpuElectricityStep(val field: WhpuElectricityLevel, val option: WhpuElectricityOption)
@Serializable data class WhpuElectricityRoom(
    val owner: WhpuCardOwner,
    val feeItemId: String,
    val steps: List<WhpuElectricityStep>,
) {
    val label: String get() = steps.joinToString(" · ") { it.option.name }
}

data class WhpuElectricityDirectory(
    val levels: List<WhpuElectricityLevel>,
    val options: List<WhpuElectricityOption>,
)
data class WhpuElectricityDirectorySnapshot(
    val sessionRevision: Long,
    val owner: WhpuCardOwner,
    val feeItemId: String,
    val steps: List<WhpuElectricityStep>,
    val directory: WhpuElectricityDirectory,
)
data class WhpuElectricitySnapshot(val sessionRevision: Long, val room: WhpuElectricityRoom, val balanceFen: Long)

sealed class WhpuElectricityException(message: String) : Exception(message) {
    class SessionExpired : WhpuElectricityException("校园服务登录已失效，请重新登录")
    class SessionChanged : WhpuElectricityException("登录账号已变化，请重新选择房间")
    class Network : WhpuElectricityException("电费网络请求失败，已有缓存保留")
    class Http(val code: Int) : WhpuElectricityException("电费服务暂不可用（HTTP $code）")
    class Business(val code: Int) : WhpuElectricityException("电费查询未成功（$code）")
    class Unavailable : WhpuElectricityException("学校暂未返回该房间余额，已有缓存保留")
    class Parse : WhpuElectricityException("电费数据无法完整解析，已有缓存保留")
}

object WhpuElectricityParser {
    private val balancePattern = Regex("当前房间余额为([+-]?\\d+(?:\\.\\d+)?)元")
    private val contextFields = linkedMapOf(
        "campus" to ("area" to "areaName"),
        "building" to ("building" to "buildingName"),
        "floor" to ("floor" to "floorName"),
        "room" to ("room" to "roomName"),
    )

    fun directory(body: String): WhpuElectricityDirectory = parse {
        val map = response(body)
        val levels = map.getValue("total").jsonArray.map {
            val row = it.jsonObject
            WhpuElectricityLevel(row.text("code"), row.text("name"), row.text("level").toInt())
        }.sortedBy { it.level }
        require(levels.isNotEmpty() && levels.map { it.level } == (1..levels.size).toList())
        require(levels.map { it.code }.toSet().size == levels.size)
        // These names become form keys. They must not replace the protocol parameters.
        require(levels.all { it.code !in setOf("feeitemid", "type", "level") })
        val options = map.getValue("data").jsonArray.map {
            WhpuElectricityOption(it.jsonObject.text("name"), it.jsonObject.text("value"))
        }
        require(options.map { it.value }.toSet().size == options.size)
        WhpuElectricityDirectory(levels, options)
    }

    fun balance(body: String, room: WhpuElectricityRoom): Long = parse {
        val context = requestContext(room)
        val map = response(body)
        if (map["showData"] == null || map["showData"] == JsonNull) {
            if ((map["tipinfo"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) {
                throw WhpuElectricityException.Unavailable()
            }
            error("Missing room result")
        }
        val data = map.getValue("data").jsonObject
        context.forEach { (key, value) -> require(data.text(key) == value) }
        val text = map.getValue("showData").jsonObject.text("信息")
        val amount = requireNotNull(balancePattern.matchEntire(text)).groupValues[1]
        BigDecimal(amount).movePointRight(2).longValueExact()
    }

    internal fun requestContext(room: WhpuElectricityRoom): Map<String, String> = parse {
        require(room.feeItemId == WHPU_GENERAL_ELECTRICITY)
        require(room.steps.map { it.field.code } == contextFields.keys.toList())
        require(room.steps.map { it.field.level } == listOf(1, 2, 3, 4))
        buildMap {
            room.steps.forEach { step ->
                val parts = step.option.value.split('&', limit = 2)
                require(parts.size == 2 && parts[0].isNotBlank() && parts[1] == step.option.name)
                val (idKey, nameKey) = contextFields.getValue(step.field.code)
                put(idKey, parts[0]); put(nameKey, parts[1])
            }
        }
    }

    private fun response(body: String): JsonObject {
        val root = Json.parseToJsonElement(body).jsonObject
        val code = root.text("code").toInt()
        if (code == 401) throw WhpuElectricityException.SessionExpired()
        if (code != 200) throw WhpuElectricityException.Business(code)
        return root.getValue("map").jsonObject
    }

    private fun JsonObject.text(key: String): String =
        requireNotNull((get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })

    internal inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) { throw e
    } catch (e: WhpuElectricityException) { throw e
    } catch (_: IllegalArgumentException) { throw WhpuElectricityException.Parse()
    } catch (_: IllegalStateException) { throw WhpuElectricityException.Parse()
    } catch (_: NoSuchElementException) { throw WhpuElectricityException.Parse()
    } catch (_: ArithmeticException) { throw WhpuElectricityException.Parse() }
}
