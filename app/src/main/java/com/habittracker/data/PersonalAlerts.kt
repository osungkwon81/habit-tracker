package com.habittracker.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.File
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 앱 내부 원자적 파일에 보존하며 화면과 Worker는 같은 인스턴스를 공유한다. */
class PersonalAlerts private constructor(private val context: Context) {
    private val lock = Mutex()
    private val file = AtomicFile(File(context.filesDir, "personal-alerts.json"))
    private val state = MutableStateFlow<String?>(null)
    val snapshot = state.asStateFlow()
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock { read().also { state.value = it.toString() } } }
    suspend fun backupTo(target: File) = withContext(Dispatchers.IO) {
        lock.withLock { if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) target.writeText(read().toString()) }
    }
    suspend fun restoreIfMissing(source: File) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (source.exists() && !file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                val value = JSONObject(source.readText())
                val stream = file.startWrite()
                try { stream.write(value.toString().toByteArray()); file.finishWrite(stream); state.value = value.toString() }
                catch (error: Exception) { file.failWrite(stream); throw error }
            }
        }
    }
    private fun read(): JSONObject = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() }) else
        JSONObject().put("accounts", JSONArray()).put("keywords", JSONArray()).put("subscriptions", JSONArray()).put("expenses", JSONArray()).put("seen", JSONArray()).put("notified", JSONArray())
    suspend fun change(action: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val value = read()
            val original = value.toString()
            action(value)
            if (value.toString() == original) return@withLock
            val stream = file.startWrite()
            try { stream.write(value.toString().toByteArray()); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            state.value = value.toString()
        }
    }
    suspend fun chargeDueBills() = change { data ->
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val accounts = data.getJSONArray("accounts").objects().associateBy { it.getString("id") }
        data.getJSONArray("subscriptions").objects().forEach { bill ->
            val accountId = bill.getString("account")
            check(accounts.containsKey(accountId)) { "정기결제 계정 누락: accountId=$accountId billId=${bill.getString("id")}" }
            // 시작일이 없는 기존 기록의 과거 미처리 결제를 소급 차감하지 않는다.
            val start = if (bill.has("autoChargeFrom")) LocalDate.parse(bill.getString("autoChargeFrom"))
                else today.also { bill.put("autoChargeFrom", it.toString()) }
            var month = YearMonth.from(start)
            if (bill.has("paidThrough")) month = maxOf(month, YearMonth.from(LocalDate.parse(bill.getString("paidThrough"))).plusMonths(1))
            var date = month.atDay(minOf(bill.getInt("day"), month.lengthOfMonth()))
            if (date.isBefore(start)) {
                month = month.plusMonths(1)
                date = month.atDay(minOf(bill.getInt("day"), month.lengthOfMonth()))
            }
            while (!date.isAfter(today)) {
                recordBillPayment(data, bill, date, date)
                val remaining = accounts.getValue(accountId).getLong("balance")
                val key = "${bill.getString("id")}:$date"
                val notified = data.getJSONArray("notified")
                if (date == today && remaining < 0 && (0 until notified.length()).none { notified.getString(it) == key }) {
                    // 당일 자동 차감 후에는 예정 목록에서 빠지므로 부족 알림을 여기서 보존한다.
                    try {
                        if (notify("google:$key", "구글 잔액 · ${accounts.getValue(accountId).getString("name")}",
                            "$date ${bill.getString("name")} 자동 차감 후 ${java.math.BigInteger.valueOf(remaining).negate()}원 부족합니다. 잔액을 확인해 주세요.", accountId = accountId)) notified.put(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e("PersonalAlerts", "product=google phase=due-notification failed accountId=$accountId billId=${bill.getString("id")} date=$date", error)
                    }
                }
                month = month.plusMonths(1)
                date = month.atDay(minOf(bill.getInt("day"), month.lengthOfMonth()))
            }
        }
    }
    fun schedule() {
        ensureChannels(context)
        val manager = WorkManager.getInstance(context)
        val data = state.value?.let(::JSONObject) ?: return
        if (data.getJSONArray("keywords").length() > 0) manager.enqueueUniquePeriodicWork("ppomppu-keywords", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PpomppuAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        else manager.cancelUniqueWork("ppomppu-keywords")
        if (data.getJSONArray("subscriptions").length() > 0) manager.enqueueUniquePeriodicWork("google-balance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GoogleBalanceAlertWorker>(15, TimeUnit.MINUTES).build())
        else manager.cancelUniqueWork("google-balance")
    }
    fun notify(key: String, title: String, body: String, url: String? = null, accountId: String? = null): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannels(context)
        val channel = channelId(url == null)
        if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url)) else Intent(context, com.habittracker.MainActivity::class.java)
            .setData(Uri.parse("habittracker://google-balance/$accountId"))
            .putExtra("googleBalanceAccount", accountId)
        val pending = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(key, 0, Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
        return true
    }
    companion object {
        fun channelId(google: Boolean) = if (google) "google-balance-alerts" else "ppomppu-alerts"
        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val importance = manager.getNotificationChannel("personal-alerts")?.importance ?: NotificationManager.IMPORTANCE_DEFAULT
            listOf(false, true).forEach { google ->
                val id = channelId(google)
                if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(NotificationChannel(id, if (google) "구글 잔액" else "뽐뿌 키워드", importance))
            }
        }
        @Volatile private var instance: PersonalAlerts? = null
        fun get(context: Context): PersonalAlerts = instance ?: synchronized(this) {
            instance ?: PersonalAlerts(context.applicationContext).also { instance = it }
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun upcomingBills(data: JSONObject, accountId: String, today: LocalDate): List<Pair<JSONObject, LocalDate>> =
    data.getJSONArray("subscriptions").objects().filter { it.getString("account") == accountId }
        .flatMap { bill -> (0L..2L).map { offset ->
            val month = YearMonth.from(today).plusMonths(offset)
            bill to month.atDay(minOf(bill.getInt("day"), month.lengthOfMonth()))
        } }.filter { (bill, date) -> !date.isBefore(today) && !billPaid(bill, YearMonth.from(date)) }.sortedBy { it.second }

internal fun billPaid(bill: JSONObject, month: YearMonth): Boolean =
    bill.optString("paidThrough", "").take(7) >= month.toString()

internal fun recordBillPayment(data: JSONObject, bill: JSONObject, date: LocalDate, recordedOn: LocalDate) {
    val billId = bill.getString("id")
    val accountId = bill.getString("account")
    val account = data.getJSONArray("accounts").objects().firstOrNull { it.getString("id") == accountId }
        ?: error("정기결제 계정 누락: accountId=$accountId billId=$billId")
    require(!billPaid(bill, YearMonth.from(date))) { "해당 월 결제를 이미 처리했습니다: billId=$billId date=$date" }
    val amount = bill.getLong("amount")
    require(amount > 0) { "정기결제 금액 오류: billId=$billId date=$date" }
    val remaining = try { Math.subtractExact(account.getLong("balance"), amount) }
    catch (error: ArithmeticException) {
        throw IllegalStateException("정기결제 잔액 계산 범위 초과: accountId=$accountId billId=$billId date=$date", error)
    }
    account.put("balance", remaining)
    bill.put("paidThrough", date.toString())
    data.getJSONArray("expenses").put(JSONObject().put("id", UUID.randomUUID().toString()).put("account", accountId)
        .put("name", bill.getString("name")).put("amount", amount).put("date", recordedOn.toString())
        .put("bill", billId).put("billDate", date.toString()).put("revision", account.optLong("revision")))
}

class PpomppuAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun matchesKeyword(title: String, keyword: String): Boolean {
        val words = keyword.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { title.contains(it, ignoreCase = true) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = PersonalAlerts.get(applicationContext)
        try {
            val initial = store.load()
            if (initial.getJSONArray("keywords").length() == 0) return@withContext Result.success()
            val connection = URL("https://www.ppomppu.co.kr/rss.php?id=ppomppu").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            val posts = mutableListOf<Pair<String, String>>()
            try {
                require(connection.responseCode == 200) { "뽐뿌 RSS 응답 ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    val boundedInput = object : java.io.FilterInputStream(input) {
                        var consumed = 0L
                        private fun count(amount: Int) { if (amount > 0) { consumed += amount; require(consumed <= 2 * 1024 * 1024) { "뽐뿌 RSS 응답 크기 초과" } } }
                        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also(::count)
                    }
                    parser.setInput(boundedInput, null)
                    var inItem = false; var title = ""; var link = ""; var isFeed = false
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                            "rss", "RDF" -> isFeed = true
                            "item" -> { inItem = true; title = ""; link = "" }
                            "title" -> if (inItem) title = parser.nextText()
                            "link" -> if (inItem) link = parser.nextText()
                        }
                        if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item") {
                            val uri = Uri.parse(link)
                            if (title.isNotBlank() && uri.scheme in listOf("https", "http") && uri.host in listOf("www.ppomppu.co.kr", "ppomppu.co.kr") && uri.getQueryParameter("id") == "ppomppu" && uri.getQueryParameter("no")?.toLongOrNull() != null)
                                posts += title to "https://www.ppomppu.co.kr/zboard/view.php?id=ppomppu&no=${uri.getQueryParameter("no")}"
                            inItem = false
                        }
                        parser.next()
                    }
                    require(isFeed && posts.isNotEmpty()) { "뽐뿌 RSS 형식 또는 게시글 확인 실패" }
                }
            } finally { connection.disconnect() }
            store.change { data ->
                val seen = data.getJSONArray("seen")
                val old = (0 until seen.length()).map { seen.getString(it) }.toSet()
                val keywords = data.getJSONArray("keywords").objects()
                val delivered = mutableSetOf<String>()
                posts.forEach { (title, link) ->
                    val matched = keywords.filter { matchesKeyword(title, it.getString("word")) }
                    if (data.optBoolean("ppomppuInitialized") && link !in old && matched.isNotEmpty() && store.notify("ppomppu:$link", "뽐뿌 · ${matched.joinToString { it.getString("word") }}", title, link)) delivered += link
                }
                data.put("seen", JSONArray((old + delivered + posts.filter { (title, _) -> keywords.none { matchesKeyword(title, it.getString("word")) } }.map { it.second }).toList().takeLast(2000)))
                data.put("ppomppuCheckedAt", java.time.LocalDateTime.now().toString()).remove("ppomppuError")
                if (!data.optBoolean("ppomppuInitialized")) data.put("seen", JSONArray(posts.map { it.second }))
                data.put("ppomppuInitialized", true)
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("PersonalAlerts", "product=ppomppu phase=poll failed", error)
            try { store.change { it.put("ppomppuError", "게시글 확인 실패: ${error.message}") } } catch (recordError: Exception) { error.addSuppressed(recordError) }
            Result.retry()
        }
    }
}

class GoogleBalanceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = PersonalAlerts.get(applicationContext)
        return try {
            store.chargeDueBills()
            store.change { data ->
                val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
                val notified = data.getJSONArray("notified")
                val keys = (0 until notified.length()).map { notified.getString(it) }.toMutableSet()
                data.getJSONArray("accounts").objects().forEach { account ->
                    val bills = upcomingBills(data, account.getString("id"), today)
                    var remaining = account.getLong("balance")
                    bills.forEach { (bill, date) ->
                        remaining = Math.subtractExact(remaining, bill.getLong("amount"))
                        val key = "${bill.getString("id")}:$date"
                        if (!today.isBefore(date.minusDays(bill.getLong("advance"))) && remaining < 0 && key !in keys &&
                            store.notify("google:$key", "구글 잔액 · ${account.getString("name")}",
                                "$date ${bill.getString("name")} 결제 예정 · 예정 결제 누적 기준 ${-remaining}원 부족합니다. 잔액을 확인해 주세요.", accountId = account.getString("id"))) keys += key
                    }
                }
                data.put("notified", JSONArray(keys.filter { key -> key.substringAfterLast(':') >= today.toString() }))
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Log.e("PersonalAlerts", "product=google phase=balance-check failed", error); Result.retry() }
    }
}
