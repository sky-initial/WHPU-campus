package com.zzupocket.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.zzupocket.app.R
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.nav.AppRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 左侧打开官方电费页；右侧显示当前 WHPU 校园卡缓存并打开原生查询页。
 * 电费原生查询待接入，不显示旧项目电表读数。组件只读缓存，不发起网络请求。
 */
class EnergyCardWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDisabled(context: Context) {
        Widgets.onProviderDisabled(context, EnergyCardWidget::class.java)
    }

    private fun refresh(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ctx = context.applicationContext
                val prefs = WidgetData.prefs()
                render(ctx, mgr, ids, WidgetData.loadBalance(), prefs)
            } catch (t: Throwable) {
                Log.w(TAG, "刷新「电量与校园卡」失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private const val TAG = "EnergyCardWidget"

        /** 四个数据块容器（撑高用）：左半照明/空调，右半余额/本周 */
        private val BLOCK_ROOTS = listOf(R.id.block_l1, R.id.block_l2, R.id.block_r1, R.id.block_r2)

        /** 四个数据块在布局里的 id：色条 / 块名 / 数字 / 单位 / 小字 */
        private val BLOCKS = listOf(
            intArrayOf(R.id.bar_1, R.id.label_1, R.id.value_1, R.id.unit_1, R.id.hint_1),
            intArrayOf(R.id.bar_2, R.id.label_2, R.id.value_2, R.id.unit_2, R.id.hint_2),
            intArrayOf(R.id.bar_3, R.id.label_3, R.id.value_3, R.id.unit_3, R.id.hint_3),
            intArrayOf(R.id.bar_4, R.id.label_4, R.id.value_4, R.id.unit_4, R.id.hint_4),
        )

        fun render(
            context: Context,
            mgr: AppWidgetManager,
            ids: IntArray,
            balance: WidgetBalanceBundle,
            prefs: UiPrefs,
        ) {
            val colors = WidgetStyle.colors(context, prefs)
            val plan = balance.plan()
            // 三个目标必须用**不同的 requestCode**（见 Widgets.openApp 的注释）
            val energyClick = Widgets.openApp(context, AppRoutes.ELECTRICITY)
            val cardClick = Widgets.openApp(context, AppRoutes.CARD)
            val homeClick = Widgets.openApp(context, AppRoutes.TODAY)
            ids.forEach { id ->
                val (_, heightDp) = Widgets.sizeDp(mgr, id)
                val rv = build(context, plan, colors, energyClick, cardClick, homeClick, heightDp)
                runCatching { mgr.updateAppWidget(id, rv) }
            }
        }

        internal fun build(
            context: Context,
            plan: WidgetBalancePlan,
            colors: WidgetColors,
            energyClick: PendingIntent,
            cardClick: PendingIntent,
            homeClick: PendingIntent,
            heightDp: Int,
        ): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_energy_card)
            WidgetStyle.paintBackground(rv, R.id.widget_root, colors)
            // 根容器兜底，两块各自的目标
            rv.setOnClickPendingIntent(R.id.widget_root, homeClick)
            rv.setOnClickPendingIntent(R.id.zone_energy, energyClick)
            rv.setOnClickPendingIntent(R.id.zone_card, cardClick)

            rv.setTextViewText(R.id.update, plan.update)
            rv.setTextColor(R.id.update, colors.hint)

            rv.setTextViewText(R.id.left_title, plan.leftTitle)
            rv.setTextColor(R.id.left_title, colors.text)
            rv.setTextViewText(R.id.right_title, plan.rightTitle)
            rv.setTextColor(R.id.right_title, colors.text)

            val blocks = plan.leftBlocks + plan.rightBlocks
            blocks.forEachIndexed { index, block ->
                val (barId, labelId, valueId, unitId, hintId) = BLOCKS[index]
                rv.setTextViewText(labelId, block.label)
                rv.setTextColor(labelId, colors.text)

                rv.setTextViewText(valueId, block.value)
                // 欠费 / 电量偏低 / 余额偏低：只把数字变红
                rv.setTextColor(valueId, if (block.alert) colors.error else colors.text)

                rv.setTextViewText(unitId, block.unit)
                rv.setTextColor(unitId, colors.dim)

                rv.setTextViewText(hintId, block.hint)
                rv.setTextColor(hintId, colors.hint)
                rv.setViewVisibility(hintId, if (block.hint.isBlank()) View.GONE else View.VISIBLE)

                // 色条统一用主题主色（与课表组件的色条同一套视觉语言）
                WidgetStyle.tint(rv, barId, colors.accent)
            }

            // 把四个块撑到「可用高度 ÷ 2」，块内内容靠着内边距摊开 —— 195dp 的盒子正好铺满。
            // ⚠️ 实测两个坑：`layout_weight` 在 RemoteViews 这层嵌套里不生效；
            // `setInt(…, "setMinimumHeight", …)` 是反射调用，而 Android 只放行带
            // `@RemotableViewMethod` 注解的方法，`View.setMinimumHeight` 没有 → 调用被丢掉。
            // 所以用 API 31 的正经 API `setViewPadding` 来补高度（见 Widgets.stretch）。
            val blockHeightDp = WidgetSchedule.blockHeightDp(heightDp)
            BLOCK_ROOTS.forEach {
                Widgets.stretch(
                    rv = rv,
                    viewId = it,
                    context = context,
                    targetHeightDp = blockHeightDp,
                    naturalHeightDp = Widgets.NATURAL_BLOCK_HEIGHT_DP +
                        if (it == R.id.block_r1 || it == R.id.block_r2) 16 else 0,
                )
            }

            // 盒子变矮时把字号收一档（块变矮了，字号不跟着收会挤）
            val factor = WidgetStyle.fontFactor(heightDp)
            if (factor != 1f) {
                val size = 28f * factor
                listOf(R.id.value_1, R.id.value_2, R.id.value_3, R.id.value_4).forEach {
                    rv.setTextViewTextSize(it, android.util.TypedValue.COMPLEX_UNIT_DIP, size)
                }
            }
            return rv
        }
    }
}
