package com.zzupocket.app.data.whpu

import androidx.room.withTransaction
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.KvCacheEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.math.BigDecimal

fun interface WhpuElectricitySource {
    suspend fun fetch(room: WhpuElectricityRoom): WhpuElectricitySnapshot
}

@Serializable data class WhpuElectricityReading(val room: WhpuElectricityRoom, val value: WhpuElectricityValue, val fetchedAt: Long)
@Serializable data class WhpuElectricityCache(
    val owner: WhpuCardOwner,
    val readings: List<WhpuElectricityReading>,
    val selectedRoom: WhpuElectricityRoom?,
) {
    val selected: WhpuElectricityReading? get() = readings.find { it.room == selectedRoom }
}

/** Account snapshots contain independent meter/room readings. Old energy samples are never adopted. */
class WhpuElectricityRepository(
    private val db: AppDatabase,
    private val source: WhpuElectricitySource,
    private val session: WhpuCardSession,
    private val now: () -> Long = System::currentTimeMillis,
    private val onOwnerVerified: suspend (WhpuCardOwner) -> Unit = {},
) {
    private val dao = db.kvCacheDao()
    private val refreshMutex = Mutex()

    fun observeCurrent() = dao.observeLinked(ACTIVE_KEY).map { it?.decode() }
    suspend fun current(): WhpuElectricityCache? = dao.getLinked(ACTIVE_KEY)?.decode()
    suspend fun cached(owner: WhpuCardOwner): WhpuElectricityCache? = dao.get(key(owner))?.decode()

    suspend fun refresh(room: WhpuElectricityRoom, isCurrent: () -> Boolean = { true }): WhpuElectricityCache = refreshMutex.withLock {
        val revision = session.currentRevision()
        val result = source.fetch(room)
        if (result.sessionRevision != revision || !isCurrent()) throw WhpuElectricityException.SessionChanged()
        if (result.room != room) throw WhpuElectricityException.Parse()
        WhpuElectricityParser.requestContext(room)
        try {
            session.withCurrent(revision) {
                db.withTransaction {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) throw WhpuElectricityException.SessionChanged()
                    val previous = cached(room.owner)
                    val reading = WhpuElectricityReading(room, result.value, now())
                    val readings = previous?.readings.orEmpty().filterNot { it.room == room } + reading
                    val cache = WhpuElectricityCache(room.owner, readings, room)
                    val target = key(room.owner)
                    dao.put(KvCacheEntity(target, Json.encodeToString(cache), reading.fetchedAt, 0))
                    dao.put(KvCacheEntity(ACTIVE_KEY, target, reading.fetchedAt, 0))
                    onOwnerVerified(room.owner)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) throw WhpuElectricityException.SessionChanged()
                    cache
                }
            }
        } catch (_: WhpuCardException.SessionChanged) { throw WhpuElectricityException.SessionChanged() }
    }

    suspend fun select(room: WhpuElectricityRoom) = db.withTransaction {
        val target = key(room.owner)
        if (dao.get(ACTIVE_KEY)?.payload != target) throw WhpuElectricityException.SessionChanged()
        val cache = requireNotNull(cached(room.owner))
        val reading = requireNotNull(cache.readings.find { it.room == room })
        dao.put(KvCacheEntity(target, Json.encodeToString(cache.copy(selectedRoom = room)), reading.fetchedAt, 0))
    }

    /** Call only after the shared campus login has established this owner. */
    suspend fun activateOwner(owner: WhpuCardOwner) = db.withTransaction {
        val target = key(owner)
        val cache = dao.get(target)
        if (cache == null) dao.remove(ACTIVE_KEY)
        else dao.put(KvCacheEntity(ACTIVE_KEY, target, cache.fetchedAt, 0))
    }

    suspend fun acceptOwner(owner: WhpuCardOwner) = db.withTransaction {
        activateOwner(owner)
        onOwnerVerified(owner)
    }

    suspend fun deactivate() = dao.remove(ACTIVE_KEY)

    suspend fun clear(owner: WhpuCardOwner) = db.withTransaction {
        val target = key(owner)
        dao.remove(target)
        if (dao.get(ACTIVE_KEY)?.payload == target) dao.remove(ACTIVE_KEY)
    }

    private fun key(owner: WhpuCardOwner): String {
        require(owner.id.toLongOrNull()?.let { it > 0 } == true)
        return "whpu-electricity:owner:${owner.id}"
    }
    private fun KvCacheEntity.decode(): WhpuElectricityCache = Json.decodeFromString(ElectricityCacheSerializer, payload)

    companion object { internal const val ACTIVE_KEY = "whpu-electricity:active" }
}

/** Older fen/yuan readings remain money; preserve their selection, timestamps and decimal precision. */
private object ElectricityCacheSerializer : JsonTransformingSerializer<WhpuElectricityCache>(WhpuElectricityCache.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val cache = element.jsonObject
        val readings = cache.getValue("readings").jsonArray.map { value ->
            val reading = value.jsonObject
            if ("value" in reading) reading else {
                val yuan = reading["balanceYuan"]?.jsonPrimitive?.content
                    ?: BigDecimal.valueOf(reading.getValue("balanceFen").jsonPrimitive.long, 2).toPlainString()
                val amount = buildJsonObject {
                    put("number", yuan)
                    put("kind", WhpuElectricityReadingKind.YUAN.name)
                }
                JsonObject(reading.filterKeys { it != "balanceFen" && it != "balanceYuan" } + ("value" to amount))
            }
        }
        return JsonObject(cache + ("readings" to JsonArray(readings)))
    }
}
