package com.zzupocket.app.ui.widget

import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.R
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.nav.AppRoutes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetExamRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun examRowRendersLabelTimeAndExamRoomInBothThemes() {
        instrumentation.runOnMainSync {
            for (mode in listOf(DarkMode.LIGHT, DarkMode.DARK)) {
                val prefs = UiPrefs(darkMode = mode)
                val course = WidgetCourse("exam:SYN-01", "人工考试", "示例考场", 540, 660, isExam = true)
                val views = Widgets.courseRow(context, WidgetSchedule.toRow(course), WidgetStyle.colors(context, prefs),
                    mode == DarkMode.DARK, WidgetSchedule.RowDetail.FULL, 46, 13, 12)
                    .apply(context, FrameLayout(context))
                assertEquals("考试 · 人工考试", views.findViewById<TextView>(R.id.row_name).text.toString())
                assertEquals("09:00-11:00", views.findViewById<TextView>(R.id.row_time).text.toString())
                assertEquals("示例考场", views.findViewById<TextView>(R.id.row_place).text.toString())
                assertTrue(views.findViewById<android.view.View>(R.id.row_root).hasOnClickListeners())
            }
        }
    }

    @Test fun examAndSchedulePendingIntentsRemainDistinct() {
        val exam = Widgets.openApp(context, AppRoutes.EXAMS)
        val schedule = Widgets.openApp(context, AppRoutes.SCHEDULE)
        assertNotEquals(exam, schedule)
        assertEquals(exam, Widgets.openApp(context, AppRoutes.EXAMS))
        assertEquals(schedule, Widgets.openApp(context, AppRoutes.SCHEDULE))
    }
}
