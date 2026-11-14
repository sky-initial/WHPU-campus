package com.zzupocket.app.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** Requires user-granted policy/exact-alarm access. Uses synthetic plans and a separate temporary store. */
@RunWith(AndroidJUnit4::class)
class ClassDndSystemTest {
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var controller: ClassDndController
    private lateinit var store: ClassDndStore
    private lateinit var file: File
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var plan = ClassDndPlan(true, Instant.now().plusSeconds(3600), emptyList())
    private var extraRule: String? = null
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("User must grant Do Not Disturb access", notifications.isNotificationPolicyAccessGranted)
        assumeTrue("User must grant exact alarm access", context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        file = File(context.cacheDir, "synthetic-dnd-${UUID.randomUUID()}.preferences_pb")
        store = ClassDndStore(PreferenceDataStoreFactory.create(scope = dataScope, produceFile = { file }))
        controller = ClassDndController(context, store, 5300) { plan }
    }
    @After fun cleanup() = runBlocking {
        if (::controller.isInitialized) controller.setEnabled(false)
        extraRule?.let { notifications.removeAutomaticZenRule(it) }
        dataScope.coroutineContext[Job]?.cancelAndJoin()
        if (::file.isInitialized) file.delete()
        Unit
    }
    private suspend fun awaitState(id: String, state: Int) {
        if (Build.VERSION.SDK_INT >= 35) {
            repeat(30) {
                if (notifications.getAutomaticZenRuleState(id) == state) return
                delay(100)
            }
            assertEquals(state, notifications.getAutomaticZenRuleState(id))
        }
    }
    private suspend fun awaitFilter(expected: Int) {
        repeat(30) {
            if (notifications.currentInterruptionFilter == expected) return
            delay(100)
        }
        assertEquals(expected, notifications.currentInterruptionFilter)
    }
    @Test fun classStartsEndsAndToggleRemovesOnlyItsOwnRule() = runBlocking {
        val original = notifications.currentInterruptionFilter
        controller.setEnabled(true)
        val id = store.current().ruleId!!
        awaitState(id, Condition.STATE_TRUE)
        if (original == NotificationManager.INTERRUPTION_FILTER_ALL) {
            awaitFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        assertEquals(ZenPolicy.STATE_ALLOW, notifications.getAutomaticZenRule(id)!!.zenPolicy!!.priorityCategoryAlarms)
        plan = ClassDndPlan(false, Instant.now().plusSeconds(7200), emptyList())
        controller.refresh()
        awaitState(id, Condition.STATE_FALSE)
        // Assert before removing the rule: removal must not hide a failed class-end transition.
        awaitFilter(original)
        assertTrue(notifications.getAutomaticZenRule(id)!!.isEnabled)
        controller.setEnabled(false)
        assertNull(notifications.getAutomaticZenRule(id))
        assertFalse(store.current().enabled)
        awaitFilter(original)
    }
    @Test fun endingClassKeepsAnotherActiveRule() = runBlocking {
        val uri = Uri.parse("condition://${context.packageName}/synthetic-other")
        @Suppress("DEPRECATION")
        val other = AutomaticZenRule("人工规则测试", null, ComponentName(context, MainActivity::class.java),
            uri, null, NotificationManager.INTERRUPTION_FILTER_ALARMS, true)
        extraRule = notifications.addAutomaticZenRule(other)
        notifications.setAutomaticZenRuleState(extraRule!!, Condition(uri, "人工条件", Condition.STATE_TRUE))
        awaitState(extraRule!!, Condition.STATE_TRUE)
        controller.setEnabled(true)
        awaitState(store.current().ruleId!!, Condition.STATE_TRUE)
        plan = ClassDndPlan(false, null, emptyList())
        controller.refresh()
        awaitState(store.current().ruleId!!, Condition.STATE_FALSE)
        awaitState(extraRule!!, Condition.STATE_TRUE)
        assertTrue(notifications.getAutomaticZenRule(extraRule!!)!!.isEnabled)
        awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    }
    @Test fun reopenedControllerEndsPersistedRuleAfterClass() = runBlocking {
        val original = notifications.currentInterruptionFilter
        controller.setEnabled(true)
        val id = store.current().ruleId!!
        awaitState(id, Condition.STATE_TRUE)
        plan = ClassDndPlan(false, null, emptyList())
        controller = ClassDndController(context, store, 5300) { plan }
        controller.refresh()
        awaitState(id, Condition.STATE_FALSE)
        awaitFilter(original)
        assertTrue(store.current().enabled)
        assertEquals(id, store.current().ruleId)
    }
    @Test fun deletedRuleDoesNotGetRecreatedInBackground() = runBlocking {
        controller.setEnabled(true)
        val id = store.current().ruleId!!
        notifications.removeAutomaticZenRule(id)
        controller.refresh()
        assertFalse(store.current().enabled)
        assertNull(store.current().ruleId)
        assertNull(notifications.getAutomaticZenRule(id))
        controller.setEnabled(false)
    }
    @Test fun closingAfterSystemDeletionDoesNotUpdateMissingRule() = runBlocking {
        controller.setEnabled(true)
        val id = store.current().ruleId!!
        notifications.removeAutomaticZenRule(id)
        controller.setEnabled(false)
        assertFalse(store.current().enabled)
        assertNull(store.current().ruleId)
    }
    @Test fun disabledStaleRuleReferenceIsClearedOnRefresh() = runBlocking {
        controller.setEnabled(true)
        val id = store.current().ruleId!!
        notifications.removeAutomaticZenRule(id)
        store.save(false, id)
        controller.refresh()
        assertNull(store.current().ruleId)
        assertEquals("已关闭", controller.status.value)
    }
    @Test fun systemDisabledRuleStaysDisabledWhenAppRefreshes() = runBlocking {
        controller.setEnabled(true)
        val id = store.current().ruleId!!
        val rule = notifications.getAutomaticZenRule(id)!!
        rule.isEnabled = false
        assertTrue(notifications.updateAutomaticZenRule(id, rule))
        controller.refresh()
        assertFalse(store.current().enabled)
        assertFalse(notifications.getAutomaticZenRule(id)!!.isEnabled)
        assertEquals(id, store.current().ruleId)
    }
}
