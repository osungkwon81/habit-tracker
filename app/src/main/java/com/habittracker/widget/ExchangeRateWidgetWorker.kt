package com.habittracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.habittracker.HabitTrackerApplication
import com.habittracker.MainActivity
import com.habittracker.R
import com.habittracker.data.exchange.ExchangeCurrency
import com.habittracker.data.exchange.ExchangeRateSource
import com.habittracker.data.local.entity.ExchangeRateEntity
import com.habittracker.ui.exchange.ExchangeChartPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.coroutines.coroutineContext

class ExchangeRateWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val component = ComponentName(applicationContext, ExchangeRateWidgetProvider::class.java)
        val requestedId = inputData.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val requestedIds = if (requestedId == AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf() else intArrayOf(requestedId)
        val ids = (manager.getAppWidgetIds(component) + requestedIds).distinct()
            .filter { manager.getAppWidgetInfo(it)?.provider == component }.toIntArray()
        if (ids.isEmpty()) return Result.success()
        var selections = emptyMap<Int, ExchangeCurrency?>()
        var periods = emptyMap<Int, ExchangeChartPeriod>()
        return try {
            selections = withContext(Dispatchers.IO) {
                ids.associateWith { ExchangeRateWidgetProvider.selectedCurrency(applicationContext, it) }
            }
            periods = withContext(Dispatchers.IO) {
                ids.associateWith { ExchangeRateWidgetProvider.selectedPeriod(applicationContext, it) }
            }
            val currencies = selections.values.filterNotNull().distinct()
            // DB 스냅샷만 읽는다. 위젯 갱신이 은행 조회나 수집 예약으로 이어지지 않게 한다.
            val rowsByCurrency = if (currencies.isEmpty()) emptyMap() else {
                val container = (applicationContext as HabitTrackerApplication).appContainer
                val repository = container.awaitExchangeRepository()
                currencies.associateWith { repository.observeRates(it).first() }
            }
            val rendered = withContext(Dispatchers.Default) {
                val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
                val series = ids.asSequence().mapNotNull { id -> selections[id]?.let { it to periods.getValue(id) } }.distinct()
                    .associateWith { (currency, period) -> chartRows(rowsByCurrency.getValue(currency), period, today) }
                val graphs = series.mapValues { (selection, rows) ->
                    if (rows.isEmpty()) null else drawGraph(rows, rows.map { it.rate.toDouble() }, selection.second)
                }
                ids.associateWith { id ->
                    val currency = selections[id]
                    val period = periods.getValue(id)
                    createViews(id, currency, period).apply {
                        if (currency == null) {
                            setTextViewText(R.id.widget_notice, applicationContext.getString(R.string.exchange_widget_select_currency))
                        } else {
                            val selection = currency to period
                            bindCurrency(this, currency, rowsByCurrency.getValue(currency), series.getValue(selection), graphs[selection], period)
                        }
                    }
                }
            }
            for ((id, views) in rendered) {
                coroutineContext.ensureActive()
                manager.updateAppWidget(id, views)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ExchangeWidget", "환율 위젯 DB 조회/표시 실패: widgetIds=${ids.joinToString()}", error)
            for (id in ids) {
                val notice = createViews(id, selections[id], periods[id] ?: ExchangeChartPeriod.ALL)
                notice.setTextViewText(R.id.widget_notice, applicationContext.getString(R.string.exchange_widget_error))
                notice.setViewVisibility(R.id.widget_rate, View.GONE)
                manager.updateAppWidget(id, notice)
            }
            Result.failure()
        }
    }

    private fun createViews(widgetId: Int, currency: ExchangeCurrency?, period: ExchangeChartPeriod) = RemoteViews(applicationContext.packageName, R.layout.exchange_rate_widget).apply {
        val openRates = if (currency == null) {
            Intent(applicationContext, ExchangeRateWidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        } else {
            setTextViewText(R.id.widget_country, when (currency) {
                ExchangeCurrency.USD -> "미국"
                ExchangeCurrency.JPY -> "일본"
            })
            setTextViewText(R.id.widget_currency, "${currency.unit}${currency.amountUnitLabel} · ${period.label}")
            Intent(applicationContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("habittracker://exchange/rates?widgetCurrency=${currency.name}")
            }
        }
        setOnClickPendingIntent(
            R.id.exchange_widget_root,
            PendingIntent.getActivity(applicationContext, widgetId, openRates, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
    }

    private fun bindCurrency(views: RemoteViews, currency: ExchangeCurrency, allRows: List<ExchangeRateEntity>, rows: List<ExchangeRateEntity>, graph: Bitmap?, period: ExchangeChartPeriod) {
        val latest = allRows.lastOrNull()
        if (latest == null) {
            views.setTextViewText(R.id.widget_notice, applicationContext.getString(R.string.exchange_widget_empty))
            views.setViewVisibility(R.id.widget_rate, View.GONE)
            return
        }
        val format = NumberFormat.getNumberInstance(Locale.KOREA).apply { maximumFractionDigits = 2 }
        val rateText = "${format.format(latest.rate.toBigDecimal())}원"
        views.setTextViewText(R.id.widget_rate, rateText)
        views.setContentDescription(R.id.widget_rate, "${currency.rateLabel} $rateText. 저장된 고시 ${latest.quotedAt.format(quoteFormat)} · ${latest.quoteRound}회차")
        if (rows.isEmpty()) {
            views.setTextViewText(R.id.widget_notice, "오늘 저장된 고시 없음")
            views.setViewVisibility(R.id.widget_graph, View.GONE)
            return
        }
        views.setViewVisibility(R.id.widget_notice, View.GONE)
        views.setViewVisibility(R.id.widget_graph, View.VISIBLE)
        views.setImageViewBitmap(R.id.widget_graph, checkNotNull(graph))
        val sourceDescription = if (rows.any { it.source == ExchangeRateSource.NAVER_SHINHAN.name }) " 네이버에서 수집한 신한은행 고시가 포함되어 있습니다." else ""
        val representativeDescription = if (period == ExchangeChartPeriod.DAILY || period == ExchangeChartPeriod.MONTHLY) " 해당 일·월의 마지막 저장 고시환율입니다." else ""
        views.setContentDescription(R.id.widget_graph, "${currency.label} ${period.label} 환율 그래프. ${rows.size}개 지점.$representativeDescription 미수집 구간의 실제 변동은 포함하지 않습니다.$sourceDescription")
    }

    private fun chartRows(rows: List<ExchangeRateEntity>, period: ExchangeChartPeriod, today: LocalDate): List<ExchangeRateEntity> = when (period) {
        ExchangeChartPeriod.ALL -> rows
        ExchangeChartPeriod.TODAY -> rows.filter { it.quotedAt.toLocalDate() == today }
        ExchangeChartPeriod.DAILY -> {
            val daily = linkedMapOf<LocalDate, ExchangeRateEntity>()
            rows.forEach { daily[it.quotedAt.toLocalDate()] = it }
            daily.values.toList()
        }
        ExchangeChartPeriod.MONTHLY -> {
            val monthly = linkedMapOf<YearMonth, ExchangeRateEntity>()
            rows.forEach { monthly[YearMonth.from(it.quotedAt)] = it }
            monthly.values.toList()
        }
    }

    private fun drawGraph(rows: List<ExchangeRateEntity>, rates: List<Double>, period: ExchangeChartPeriod): Bitmap {
        val bitmap = Bitmap.createBitmap(480, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = 6f
        val right = bitmap.width - 6f
        val top = 6f
        val bottom = bitmap.height - 6f
        val low = checkNotNull(rates.minOrNull())
        val high = checkNotNull(rates.maxOrNull())
        val padding = ((high - low) * 0.1).coerceAtLeast(0.01)
        fun distance(row: ExchangeRateEntity, index: Int): Long = when (period) {
            ExchangeChartPeriod.ALL, ExchangeChartPeriod.TODAY -> index.toLong()
            ExchangeChartPeriod.DAILY -> ChronoUnit.DAYS.between(rows.first().quotedAt.toLocalDate(), row.quotedAt.toLocalDate())
            ExchangeChartPeriod.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(rows.first().quotedAt), YearMonth.from(row.quotedAt))
        }
        val span = distance(rows.last(), rows.lastIndex).coerceAtLeast(1)
        val path = Path()
        var lastX = left
        var lastY = top
        rows.forEachIndexed { index, row ->
            val x = left + (right - left) * distance(row, index).toFloat() / span
            val y = top + (bottom - top) * (1 - (rates[index] - low + padding) / (high - low + 2 * padding)).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
            lastY = y
        }
        paint.color = applicationContext.getColor(R.color.exchange_widget_accent)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(lastX, lastY, 5f, paint)
        return bitmap
    }

    companion object {
        private val quoteFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
    }
}
