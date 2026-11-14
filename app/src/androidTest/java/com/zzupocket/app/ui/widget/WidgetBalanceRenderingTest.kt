package com.zzupocket.app.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.R
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.nav.AppRoutes
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetBalanceRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun unavailableElectricityFitsNarrowAndNormalRemoteViewsInBothThemes() {
        instrumentation.runOnMainSync {
            for (mode in listOf(DarkMode.LIGHT, DarkMode.DARK)) {
                for (width in listOf(250, 340)) {
                    val colors = WidgetStyle.colors(context, UiPrefs(darkMode = mode))
                    val plan = WidgetBalanceBundle(36.5, null, 3.2, 87.6, 5, 0).plan()
                    val rv = EnergyCardWidget.build(context, plan, colors,
                        Widgets.openApp(context, AppRoutes.ELECTRICITY),
                        Widgets.openApp(context, AppRoutes.CARD),
                        Widgets.openApp(context, AppRoutes.TODAY), 165)
                    val view = rv.apply(context, null)
                    layout(view, width, 165)
                    for ((id, expected) in listOf(R.id.value_1 to "未查询", R.id.value_2 to "打开")) {
                        val text = view.findViewById<TextView>(id)
                        assertEquals(expected, text.text.toString())
                        assertTrue("status must fit horizontally", text.paint.measureText(expected) <= text.width)
                        assertTrue("status must fit vertically", text.height >= text.layout.height)
                        assertEquals(colors.text, text.currentTextColor)
                        assertNotEquals(colors.bg, text.currentTextColor)
                    }
                    assertEquals("", view.findViewById<TextView>(R.id.unit_1).text.toString())
                    assertEquals("", view.findViewById<TextView>(R.id.unit_2).text.toString())
                    assertEquals("36.50", view.findViewById<TextView>(R.id.value_3).text.toString())
                    assertEquals("87.60", view.findViewById<TextView>(R.id.value_4).text.toString())
                }
            }
        }
    }

    @Test fun pickerPreviewMarksSamplesAndDoesNotPromiseUnconnectedMeters() {
        instrumentation.runOnMainSync {
            val view = RemoteViews(context.packageName, R.layout.widget_energy_card).apply(context, null)
            layout(view, 340, 195)
            val texts = textViews(view).map { it.text.toString() }
            assertTrue(texts.contains("预览 · 示例数据"))
            assertTrue(texts.contains("未查询"))
            assertTrue(texts.contains("选择房间"))
            val todayHint = view.findViewById<TextView>(R.id.hint_3)
            assertTrue(todayHint.paint.measureText(todayHint.text.toString()) <= todayHint.width)
            assertFalse(texts.any { it.contains("照明") || it.contains("空调") || it.contains("度") || it.contains("可用约") })
            if (InstrumentationRegistry.getArguments().getString("exportWidgetPreview") == "true") {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(context.cacheDir, "widget-preview-energy-card.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun electricityCurrencyFitsRemoteViewsInBothThemes() {
        val room = com.zzupocket.app.data.whpu.WhpuElectricityRoom(
            com.zzupocket.app.data.whpu.WhpuCardOwner("101"), "182",
            listOf(com.zzupocket.app.data.whpu.WhpuElectricityStep(
                com.zzupocket.app.data.whpu.WhpuElectricityLevel("room", "房间", 4),
                com.zzupocket.app.data.whpu.WhpuElectricityOption("示例房间", "1&示例房间"))),
        )
        instrumentation.runOnMainSync {
            for (mode in listOf(DarkMode.LIGHT, DarkMode.DARK)) {
                for (width in listOf(250, 340)) {
                    val colors = WidgetStyle.colors(context, UiPrefs(darkMode = mode))
                    val reading = com.zzupocket.app.data.whpu.WhpuElectricityReading(room, -123L, 0L)
                    val plan = WidgetBalanceBundle(null, null, null, null, null, 0L, reading).plan()
                    val view = EnergyCardWidget.build(context, plan, colors,
                        Widgets.openApp(context, AppRoutes.ELECTRICITY),
                        Widgets.openApp(context, AppRoutes.CARD),
                        Widgets.openApp(context, AppRoutes.TODAY), 165).apply(context, null)
                    layout(view, width, 165)
                    val value = view.findViewById<TextView>(R.id.value_1)
                    assertEquals("-1.23", value.text.toString())
                    assertTrue(value.paint.measureText(value.text.toString()) <= value.width)
                    assertTrue(value.height >= value.layout.height)
                    assertNotEquals(colors.bg, value.currentTextColor)
                    assertEquals("元", view.findViewById<TextView>(R.id.unit_1).text.toString())
                    assertEquals("示例房间", view.findViewById<TextView>(R.id.hint_2).text.toString())
                    assertFalse(textViews(view).any { it.text.contains("度") || it.text.contains("可用约") })
                }
            }
        }
    }

    private fun layout(view: View, width: Int, height: Int) {
        val density = context.resources.displayMetrics.density
        val w = (width * density).toInt()
        val h = (height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
