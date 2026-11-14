package com.zzupocket.app.data.whpu

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.CardTransactionEntity
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhpuCardCacheTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private val session = WhpuCardSession()
    private val ownerA = WhpuCardOwner("101")
    private val ownerB = WhpuCardOwner("202")

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "whpu-card-test-${UUID.randomUUID()}.db"
        open()
        session.accept("synthetic-token", userAgent = "synthetic-agent")
    }
    private fun open() { db = Room.databaseBuilder(context, AppDatabase::class.java, name).build() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    private fun card(account: String = "card-a", balance: Long = 1000) = WhpuCard(account, "人工卡", balance)
    private fun record(account: String = "card-a", amount: Long = -100) =
        WhpuCardTransaction(account, "same-order", amount, 1767312000000, "人工商户")
    private fun repo(owner: WhpuCardOwner = ownerA, cards: List<WhpuCard> = listOf(card()),
        records: List<WhpuCardTransaction> = listOf(record()), at: Long = 1000,
        source: WhpuCardSource? = null) = WhpuCardRepository(db, source ?: WhpuCardSource {
        WhpuCardSnapshot(session.currentRevision(), owner, cards, records)
    }, session, now = { at })

    @Test fun accountSnapshotSelectionAndTimestampSurviveReopen() = runBlocking {
        val repository = repo(cards = listOf(card(), card("card-b")), records = listOf(record(), record("card-b")))
        repository.refresh()
        repository.selectCard(ownerA, "card-b")
        val before = repository.current()
        db.close(); open()
        assertEquals(before, repo().current())
        assertEquals(before, repo().observeCurrent().first())
        assertEquals("card-b", before!!.selectedCardAccount)
        assertEquals(1000L, before.fetchedAt)
    }
    @Test fun sameCardAndOrderOnDifferentAccountsNeverOverwriteEachOther() = runBlocking {
        val first = repo().refresh()
        val second = repo(ownerB, cards = listOf(card(balance = 2000)), records = listOf(record(amount = -200)), at = 2000).refresh()
        assertEquals(first, repo().cached(ownerA))
        assertEquals(second, repo().cached(ownerB))
        assertEquals(second, repo().current())
        assertEquals(second, repo().observeCurrent().first())
    }
    @Test fun cardSelectionFiltersSameOrderAndPersistsAcrossRefresh() = runBlocking {
        val cards = listOf(card(), card("card-b", 2000))
        val records = listOf(record(), record("card-b", -200))
        val repository = repo(cards = cards, records = records)
        repository.refresh(); repository.selectCard(ownerA, "card-b")
        val refreshed = repo(cards = cards, records = records, at = 2000).refresh()
        assertEquals("card-b", refreshed.selectedCard!!.account)
        assertEquals(listOf(record("card-b", -200)), refreshed.transactionsFor("card-b"))
        assertEquals("card-a", repo().refresh().selectedCardAccount)
    }
    @Test fun failedOrCancelledSourcePreservesSnapshotAndSuccessfulTime() = runBlocking {
        val before = repo().refresh()
        for (failure in listOf(WhpuCardException.SessionExpired(), WhpuCardException.Parse(), CancellationException())) {
            assertTrue(runCatching { repo(at = 2000, source = WhpuCardSource { throw failure }).refresh() }.isFailure)
            assertEquals(before, repo().current())
        }
    }
    @Test fun completeEmptyResultOnlyReplacesItsOwnAccount() = runBlocking {
        repo().refresh()
        val other = repo(ownerB).refresh()
        val empty = repo(cards = emptyList(), records = emptyList(), at = 2000).refresh()
        assertTrue(empty.cards.isEmpty())
        assertTrue(empty.transactions.isEmpty())
        assertNull(empty.selectedCardAccount)
        assertEquals(2000L, empty.fetchedAt)
        assertEquals(other, repo().cached(ownerB))
        assertEquals(empty, repo().current())
    }
    @Test fun pointerWriteFailureRollsBackSnapshotAndActiveAccount() = runBlocking {
        val before = repo().refresh()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_card_pointer BEFORE INSERT ON kv_cache
            WHEN NEW.key = 'whpu-card:active' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            assertTrue(runCatching { repo(ownerB).refresh() }.isFailure)
            assertNull(repo().cached(ownerB))
            assertEquals(before, repo().current())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_card_pointer") }
    }
    @Test fun accountSwitchDuringFetchCannotCommitOldResponse() = runBlocking {
        val before = repo().refresh()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repository = repo(source = WhpuCardSource {
            val revision = session.currentRevision()
            started.complete(Unit); finish.await()
            WhpuCardSnapshot(revision, ownerB, listOf(card()), listOf(record()))
        })
        val refresh = async { runCatching { repository.refresh() } }
        started.await()
        session.accept("changed-synthetic-token", userAgent = "synthetic-agent")
        finish.complete(Unit)
        assertTrue(refresh.await().exceptionOrNull() is WhpuCardException.SessionChanged)
        assertEquals(before, repo().current())
        assertNull(repo().cached(ownerB))
    }
    @Test fun cancellationInsideTransactionDoesNotPublishNewSnapshot() = runBlocking {
        val before = repo().refresh()
        lateinit var task: Deferred<WhpuCardCache>
        val repository = WhpuCardRepository(db, WhpuCardSource {
            WhpuCardSnapshot(session.currentRevision(), ownerB, listOf(card()), listOf(record()))
        }, session, now = { task.cancel(); 2000L })
        task = async(start = CoroutineStart.LAZY) { repository.refresh() }
        task.start()
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertNull(repo().cached(ownerB))
        assertEquals(before, repo().current())
    }
    @Test fun staleAccountSelectionCannotChangeCurrentAccount() = runBlocking {
        repo().refresh(); val second = repo(ownerB).refresh()
        assertTrue(runCatching { repo().selectCard(ownerA, "card-a") }.exceptionOrNull() is WhpuCardException.SessionChanged)
        assertEquals(second, repo().current())
    }
    @Test fun clearIsScopedAndDeactivatePreservesOfflineSnapshots() = runBlocking {
        repo().refresh(); val second = repo(ownerB).refresh()
        repo().clear(ownerA)
        assertNull(repo().cached(ownerA))
        assertEquals(second, repo().current())
        repo().deactivate()
        assertNull(repo().current())
        assertEquals(second, repo().cached(ownerB))
        repo(ownerB).refresh(); repo().clear(ownerB)
        assertNull(repo().current())
    }
    @Test fun oldUnownedTransactionsAreNeitherAdoptedNorDeleted() = runBlocking {
        val legacy = CardTransactionEntity("legacy-order", "人工历史", "人工商户", "2026-01-02 08:00:00",
            1767312000000, -1.0, false, "legacy", 1)
        db.cardTransactionDao().upsertAll(listOf(legacy))
        assertNull(repo().current())
        val fresh = repo().refresh()
        assertFalse(fresh.transactions.any { it.orderId == "legacy-order" })
        repo().clear(ownerA)
        assertEquals(listOf(legacy), db.cardTransactionDao().all())
    }
    @Test fun invalidOwnershipCannotReplaceCachedResult() = runBlocking {
        val before = repo().refresh()
        assertTrue(runCatching { repo(records = listOf(record("unowned-card"))).refresh() }.isFailure)
        assertTrue(runCatching { repo(records = listOf(record(), record())).refresh() }.isFailure)
        assertEquals(before, repo().current())
    }
    @Test fun navigationChangedBeforeCommitKeepsPreviousSnapshot() = runBlocking {
        val before = repo().refresh()
        assertTrue(runCatching { repo(ownerB).refresh { false } }.exceptionOrNull() is WhpuCardException.SessionChanged)
        assertEquals(before, repo().current())
        assertNull(repo().cached(ownerB))
    }
}
