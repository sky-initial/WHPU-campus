package com.zzupocket.app.ui.campus

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces the short dialog body available on a landscape phone at 2x font. */
@RunWith(AndroidJUnit4::class)
class ElectricityGroupOptionsTest {
    @Test fun allGroupsAreReadableAndSelectableWithLargeFontInLightTheme() = checkGroups(false)
    @Test fun allGroupsAreReadableAndSelectableWithLargeFontInDarkTheme() = checkGroups(true)

    private fun checkGroups(dark: Boolean) {
        val groups = listOf("其他楼栋" to "182", "金银湖 6、7、8 栋照明" to "201", "常青 1 号楼、金湖 10、11 栋" to "1")
        val heights = AtomicReference<Map<String, Int>>(emptyMap())
        val selected = AtomicReference<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                        val measurer = rememberTextMeasurer()
                        // The 272dp button has 24dp horizontal content padding on each side.
                        val textWidth = with(LocalDensity.current) { 224.dp.roundToPx() }
                        val expected = groups.associate { (label, _) -> label to measurer.measure(
                            AnnotatedString(label), style = MaterialTheme.typography.labelLarge,
                            constraints = Constraints(maxWidth = textWidth),
                        ).size.height }
                        SideEffect { heights.set(expected) }
                        Surface(Modifier.fillMaxSize()) {
                            Box {
                                Box(Modifier.width(272.dp).height(180.dp).clipToBounds()) {
                                    ElectricityGroupOptions(selected::set)
                                }
                            }
                        }
                    }
                }
            } }
            repeat(50) {
                if (heights.get().isNotEmpty()) return@repeat
                Thread.sleep(100)
            }
            assertEquals(groups.size, heights.get().size)
            for ((label, id) in groups) {
                val node = awaitFullText(label, heights.get().getValue(label))
                val parent = node.parent
                try {
                    val clicked = node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        parent?.isClickable == true && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    assertTrue("Group must be clickable: $label", clicked)
                } finally { parent?.recycle(); node.recycle() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(id, selected.get())
            }
        }
    }

    private fun awaitFullText(label: String, requiredHeight: Int): AccessibilityNodeInfo {
        repeat(12) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    val node = findText(root, label)
                    if (node != null) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (node.isVisibleToUser && bounds.height() >= requiredHeight) return node
                        node.recycle()
                    }
                    scrollForward(root)
                } finally { root.recycle() }
            }
            Thread.sleep(150)
        }
        throw AssertionError("Full group text cannot be reached: $label (requires $requiredHeight px)")
    }

    private fun findText(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { findText(child, label)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    private fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (scrollForward(child)) return true } finally { child.recycle() }
        }
        return false
    }
}
