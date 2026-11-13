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
import kotlinx.serialization.json.Json

fun interface WhpuCardSource {
    suspend fun fetch(): WhpuCardSnapshot
}

@Serializable
data class WhpuCardCache(
    val owner: WhpuCardOwner,
    val cards: List<WhpuCard>,
    val transactions: List<WhpuCardTransaction>,
    val selectedCardAccount: String?,
    val fetchedAt: Long,
) {
    val selectedCard: WhpuCard? get() = cards.find { it.account == selectedCardAccount }
    fun transactionsFor(cardAccount: String): List<WhpuCardTransaction> {
        require(cards.any { it.account == cardAccount })
        return transactions.filter { it.cardAccount == cardAccount }
    }
}

/** One complete account snapshot per row. The pointer and snapshot change in the same transaction. */
class WhpuCardRepository(
    private val db: AppDatabase,
    private val source: WhpuCardSource,
    private val session: WhpuCardSession,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.kvCacheDao()
    private val refreshMutex = Mutex()

    fun observeCurrent() = dao.observeLinked(ACTIVE_KEY).map { it?.decode() }
    suspend fun current(): WhpuCardCache? = dao.getLinked(ACTIVE_KEY)?.decode()
    suspend fun cached(owner: WhpuCardOwner): WhpuCardCache? = dao.get(key(owner))?.decode()

    suspend fun refresh(): WhpuCardCache = refreshMutex.withLock {
        val revision = session.currentRevision()
        val result = source.fetch()
        if (result.sessionRevision != revision) throw WhpuCardException.SessionChanged()
        validate(result)
        session.withCurrent(revision) {
            db.withTransaction {
                currentCoroutineContext().ensureActive()
                val previous = cached(result.owner)
                val selected = previous?.selectedCardAccount?.takeIf { account ->
                    result.cards.any { it.account == account }
                } ?: result.cards.firstOrNull()?.account
                val cache = WhpuCardCache(result.owner, result.cards, result.transactions, selected, now())
                val target = key(cache.owner)
                dao.put(KvCacheEntity(target, Json.encodeToString(cache), cache.fetchedAt, 0))
                dao.put(KvCacheEntity(ACTIVE_KEY, target, cache.fetchedAt, 0))
                currentCoroutineContext().ensureActive()
                cache
            }
        }
    }

    suspend fun selectCard(owner: WhpuCardOwner, cardAccount: String) = db.withTransaction {
        val target = key(owner)
        if (dao.get(ACTIVE_KEY)?.payload != target) throw WhpuCardException.SessionChanged()
        val cache = requireNotNull(cached(owner))
        require(cache.cards.any { it.account == cardAccount })
        dao.put(KvCacheEntity(target, Json.encodeToString(cache.copy(selectedCardAccount = cardAccount)), cache.fetchedAt, 0))
    }

    /** Hide the old account during an explicit login switch; retain its offline snapshot. */
    suspend fun deactivate() = dao.remove(ACTIVE_KEY)

    suspend fun clear(owner: WhpuCardOwner) = db.withTransaction {
        val target = key(owner)
        dao.remove(target)
        if (dao.get(ACTIVE_KEY)?.payload == target) dao.remove(ACTIVE_KEY)
    }

    private fun KvCacheEntity.decode(): WhpuCardCache = Json.decodeFromString(payload)

    private fun key(owner: WhpuCardOwner): String {
        require(owner.id.toLongOrNull()?.let { it > 0 } == true)
        return "whpu-card:owner:${owner.id}"
    }

    private fun validate(snapshot: WhpuCardSnapshot) {
        key(snapshot.owner)
        val accounts = snapshot.cards.map { it.account }.toSet()
        require(accounts.size == snapshot.cards.size && accounts.all { it.isNotBlank() })
        require(snapshot.transactions.all { it.cardAccount in accounts && it.orderId.isNotBlank() })
        require(snapshot.transactions.map { it.cardAccount to it.orderId }.toSet().size == snapshot.transactions.size)
    }

    companion object {
        internal const val ACTIVE_KEY = "whpu-card:active"
    }
}
