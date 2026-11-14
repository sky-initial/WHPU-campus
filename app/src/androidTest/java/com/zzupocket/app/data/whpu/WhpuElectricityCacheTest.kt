package com.zzupocket.app.data.whpu

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.EnergyReadingEntity
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhpuElectricityCacheTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private val session = WhpuCardSession()
    private val ownerA = WhpuCardOwner("101")
    private val ownerB = WhpuCardOwner("202")

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "whpu-electricity-test-${UUID.randomUUID()}.db"
        open(); session.accept("synthetic-token", userAgent = "synthetic-agent")
    }
    private fun open() { db = Room.databaseBuilder(context, AppDatabase::class.java, name).build() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    private fun room(owner: WhpuCardOwner = ownerA, meter: String = "light", leaf: String = "test-room") =
        WhpuElectricityRoom(owner, WHPU_GENERAL_ELECTRICITY, listOf(
            Triple("campus", "校区", "test-campus&人工校区"), Triple("building", "楼栋", "$meter&人工$meter"),
            Triple("floor", "楼层", "test-floor&人工楼层"), Triple("room", "房间", "$leaf&人工房间"),
        ).mapIndexed { index, (code, label, value) ->
            WhpuElectricityStep(WhpuElectricityLevel(code, label, index + 1),
                WhpuElectricityOption(value.substringAfter('&'), value))
        })
    private fun repo(amount: Long = 1234L, at: Long = 1000L, source: WhpuElectricitySource? = null) =
        WhpuElectricityRepository(db, source ?: WhpuElectricitySource {
            WhpuElectricitySnapshot(session.currentRevision(), it, amount)
        }, session, now = { at })

    @Test fun multipleMeterBalancesAndSelectedRoomSurviveReopen() = runBlocking {
        val light = room(); val ac = room(meter = "ac")
        repo().refresh(light); repo(amount = 5678L, at = 2000).refresh(ac); repo().select(light)
        val before = repo().current()
        db.close(); open()
        assertEquals(before, repo().current())
        assertEquals(before, repo().observeCurrent().first())
        assertEquals(1234L, before!!.selected!!.balanceFen)
        assertEquals(2, before.readings.size)
        assertEquals(1000L, before.selected!!.fetchedAt)
    }

    @Test fun sameRoomIdentityOnDifferentAccountsNeverMixes() = runBlocking {
        val first = repo().refresh(room())
        val second = repo(amount = 900L).refresh(room(ownerB))
        assertEquals(first, repo().cached(ownerA)); assertEquals(second, repo().cached(ownerB))
        assertEquals(second, repo().current())
        assertTrue(second.readings.all { it.room.owner == ownerB })
    }

    @Test fun zeroRefreshReplacesOnlyTheRequestedRoomAndPreservesOtherMeters() = runBlocking {
        val light = room(); val ac = room(meter = "ac"); val other = room(leaf = "another-room")
        repo().refresh(light); repo(amount = 500).refresh(ac); repo(amount = 300).refresh(other)
        val result = repo(amount = 0, at = 2000).refresh(light)
        assertEquals(3, result.readings.size)
        assertEquals(0L, result.selected!!.balanceFen)
        assertEquals(2000L, result.selected!!.fetchedAt)
        assertEquals(500L, result.readings.single { it.room == ac }.balanceFen)
        assertEquals(300L, result.readings.single { it.room == other }.balanceFen)
    }

    @Test fun failureAndCancellationPreserveAmountSelectionAndSuccessfulTime() = runBlocking {
        val before = repo().refresh(room())
        for (failure in listOf(WhpuElectricityException.SessionExpired(), WhpuElectricityException.Parse(),
            WhpuElectricityException.Unavailable(), CancellationException())) {
            assertTrue(runCatching { repo(at = 2000, source = WhpuElectricitySource { throw failure }).refresh(room()) }.isFailure)
            assertEquals(before, repo().current())
        }
    }

    @Test fun pointerWriteFailureRollsBackNewAccountSnapshot() = runBlocking {
        val before = repo().refresh(room())
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_electricity_pointer BEFORE INSERT ON kv_cache
            WHEN NEW.key = 'whpu-electricity:active' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            assertTrue(runCatching { repo().refresh(room(ownerB)) }.isFailure)
            assertNull(repo().cached(ownerB)); assertEquals(before, repo().current())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_electricity_pointer") }
    }

    @Test fun sessionChangeDuringFetchCannotCommitOldRoom() = runBlocking {
        val before = repo().refresh(room())
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val repository = repo(source = WhpuElectricitySource {
            val revision = session.currentRevision(); started.complete(Unit); finish.await()
            WhpuElectricitySnapshot(revision, it, 900L)
        })
        val task = async { runCatching { repository.refresh(room(ownerB)) } }
        started.await(); session.accept("changed-synthetic-token", userAgent = "synthetic-agent"); finish.complete(Unit)
        assertTrue(task.await().exceptionOrNull() is WhpuElectricityException.SessionChanged)
        assertNull(repo().cached(ownerB)); assertEquals(before, repo().current())
    }

    @Test fun cancellationInsideTransactionRollsBackReadingAndPointer() = runBlocking {
        val before = repo().refresh(room())
        lateinit var task: Deferred<WhpuElectricityCache>
        val repository = WhpuElectricityRepository(db, WhpuElectricitySource {
            WhpuElectricitySnapshot(session.currentRevision(), it, 999L)
        }, session, now = { task.cancel(); 2000L })
        task = async(start = CoroutineStart.LAZY) { repository.refresh(room(ownerB)) }
        task.start(); assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertNull(repo().cached(ownerB)); assertEquals(before, repo().current())
    }

    @Test fun staleNavigationOrDifferentReturnedRoomCannotReplaceSnapshot() = runBlocking {
        val before = repo().refresh(room())
        assertTrue(runCatching { repo().refresh(room()) { false } }.exceptionOrNull() is WhpuElectricityException.SessionChanged)
        val mismatched = repo(source = WhpuElectricitySource {
            WhpuElectricitySnapshot(session.currentRevision(), room(meter = "ac"), 500L)
        })
        assertTrue(runCatching { mismatched.refresh(room()) }.exceptionOrNull() is WhpuElectricityException.Parse)
        assertEquals(before, repo().current())
    }

    @Test fun oldAccountSelectionCannotActivateItsCache() = runBlocking {
        repo().refresh(room()); val second = repo().refresh(room(ownerB))
        assertTrue(runCatching { repo().select(room()) }.exceptionOrNull() is WhpuElectricityException.SessionChanged)
        assertEquals(second, repo().current())
    }

    @Test fun authenticatedOwnerActivationHidesOtherAccountAndRestoresOnlyMatchingCache() = runBlocking {
        val first = repo().refresh(room())
        repo().activateOwner(ownerB)
        assertNull(repo().current()); assertEquals(first, repo().cached(ownerA))
        repo().activateOwner(ownerA); assertEquals(first, repo().current())
    }

    @Test fun clearingOneOwnerOrDeactivatingPreservesOtherSnapshots() = runBlocking {
        repo().refresh(room()); val second = repo().refresh(room(ownerB))
        repo().clear(ownerA); assertNull(repo().cached(ownerA)); assertEquals(second, repo().current())
        repo().deactivate(); assertNull(repo().current()); assertEquals(second, repo().cached(ownerB))
        repo().activateOwner(ownerB); repo().clear(ownerB); assertNull(repo().current())
    }

    @Test fun legacyEnergySamplesAreNeitherAdoptedNorDeleted() = runBlocking {
        val legacy = EnergyReadingEntity("light", "legacy-room", 1000L, 321.0, 1000L)
        db.energyDao().insert(legacy); assertNull(repo().current())
        repo().refresh(room()); repo().clear(ownerA)
        assertEquals(listOf(legacy), db.energyDao().all())
    }

    @Test fun sharedCampusOwnerSwitchesCardAndElectricityPointersTogether() = runBlocking {
        var cardOwner = ownerA
        lateinit var electricity: WhpuElectricityRepository
        val card = WhpuCardRepository(db, WhpuCardSource {
            WhpuCardSnapshot(session.currentRevision(), cardOwner, emptyList(), emptyList())
        }, session, onOwnerVerified = { electricity.activateOwner(it) })
        electricity = WhpuElectricityRepository(db, WhpuElectricitySource {
            WhpuElectricitySnapshot(session.currentRevision(), it, 1234L)
        }, session, onOwnerVerified = { card.activateOwner(it) })
        card.refresh(); val first = electricity.refresh(room())
        cardOwner = ownerB; card.refresh()
        assertNull(electricity.current()); assertEquals(first, electricity.cached(ownerA))
        electricity.refresh(room(ownerB)); assertEquals(ownerB, card.current()!!.owner)
        electricity.acceptOwner(ownerA)
        assertEquals(ownerA, card.current()!!.owner); assertEquals(first, electricity.current())
    }
}
