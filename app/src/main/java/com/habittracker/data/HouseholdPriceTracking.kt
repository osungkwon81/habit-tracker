package com.habittracker.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.habittracker.HabitTrackerApplication
import com.habittracker.MainActivity
import com.habittracker.data.local.entity.HouseholdPriceDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

data class HouseholdOfferLink(val id: String, val url: String, val count: Int, val unit: String,
                              val option: String, val status: String, val unavailable: Boolean)

data class HouseholdTrackingSettings(val enabled: Boolean = false, val links: List<HouseholdOfferLink> = emptyList())

private const val recordsKey = "householdPriceTracking"
private fun JSONObject.group(id: String) = optJSONObject(recordsKey)?.optJSONObject(id)
private fun JSONObject.links() = getJSONObject("links")
private fun JSONObject.link(id: String) = HouseholdOfferLink(id, getString("url"), getInt("count"), getString("unit"),
    optString("option"), optString("status"), optBoolean("unavailable"))

class HouseholdPriceTracking internal constructor(private val context: Context, private val dao: HouseholdPriceDao,
                                                  private val requestBackup: () -> Unit) {
    private val alerts = PersonalAlerts.get(context)
    private val lock = Mutex()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val work by lazy { WorkManager.getInstance(context) }

    fun observe(id: String): Flow<HouseholdTrackingSettings> = flow {
        alerts.load()
        emitAll(alerts.snapshot.filterNotNull().map { raw ->
            val group = JSONObject(raw).group(id)
            if (group == null) HouseholdTrackingSettings() else {
                val links = group.links()
                HouseholdTrackingSettings(group.getBoolean("enabled"), links.keys().asSequence().map { links.getJSONObject(it).link(it) }.toList())
            }
        }.distinctUntilChanged())
    }.flowOn(Dispatchers.IO)

    suspend fun add(id: String, url: String, count: Int, unit: String, option: String) = withContext(Dispatchers.IO) {
        val cleanUrl = requireNotNull(resolveOfferUrl(url)) { "네이버 또는 쿠팡에서 상품 상세 페이지의 주소를 복사해 주세요." }
        require(count > 0 && option.length <= 200) { "구성 수량과 옵션명을 확인해 주세요." }
        lock.withLock {
            require(dao.product(id)?.unit == unit) { "제품 또는 비교 단위가 변경됐습니다. 링크를 다시 등록해 주세요." }
            val linkId = UUID.randomUUID().toString()
            var enabled = false
            alerts.change { root ->
                val groups = root.optJSONObject(recordsKey) ?: JSONObject().also { root.put(recordsKey, it) }
                val group = groups.optJSONObject(id) ?: JSONObject().put("enabled", false).put("links", JSONObject()).also { groups.put(id, it) }
                val links = group.links()
                require(links.keys().asSequence().none {
                    val old = links.getJSONObject(it)
                    old.getString("url") == cleanUrl && old.optString("option") == option
                }) { "이미 등록한 상품 링크와 옵션입니다." }
                links.put(linkId, JSONObject().put("url", cleanUrl).put("count", count).put("unit", unit).put("option", option))
                enabled = group.getBoolean("enabled")
            }
            requestBackup()
            if (enabled) schedule(id, linkId)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val group = requireNotNull(alerts.load().group(id)) { "추적할 링크를 먼저 등록해 주세요." }
            val ids = group.links().keys().asSequence().toList()
            require(!enabled || ids.isNotEmpty()) { "추적할 링크를 먼저 등록해 주세요." }
            if (enabled) ensureChannel()
            alerts.change { root ->
                val settings = requireNotNull(root.group(id))
                settings.put("enabled", enabled).put("revision", UUID.randomUUID().toString())
                ids.forEach { settings.links().getJSONObject(it).remove("unavailableNotified") }
            }
            requestBackup()
            ids.forEach { linkId -> if (enabled) schedule(id, linkId) else cancel(linkId) }
        }
    }

    suspend fun remove(id: String, linkId: String? = null, onlyIfUnavailable: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val removed = mutableListOf<String>()
            alerts.change { root ->
                val group = root.group(id) ?: return@change
                val links = group.links()
                if (linkId == null) removed.addAll(links.keys().asSequence().toList())
                else if (links.has(linkId)) {
                    require(!onlyIfUnavailable || links.getJSONObject(linkId).optBoolean("unavailable")) { "판매 상태가 변경됐습니다. 상세 화면에서 확인해 주세요." }
                    removed.add(linkId)
                }
                removed.forEach { links.remove(it) }
                if (links.length() == 0) root.getJSONObject(recordsKey).remove(id)
            }
            if (removed.isNotEmpty()) requestBackup()
            removed.forEach { cancel(it) }
        }
    }

    suspend fun restore() = withContext(Dispatchers.IO) {
        lock.withLock {
            val groups = alerts.load().optJSONObject(recordsKey) ?: return@withLock
            for (id in groups.keys()) {
                val group = groups.getJSONObject(id)
                val active = group.getBoolean("enabled") && dao.product(id) != null
                if (active) ensureChannel()
                for (linkId in group.links().keys()) {
                    if (active) schedule(id, linkId) else cancel(linkId)
                }
            }
        }
    }

    private fun ensureChannel() = notifications.createNotificationChannel(
        NotificationChannel(CHANNEL, "생활용품 가격 추적", NotificationManager.IMPORTANCE_DEFAULT))

    private suspend fun schedule(id: String, linkId: String) {
        val request = PeriodicWorkRequestBuilder<HouseholdOfferWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("product" to id, "link" to linkId)).build()
        work.enqueueUniquePeriodicWork("household-offer-$linkId", ExistingPeriodicWorkPolicy.KEEP, request).await()
    }

    private suspend fun cancel(linkId: String) {
        notifications.cancel("household-offer-$linkId", 0)
        work.cancelUniqueWork("household-offer-$linkId").await()
    }

    internal suspend fun checkOffer(id: String, linkId: String) = withContext(Dispatchers.IO) {
        val initial = alerts.load().group(id) ?: return@withContext
        if (!initial.getBoolean("enabled")) return@withContext
        val link = initial.links().optJSONObject(linkId)?.link(linkId) ?: return@withContext
        if (dao.product(id) == null) return@withContext
        ensureChannel()
        val quote = try { readHouseholdOffer(context, link) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("HouseholdPrices", "상품 페이지 조회 실패 productId=$id linkId=$linkId", error)
            OfferResult.Failed(error.localizedMessage ?: "상품 페이지 조회 실패")
        }
        lock.withLock {
            val history = dao.history(id) ?: return@withLock
            val best = history.purchases.minWithOrNull(cheapestFirst)
            alerts.change { root ->
                val group = root.group(id) ?: return@change
                val current = group.links().optJSONObject(linkId) ?: return@change
                if (!group.getBoolean("enabled") || group.optString("revision") != initial.optString("revision")) return@change
                val key = "household-offer-$linkId"
                val status = when (quote) {
                    is OfferResult.Failed -> {
                        Log.w("HouseholdPrices", "가격 확인 보류 productId=$id linkId=$linkId reason=${quote.reason}")
                        if (current.optBoolean("unavailable")) {
                            notifications.cancel(key, 0)
                            current.put("unavailable", false).remove("unavailableNotified")
                        }
                        quote.reason
                    }
                    is OfferResult.Unavailable -> {
                        current.put("unavailable", true)
                        val open = Intent(context, MainActivity::class.java).setData(Uri.parse("habittracker://household/$id/$linkId"))
                            .putExtra("householdProduct", id).putExtra("householdUnavailableLink", linkId)
                        val sent = current.optBoolean("unavailableNotified") || alerts.notify(key, "${history.product.name} · ${quote.reason}",
                            "${offerSite(link.url)} ${link.count}${link.unit} ${link.option} · 추적 링크를 삭제할 수 있습니다.", channel = CHANNEL,
                            openIntent = open, actionLabel = "링크 삭제 확인")
                        current.put("unavailableNotified", sent)
                        quote.reason + if (sent) "" else " · 알림 권한을 확인해 주세요."
                    }
                    is OfferResult.Available -> {
                        if (current.optBoolean("unavailable")) notifications.cancel(key, 0)
                        current.put("unavailable", false).remove("unavailableNotified")
                        val total = BigDecimal.valueOf(quote.delivered)
                        val price = total.divide(link.count.toBigDecimal(), MathContext.DECIMAL128)
                        val summary = "배송비 포함 ${link.unit}당 ${DecimalFormat("#,##0.##").format(price)}원"
                        when {
                            best == null -> "$summary · 비교할 구매 기록 없음"
                            best.unit != link.unit || history.product.unit != link.unit -> "비교 단위가 변경됐습니다. 구성 수량을 다시 등록해 주세요."
                            total * best.count.toBigDecimal() <= best.amount.toBigDecimal() * link.count.toBigDecimal() * BigDecimal("1.05") -> {
                                val sent = alerts.notify(key, "${history.product.name} · 가격 알림", "${offerSite(link.url)} · $summary · 내 최저가의 105% 이하",
                                    url = link.url, channel = CHANNEL)
                                "$summary · " + if (sent) "알림 기준 충족" else "알림 권한을 확인해 주세요."
                            }
                            else -> "$summary · 알림 기준 초과"
                        }
                    }
                }
                current.put("status", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm")) + " · " + status)
            }
        }
    }

    companion object { const val CHANNEL = "household-price-tracking" }
}

class HouseholdOfferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val product = inputData.getString("product") ?: return Result.failure()
        val link = inputData.getString("link") ?: return Result.failure()
        return try {
            val app = applicationContext as HabitTrackerApplication
            app.appContainer.awaitRepository().householdPrices.tracking.checkOffer(product, link)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("HouseholdPrices", "가격 추적 처리 실패 productId=$product linkId=$link", error)
            Result.failure()
        }
    }
}
