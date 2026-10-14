package com.habittracker.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import com.habittracker.data.exchange.ExchangeCurrency
import com.habittracker.ui.exchange.ExchangeChartPeriod
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class ExchangeRateWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueueUpdate(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        enqueueUpdate(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { editor.remove("currency_$it"); editor.remove("period_$it") }
        editor.apply()
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val currencies = oldWidgetIds.map { selectedCurrency(context, it) }
        val periods = oldWidgetIds.map { selectedPeriod(context, it) }
        val editor = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
        oldWidgetIds.forEach { editor.remove("currency_$it"); editor.remove("period_$it") }
        newWidgetIds.forEachIndexed { index, id ->
            currencies.getOrNull(index)?.let { editor.putString("currency_$id", it.name) }
            periods.getOrNull(index)?.let { editor.putString("period_$id", it.name) }
        }
        editor.apply()
    }

    companion object {
        private const val preferencesName = "exchange_rate_widgets"

        fun selectedCurrency(context: Context, appWidgetId: Int): ExchangeCurrency? {
            val code = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString("currency_$appWidgetId", null)
            return ExchangeCurrency.entries.firstOrNull { it.name == code }
        }

        fun selectedPeriod(context: Context, appWidgetId: Int): ExchangeChartPeriod {
            val code = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString("period_$appWidgetId", null)
            return ExchangeChartPeriod.entries.firstOrNull { it.name == code } ?: ExchangeChartPeriod.ALL
        }

        fun saveSettings(context: Context, appWidgetId: Int, currency: ExchangeCurrency, period: ExchangeChartPeriod) {
            check(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
                .putString("currency_$appWidgetId", currency.name)
                .putString("period_$appWidgetId", period.name).commit()) {
                "환율 위젯 $appWidgetId 설정을 저장하지 못했습니다."
            }
        }

        fun enqueueUpdate(context: Context, appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID) {
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, ExchangeRateWidgetProvider::class.java))
            if (ids.isEmpty() && appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                "exchange-rate-widget-update",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ExchangeRateWidgetWorker>()
                    .setInputData(workDataOf(AppWidgetManager.EXTRA_APPWIDGET_ID to appWidgetId)).build(),
            )
        }
    }
}
