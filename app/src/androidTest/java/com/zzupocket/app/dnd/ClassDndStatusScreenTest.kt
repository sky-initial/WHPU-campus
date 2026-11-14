package com.zzupocket.app.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.service.notification.Condition
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.ui.nav.AppRoutes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A separate temporary rule changes the system mode; never toggles the user's class setting. */
@RunWith(AndroidJUnit4::class)
class ClassDndStatusScreenTest {
    @Test fun anotherRuleIsVisibleWhileClassSwitchIsOffAndUpdatesWithoutReopening() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue(notifications.isNotificationPolicyAccessGranted)
        assumeTrue(notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
        assumeTrue(!AppGraph.classDndController.store.current().enabled)
        val uri = Uri.parse("condition://${context.packageName}/synthetic-screen")
        @Suppress("DEPRECATION")
        val rule = AutomaticZenRule("人工状态显示测试", null, ComponentName(context, MainActivity::class.java),
            uri, null, NotificationManager.INTERRUPTION_FILTER_ALARMS, true)
        val id = notifications.addAutomaticZenRule(rule)
        try {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(AppRoutes.EXTRA_ROUTE, AppRoutes.CLASS_DND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                awaitText("系统免打扰：已关闭")
                notifications.setAutomaticZenRuleState(id, Condition(uri, "人工条件", Condition.STATE_TRUE))
                awaitText("系统免打扰：已开启")
                awaitText("上课自动切换已关闭，系统免打扰仍开启")
                notifications.setAutomaticZenRuleState(id, Condition(uri, "人工条件", Condition.STATE_FALSE))
                awaitText("系统免打扰：已关闭")
                assertTrue(notifications.getAutomaticZenRule(id)!!.isEnabled)
            }
        } finally {
            notifications.removeAutomaticZenRule(id)
        }
    }

    private fun awaitText(text: String) {
        repeat(100) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (root != null) {
                try { if (containsText(root, text)) return }
                finally { root.recycle() }
            }
            Thread.sleep(100)
        }
        throw AssertionError("Missing DND status: $text")
    }

    private fun containsText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.contains(text) == true) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (containsText(child, text)) return true }
            finally { child.recycle() }
        }
        return false
    }
}
