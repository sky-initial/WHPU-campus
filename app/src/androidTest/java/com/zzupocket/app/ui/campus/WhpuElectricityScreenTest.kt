package com.zzupocket.app.ui.campus

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.whpu.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose interactions, synthetic HTTP and an isolated database. No school traffic. */
@RunWith(AndroidJUnit4::class)
class WhpuElectricityScreenTest {
    @Test fun selectionSavesBalanceAndExpiredLoginKeepsItVisible() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "electricity-screen-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val session = WhpuCardSession().apply { accept("synthetic-token", userAgent = "test Android Mobile") }
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val body = if (request.method == "GET") """{"code":200,"success":true,"data":{"id":101}}"""
                else {
                    val form = request.body as FormBody
                    val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
                    if (fields["type"] == "select") directory(fields.getValue("level").toInt()) else balance
                }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val provider = WhpuElectricityProvider(session, client)
        val repository = WhpuElectricityRepository(db, WhpuElectricitySource { provider.balance(it) }, session, now = { 1000L })
        val store = ViewModelStore()
        val loginOpened = AtomicBoolean(false)
        try {
            val model = withContext(Dispatchers.Main) {
                WhpuElectricityViewModel(repository, provider, session, onCacheChanged = {}).also { store.put("test", it) }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { WhpuElectricityScreen(onBack = {}, onLogin = { loginOpened.set(true) }, model = model) }
                } }
                awaitText("尚未保存电费查询结果")
                click("选择房间"); awaitText("查询分组"); click("其他楼栋")
                for (label in listOf("人工校区", "人工照明楼", "人工楼层", "人工房间")) {
                    awaitText(label); click(label)
                }
                awaitText("¥ 12.34"); awaitText("已保存电费查询结果")
                val cache = repository.current()!!
                assertEquals(WhpuElectricityValue("12.34", WhpuElectricityReadingKind.YUAN), cache.selected!!.value)
                assertEquals(4, cache.selectedRoom!!.steps.size)
                assertEquals("room-id&人工房间", cache.selectedRoom!!.steps.last().option.value)
                assertEquals(5, requests.count { it.method == "POST" })
                val calls = requests.size
                session.clear()
                click("刷新所选房间")
                awaitText("校园服务登录已失效")
                awaitText("¥ 12.34")
                assertEquals(cache, repository.current())
                assertEquals(calls, requests.size)
                click("学校登录 / 官方网页")
                assertTrue(loginOpened.get())
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun lightingGroupKeepsItsIdentityWhenReturningToPreviousLevelAndRefreshing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "electricity-lighting-screen-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val session = WhpuCardSession().apply { accept("synthetic-token", userAgent = "test Android Mobile") }
        val forms = CopyOnWriteArrayList<Map<String, String>>()
        val failBalance = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val fields = (request.body as? FormBody)?.let { form ->
                (0 until form.size).associate { form.name(it) to form.value(it) }.also { forms += it }
            }
            val failure = fields?.get("type") == "IEC" && failBalance.get()
            val body = when {
                fields == null -> """{"code":200,"success":true,"data":{"id":101}}"""
                failure -> """{"code":503}"""
                fields["type"] == "select" -> lightingDirectory(fields.getValue("level").toInt())
                else -> lightingBalance
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (failure) 503 else 200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val provider = WhpuElectricityProvider(session, client)
        val repository = WhpuElectricityRepository(db, WhpuElectricitySource { provider.balance(it) }, session, now = { 1000L })
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                WhpuElectricityViewModel(repository, provider, session, onCacheChanged = {}).also { store.put("test", it) }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { WhpuElectricityScreen(onBack = {}, onLogin = {}, model = model) }
                } }
                awaitText("尚未保存电费查询结果")
                click("选择房间"); awaitText("查询分组"); click("金银湖 6、7、8 栋照明")
                awaitText("人工照明楼"); click("人工照明楼")
                awaitText("人工楼层"); click("人工楼层")
                awaitText("人工房间"); click("上一层")
                awaitText("选择楼层"); click("人工楼层")
                awaitText("人工房间"); click("人工房间")
                awaitText("¥ 12.3456"); awaitText("已保存电费查询结果")

                val expected = listOf(
                    lightingForm("select", 0), lightingForm("select", 1), lightingForm("select", 2),
                    lightingForm("select", 1), lightingForm("select", 2), lightingForm("IEC", 3),
                )
                assertEquals(expected, forms.toList())
                val cached = repository.current()!!
                assertEquals(WHPU_JINYINHU_LIGHTING, cached.selectedRoom!!.feeItemId)
                assertEquals(listOf("building", "floor", "room"), cached.selectedRoom!!.steps.map { it.field.code })
                assertEquals(WhpuElectricityValue("12.3456", WhpuElectricityReadingKind.YUAN), cached.selected!!.value)

                failBalance.set(true)
                click("刷新所选房间")
                awaitText("电费服务暂不可用（HTTP 503）"); awaitText("¥ 12.3456")
                assertEquals(expected + lightingForm("IEC", 3), forms.toList())
                assertEquals(cached, repository.current())
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun remainingGroupUsesNativeSelectionAndKeepsItsUnitlessCacheAfterReturnAndFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "electricity-remaining-screen-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val session = WhpuCardSession().apply { accept("synthetic-token", userAgent = "test Android Mobile") }
        val forms = CopyOnWriteArrayList<Map<String, String>>()
        val failBalance = AtomicBoolean(false)
        val loginOpened = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val fields = (request.body as? FormBody)?.let { form ->
                (0 until form.size).associate { form.name(it) to form.value(it) }.also { forms += it }
            }
            val failure = fields?.get("type") == "IEC" && failBalance.get()
            val body = when {
                fields == null -> """{"code":200,"success":true,"data":{"id":101}}"""
                failure -> """{"code":503}"""
                fields["type"] == "select" -> remainingDirectory(fields.getValue("level").toInt())
                else -> remainingBalance
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (failure) 503 else 200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val provider = WhpuElectricityProvider(session, client)
        val repository = WhpuElectricityRepository(db, WhpuElectricitySource { provider.balance(it) }, session, now = { 1000L })
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                WhpuElectricityViewModel(repository, provider, session, onCacheChanged = {}).also { store.put("test", it) }
            }
            val expected = listOf(remainingForm("select", 0), remainingForm("select", 1),
                remainingForm("select", 2), remainingForm("IEC", 3))
            val cached = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { WhpuElectricityScreen(onBack = {}, onLogin = { loginOpened.set(true) }, model = model) }
                } }
                awaitText("尚未保存电费查询结果")
                click("选择房间"); awaitText("查询分组"); click("常青 1 号楼、金湖 10、11 栋")
                for (label in listOf("人工公寓", "人工楼层", "人工房间")) {
                    awaitText(label); click(label)
                }
                awaitText("已保存电费查询结果")
                assertRemainingVisible()
                assertFalse(loginOpened.get())
                assertEquals(expected, forms.toList())
                val cached = repository.current()!!
                assertEquals(WHPU_CHANGQING_JINHU_ELECTRICITY, cached.selectedRoom!!.feeItemId)
                assertEquals(listOf("campus", "building", "room"), cached.selectedRoom!!.steps.map { it.field.code })
                assertEquals(listOf("11", "22", "33"), cached.selectedRoom!!.steps.map { it.option.value })
                assertEquals(WhpuElectricityValue("12.3456", WhpuElectricityReadingKind.SCHOOL_REMAINING), cached.selected!!.value)
                cached
            }

            // Close the original Activity and its composition before clearing its model, then
            // open a fresh screen that reads the saved result without requesting the school.
            val restored = withContext(Dispatchers.Main) {
                store.clear()
                WhpuElectricityViewModel(repository, provider, session, onCacheChanged = {}).also { store.put("restored", it) }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { WhpuElectricityScreen(onBack = {}, onLogin = { loginOpened.set(true) }, model = restored) }
                } }
                assertEquals(cached, withTimeout(5_000) { restored.cache.first { it?.selected != null } })
                assertRemainingVisible()
                assertEquals(expected, forms.toList())
                assertEquals(cached, repository.current())

                failBalance.set(true)
                click("刷新所选房间")
                awaitText("电费服务暂不可用（HTTP 503）") {
                    "forms=${forms.toList()}; restored message=${restored.message}, busy=${restored.busy}, " +
                        "cache=${restored.cache.value}; original message=${model.message}, busy=${model.busy}"
                }
                assertRemainingVisible()
                assertEquals(expected + remainingForm("IEC", 3), forms.toList())
                assertEquals(cached, repository.current())
                assertFalse(loginOpened.get())
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            db.close(); context.deleteDatabase(name)
        }
    }

    private fun remainingForm(type: String, level: Int): Map<String, String> = buildMap {
        put("feeitemid", WHPU_CHANGQING_JINHU_ELECTRICITY); put("type", type); put("level", level.toString())
        listOf("campus" to "11", "building" to "22", "room" to "33").take(level).forEach { (code, id) -> put(code, id) }
    }

    private fun remainingDirectory(level: Int): String {
        val fields = """{"code":"campus","name":"公寓","level":1},
            {"code":"building","name":"楼层","level":2},{"code":"room","name":"房间","level":3}"""
        val (id, name) = listOf("11" to "人工公寓", "22" to "人工楼层", "33" to "人工房间")[level]
        return """{"code":200,"map":{"total":[$fields],"data":[{"name":"$name","value":"$id"}]}}"""
    }

    private val remainingBalance = """{"code":200,"map":{"showData":{"当前剩余电量":"12.3456"},"data":{
        "campusid":"11","campus":"人工公寓","buildingid":"22","building":"人工楼层",
        "roomid":"33","room":"人工房间"}}}"""

    private fun assertRemainingVisible() {
        awaitText("当前剩余电量"); awaitText("12.3456"); awaitText("学校未标注单位")
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        assertNotNull(root)
        try {
            assertFalse(find(root, "¥", false))
            assertFalse(find(root, "元", false))
            assertFalse(find(root, "度", false))
        } finally { root.recycle() }
    }

    private fun lightingForm(type: String, level: Int): Map<String, String> = buildMap {
        put("feeitemid", WHPU_JINYINHU_LIGHTING); put("type", type); put("level", level.toString())
        listOf("building" to "人工照明楼", "floor" to "人工楼层", "room" to "人工房间")
            .take(level).forEach { (code, label) -> put(code, "$code-id&$label") }
    }

    private fun lightingDirectory(level: Int): String {
        val fields = """{"code":"building","name":"楼栋","level":1},
            {"code":"floor","name":"楼层","level":2},{"code":"room","name":"房间","level":3}"""
        val (code, name) = listOf("building" to "人工照明楼", "floor" to "人工楼层", "room" to "人工房间")[level]
        return """{"code":200,"map":{"total":[$fields],"data":[{"name":"$name","value":"$code-id&$name"}]}}"""
    }

    private val lightingBalance = """{"code":200,"map":{"showData":{"信息":"当前房间当前剩余金额12.3456元"},"data":{
        "building":"building-id","buildingName":"人工照明楼","floor":"floor-id","floorName":"人工楼层",
        "room":"room-id","roomName":"人工房间"}}}"""

    private fun directory(level: Int): String {
        val codes = listOf("campus", "building", "floor", "room")
        val labels = listOf("校区", "楼栋", "楼层", "房间")
        val names = listOf("人工校区", "人工照明楼", "人工楼层", "人工房间")
        val fields = codes.indices.joinToString(",") { """{"code":"${codes[it]}","name":"${labels[it]}","level":${it + 1}}""" }
        return """{"code":200,"map":{"total":[$fields],"data":[{"name":"${names[level]}","value":"${codes[level]}-id&${names[level]}"}]}}"""
    }
    private val balance = """{"code":200,"map":{"showData":{"信息":"当前房间余额为12.34元"},"data":{
        "area":"campus-id","areaName":"人工校区","building":"building-id","buildingName":"人工照明楼",
        "floor":"floor-id","floorName":"人工楼层","room":"room-id","roomName":"人工房间"}}}"""

    private fun awaitText(text: String, diagnostic: () -> String = { "" }) {
        repeat(100) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (root != null) {
                try { if (find(root, text, false)) return } finally { root.recycle() }
            }
            Thread.sleep(100)
        }
        fail("Synthetic UI did not show $text ${diagnostic()}")
    }
    private fun click(text: String) {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        assertNotNull(root)
        try { assertTrue("Synthetic control not clickable: $text", find(root, text, true)) }
        finally { root.recycle() }
    }
    private fun find(node: AccessibilityNodeInfo, text: String, click: Boolean): Boolean {
        if (node.text?.contains(text) == true) {
            if (!click) return true
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val parent = node.parent
            try { if (parent?.isClickable == true && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
            finally { parent?.recycle() }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (find(child, text, click)) return true } finally { child.recycle() }
        }
        return false
    }
}
