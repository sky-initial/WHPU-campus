package com.zzupocket.app.data.whpu

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.EnergyReadingEntity
import com.zzupocket.app.data.db.KvCacheEntity
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private fun lightingRoom(leaf: String = "test-room"): WhpuElectricityRoom {
        val general = room(leaf = leaf)
        return general.copy(feeItemId = WHPU_JINYINHU_LIGHTING,
            steps = general.steps.drop(1).mapIndexed { index, step ->
                step.copy(field = step.field.copy(level = index + 1))
            })
    }
    private fun schoolRoom() = WhpuElectricityRoom(ownerA, WHPU_CHANGQING_JINHU_ELECTRICITY, listOf(
        Triple("campus", "公寓", "10001"), Triple("building", "楼层", "20002"),
        Triple("room", "房间", "30003"),
    ).mapIndexed { index, (code, label, id) ->
        WhpuElectricityStep(WhpuElectricityLevel(code, label, index + 1), WhpuElectricityOption("人工$label", id))
    })
    private fun yuan(number: String) = WhpuElectricityValue(number, WhpuElectricityReadingKind.YUAN)
    private fun repo(amount: String = "12.34", at: Long = 1000L, source: WhpuElectricitySource? = null) =
        WhpuElectricityRepository(db, source ?: WhpuElectricitySource {
            WhpuElectricitySnapshot(session.currentRevision(), it, yuan(amount))
        }, session, now = { at })

    @Test fun multipleMeterBalancesAndSelectedRoomSurviveReopen() = runBlocking {
        val light = room(); val ac = room(meter = "ac")
        repo().refresh(light); repo(amount = "56.78", at = 2000).refresh(ac); repo().select(light)
        val before = repo().current()
        db.close(); open()
        assertEquals(before, repo().current())
        assertEquals(before, repo().observeCurrent().first())
        assertEquals(yuan("12.34"), before!!.selected!!.value)
        assertEquals(2, before.readings.size)
        assertEquals(1000L, before.selected!!.fetchedAt)
    }

    @Test fun sameLeafInDifferentGroupsRetainsIndependentBalancesAndSelectionAfterReopen() = runBlocking {
        val general = room(leaf = "30003")
        val lighting = lightingRoom(leaf = "30003")
        val school = schoolRoom()
        assertEquals(general.steps.last().option, lighting.steps.last().option)
        assertEquals(general.steps.last().option.name, school.steps.last().option.name)
        assertEquals(general.steps.last().option.value.substringBefore('&'), school.steps.last().option.value)
        val originalGeneral = repo().refresh(general).selected!!
        repo(amount = "12.3456", at = 2000L).refresh(lighting)
        val schoolReading = repo(amount = "12.3456", at = 2500L).refresh(school).selected!!
        val before = repo().current()!!
        assertEquals(3, before.readings.size)
        assertEquals(school, before.selectedRoom)
        assertEquals(yuan("12.3456"), before.selected!!.value)
        assertEquals(yuan("12.3456"), before.readings.single { it.room == lighting }.value)

        db.close(); open()
        assertEquals(before, repo().current())
        assertEquals(before, repo().observeCurrent().first())

        val refreshed = repo(amount = "-0.0001", at = 3000L).refresh(lighting)
        assertEquals(3, refreshed.readings.size)
        assertEquals(originalGeneral, refreshed.readings.single { it.room == general })
        assertEquals(schoolReading, refreshed.readings.single { it.room == school })
        assertEquals(yuan("-0.0001"), refreshed.selected!!.value)
        assertEquals(3000L, refreshed.selected!!.fetchedAt)
        repo().select(general)
        assertEquals(originalGeneral, repo().observeCurrent().first()!!.selected)
        repo().select(lighting)
        assertEquals(refreshed, repo().observeCurrent().first())
        repo().select(school)

        db.close(); open()
        assertEquals(refreshed.copy(selectedRoom = school), repo().observeCurrent().first())
        assertEquals(schoolReading, repo().current()!!.selected)
    }

    @Test fun sameRoomIdentityOnDifferentAccountsNeverMixes() = runBlocking {
        for (selectedRoom in listOf(room(), schoolRoom())) {
            val first = repo().refresh(selectedRoom)
            val second = repo(amount = "9.00").refresh(selectedRoom.copy(owner = ownerB))
            assertEquals(first, repo().cached(ownerA)); assertEquals(second, repo().cached(ownerB))
            assertEquals(second, repo().current())
            assertEquals(WhpuElectricityReadingKind.YUAN, second.selected!!.value.kind)
            assertTrue(second.readings.all { it.room.owner == ownerB })
        }
    }

    @Test fun legacyFenSnapshotKeepsItsSelectionAndDatesWhenAddingPreciseLightingBalance() = runBlocking {
        val general = room()
        val ac = room(meter = "ac")
        val ownerJson = Json.encodeToString(ownerA)
        val generalJson = Json.encodeToString(general)
        val acJson = Json.encodeToString(ac)
        val target = "whpu-electricity:owner:${ownerA.id}"
        val oldPayload = """{"owner":$ownerJson,"readings":[
            {"room":$generalJson,"balanceFen":1234,"fetchedAt":1000},
            {"room":$acJson,"balanceFen":-1,"fetchedAt":900}
            ],"selectedRoom":$generalJson}"""
        db.kvCacheDao().put(KvCacheEntity(target, oldPayload, 1000L, 0L))
        db.kvCacheDao().put(KvCacheEntity(WhpuElectricityRepository.ACTIVE_KEY, target, 1000L, 0L))
        db.close(); open()

        val migrated = repo().current()!!
        assertEquals(ownerA, migrated.owner)
        assertEquals(general, migrated.selectedRoom)
        assertEquals(yuan("12.34"), migrated.selected!!.value)
        assertEquals(1000L, migrated.selected!!.fetchedAt)
        val oldAc = migrated.readings.single { it.room == ac }
        assertEquals(yuan("-0.01"), oldAc.value)
        assertEquals(900L, oldAc.fetchedAt)
        assertEquals(migrated, repo().observeCurrent().first())

        val lighting = lightingRoom()
        val updated = repo(amount = "12.3456", at = 2000L).refresh(lighting)
        assertEquals(3, updated.readings.size)
        assertEquals(migrated.readings, updated.readings.filter { it.room != lighting })
        assertEquals(yuan("12.3456"), updated.selected!!.value)
        assertEquals(2000L, updated.selected!!.fetchedAt)
        val saved = db.kvCacheDao().get(target)!!.payload
        assertFalse(saved.contains("\"balanceYuan\""))
        assertFalse(saved.contains("\"balanceFen\""))
        repo().select(general)
        db.close(); open()
        val reopened = repo().observeCurrent().first()!!
        assertEquals(general, reopened.selectedRoom)
        assertEquals(migrated.selected, reopened.selected)
        assertEquals(updated.readings, reopened.readings)
    }

    @Test fun legacyYuanSnapshotKeepsPrecisionAndSelectionAlongsideThirdGroup() = runBlocking {
        val lighting = lightingRoom()
        val target = "whpu-electricity:owner:${ownerA.id}"
        val roomJson = Json.encodeToString(lighting)
        val oldPayload = """{"owner":${Json.encodeToString(ownerA)},"readings":[
            {"room":$roomJson,"balanceYuan":"-0.0001","fetchedAt":1500}
            ],"selectedRoom":$roomJson}"""
        db.kvCacheDao().put(KvCacheEntity(target, oldPayload, 1500L, 0L))
        db.kvCacheDao().put(KvCacheEntity(WhpuElectricityRepository.ACTIVE_KEY, target, 1500L, 0L))
        db.close(); open()

        val migrated = repo().observeCurrent().first()!!
        assertEquals(ownerA, migrated.owner)
        assertEquals(lighting, migrated.selectedRoom)
        assertEquals(yuan("-0.0001"), migrated.selected!!.value)
        assertEquals(1500L, migrated.selected!!.fetchedAt)
        assertEquals(oldPayload, db.kvCacheDao().get(target)!!.payload)

        val school = schoolRoom()
        val updated = repo(amount = "12.3456", at = 2000L).refresh(school)
        assertEquals(2, updated.readings.size)
        assertEquals(migrated.selected, updated.readings.single { it.room == lighting })
        assertEquals(yuan("12.3456"), updated.selected!!.value)
        assertEquals(2000L, updated.selected!!.fetchedAt)
        val saved = db.kvCacheDao().get(target)!!.payload
        assertFalse(saved.contains("\"balanceYuan\""))
        assertFalse(saved.contains("\"balanceFen\""))
        repo().select(lighting)
        db.close(); open()
        assertEquals(updated.copy(selectedRoom = lighting), repo().current())
        assertEquals(migrated.selected, repo().current()!!.selected)
    }

    @Test fun legacySchoolRemainingReadsAsYuanAndSelectionWritesExactValueWithoutFetching() = runBlocking {
        val school = schoolRoom()
        val target = "whpu-electricity:owner:${ownerA.id}"
        val roomJson = Json.encodeToString(school)
        val oldPayload = """{"owner":${Json.encodeToString(ownerA)},"readings":[
            {"room":$roomJson,"value":{"number":"-0.000100","kind":"SCHOOL_REMAINING"},"fetchedAt":1500}
            ],"selectedRoom":$roomJson}"""
        val originalEntry = KvCacheEntity(target, oldPayload, 1500L, 0L)
        val originalPointer = KvCacheEntity(WhpuElectricityRepository.ACTIVE_KEY, target, 1500L, 0L)
        db.kvCacheDao().put(originalEntry)
        db.kvCacheDao().put(originalPointer)
        db.close(); open()

        val noFetch = WhpuElectricitySource { error("Cache compatibility must not fetch") }
        val expected = WhpuElectricityCache(ownerA,
            listOf(WhpuElectricityReading(school, yuan("-0.000100"), 1500L)), school)
        assertEquals(expected, repo(source = noFetch).current())
        assertEquals(expected, repo(source = noFetch).observeCurrent().first())
        assertEquals(originalEntry, db.kvCacheDao().get(target))
        assertEquals(originalPointer, db.kvCacheDao().get(WhpuElectricityRepository.ACTIVE_KEY))
        db.close(); open()
        assertEquals(expected, repo(source = noFetch).current())
        assertEquals(originalEntry, db.kvCacheDao().get(target))

        repo(at = 9999L, source = noFetch).select(school)
        val saved = db.kvCacheDao().get(target)!!
        assertEquals(expected, Json.decodeFromString(WhpuElectricityCache.serializer(), saved.payload))
        assertEquals(1500L, saved.fetchedAt)
        assertEquals(0L, saved.ttlSeconds)
        assertEquals(originalPointer, db.kvCacheDao().get(WhpuElectricityRepository.ACTIVE_KEY))
        db.close(); open()
        assertEquals(expected, repo(source = noFetch).observeCurrent().first())
    }

    @Test fun legacyKindCorrectionIsLimitedToThirdGroupAndRefreshPreservesOtherReading() = runBlocking {
        val school = schoolRoom()
        val lighting = lightingRoom()
        val target = "whpu-electricity:owner:${ownerA.id}"
        val schoolJson = Json.encodeToString(school)
        val lightingJson = Json.encodeToString(lighting)
        val oldPayload = """{"owner":${Json.encodeToString(ownerA)},"readings":[
            {"room":$schoolJson,"value":{"number":"0012.3400","kind":"SCHOOL_REMAINING"},"fetchedAt":1500},
            {"room":$lightingJson,"value":{"number":"4.567800","kind":"SCHOOL_REMAINING"},"fetchedAt":1400}
            ],"selectedRoom":$lightingJson}"""
        db.kvCacheDao().put(KvCacheEntity(target, oldPayload, 1500L, 0L))
        db.kvCacheDao().put(KvCacheEntity(WhpuElectricityRepository.ACTIVE_KEY, target, 1500L, 0L))
        db.close(); open()

        val migrated = repo().current()!!
        val untouched = WhpuElectricityReading(lighting,
            WhpuElectricityValue("4.567800", WhpuElectricityReadingKind.SCHOOL_REMAINING), 1400L)
        assertEquals(ownerA, migrated.owner)
        assertEquals(lighting, migrated.selectedRoom)
        assertEquals(untouched, migrated.selected)
        assertEquals(WhpuElectricityReading(school, yuan("0012.3400"), 1500L),
            migrated.readings.single { it.room == school })
        assertEquals(oldPayload, db.kvCacheDao().get(target)!!.payload)

        val refreshed = repo(amount = "7.0000", at = 2500L).refresh(school)
        assertEquals(school, refreshed.selectedRoom)
        assertEquals(WhpuElectricityReading(school, yuan("7.0000"), 2500L), refreshed.selected)
        assertEquals(untouched, refreshed.readings.single { it.room == lighting })
        val saved = Json.decodeFromString(WhpuElectricityCache.serializer(), db.kvCacheDao().get(target)!!.payload)
        assertEquals(refreshed, saved)
        db.close(); open()
        assertEquals(refreshed, repo().observeCurrent().first())
    }

    @Test fun zeroRefreshReplacesOnlyTheRequestedRoomAndPreservesOtherMeters() = runBlocking {
        val light = room(); val ac = room(meter = "ac"); val other = room(leaf = "another-room")
        repo().refresh(light); repo(amount = "5.00").refresh(ac); repo(amount = "3.00").refresh(other)
        val result = repo(amount = "0.00", at = 2000).refresh(light)
        assertEquals(3, result.readings.size)
        assertEquals(yuan("0.00"), result.selected!!.value)
        assertEquals(2000L, result.selected!!.fetchedAt)
        assertEquals(yuan("5.00"), result.readings.single { it.room == ac }.value)
        assertEquals(yuan("3.00"), result.readings.single { it.room == other }.value)
    }

    @Test fun failureAndCancellationPreserveAmountSelectionAndSuccessfulTime() = runBlocking {
        for (selectedRoom in listOf(room(), schoolRoom())) {
            val before = repo().refresh(selectedRoom)
            for (failure in listOf(WhpuElectricityException.SessionExpired(), WhpuElectricityException.Parse(),
                WhpuElectricityException.Unavailable(), CancellationException())) {
                assertTrue(runCatching { repo(at = 2000, source = WhpuElectricitySource { throw failure }).refresh(selectedRoom) }.isFailure)
                assertEquals(before, repo().current())
            }
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
            WhpuElectricitySnapshot(revision, it, yuan("9.00"))
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
            WhpuElectricitySnapshot(session.currentRevision(), it, yuan("9.99"))
        }, session, now = { task.cancel(); 2000L })
        task = async(start = CoroutineStart.LAZY) { repository.refresh(room(ownerB)) }
        task.start(); assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertNull(repo().cached(ownerB)); assertEquals(before, repo().current())
    }

    @Test fun staleNavigationOrDifferentReturnedRoomCannotReplaceSnapshot() = runBlocking {
        val before = repo().refresh(room())
        assertTrue(runCatching { repo().refresh(room()) { false } }.exceptionOrNull() is WhpuElectricityException.SessionChanged)
        val mismatched = repo(source = WhpuElectricitySource {
            WhpuElectricitySnapshot(session.currentRevision(), room(meter = "ac"), yuan("5.00"))
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
            WhpuElectricitySnapshot(session.currentRevision(), it, yuan("12.34"))
        }, session, onOwnerVerified = { card.activateOwner(it) })
        card.refresh(); val first = electricity.refresh(room())
        cardOwner = ownerB; card.refresh()
        assertNull(electricity.current()); assertEquals(first, electricity.cached(ownerA))
        electricity.refresh(room(ownerB)); assertEquals(ownerB, card.current()!!.owner)
        electricity.acceptOwner(ownerA)
        assertEquals(ownerA, card.current()!!.owner); assertEquals(first, electricity.current())
    }
}
