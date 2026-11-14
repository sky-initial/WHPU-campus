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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
                awaitText("尚未保存电费余额")
                click("选择房间"); awaitText("查询分组"); click("其他楼栋")
                for (label in listOf("人工校区", "人工照明楼", "人工楼层", "人工房间")) {
                    awaitText(label); click(label)
                }
                awaitText("¥ 12.34"); awaitText("已保存电费余额")
                val cache = repository.current()!!
                assertEquals(1234L, cache.selected!!.balanceFen)
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

    private fun awaitText(text: String) {
        repeat(100) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (root != null) {
                try { if (find(root, text, false)) return } finally { root.recycle() }
            }
            Thread.sleep(100)
        }
        fail("Synthetic UI did not show $text")
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
