package com.habittracker.data.stock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class KisRealtimePrice(
    val productCode: String,
    val currentPrice: Long,
)

internal sealed interface KisRealtimeEvent {
    data object Connected : KisRealtimeEvent
    data class Price(val value: KisRealtimePrice) : KisRealtimeEvent
    data class Failure(val cause: Throwable) : KisRealtimeEvent
    data class Closed(val reason: String) : KisRealtimeEvent
}

internal class KisRealtimeStockClient : Closeable {
    private companion object {
        const val WEBSOCKET_URL = "ws://ops.koreainvestment.com:21000/tryitout"
        const val MAX_SUBSCRIPTIONS = 41
        const val SUBSCRIPTION_INTERVAL_MILLIS = 150L
        const val KRX_EXECUTION_TR_ID = "H0STCNT0"
        const val NXT_EXECUTION_TR_ID = "H0NXCNT0"
        const val UNIFIED_EXECUTION_TR_ID = "H0UNCNT0"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20L, TimeUnit.SECONDS)
        .build()
    private var activeConnection: Connection? = null

    @Synchronized
    fun connect(
        approvalKey: String,
        market: KisStockMarket,
        productCodes: Set<String>,
        onEvent: (KisRealtimeEvent) -> Unit,
    ): Connection {
        require(approvalKey.isNotBlank()) { "KIS 실시간 접속키가 없습니다." }
        require(productCodes.isNotEmpty()) { "실시간으로 감시할 종목이 없습니다." }
        require(productCodes.size <= MAX_SUBSCRIPTIONS) {
            "KIS 실시간 감시는 최대 ${MAX_SUBSCRIPTIONS}종목까지 등록할 수 있습니다. (현재=${productCodes.size}종목)"
        }
        activeConnection?.close()
        return Connection(
            approvalKey = approvalKey,
            market = market,
            productCodes = productCodes,
            onEvent = onEvent,
        ).also {
            activeConnection = it
            it.open()
        }
    }

    override fun close() {
        synchronized(this) {
            activeConnection?.close()
            activeConnection = null
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    inner class Connection internal constructor(
        private val approvalKey: String,
        private val market: KisStockMarket,
        private val productCodes: Set<String>,
        private val onEvent: (KisRealtimeEvent) -> Unit,
    ) : Closeable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isClosed = AtomicBoolean(false)
        private var webSocket: WebSocket? = null

        internal fun open() {
            val request = Request.Builder().url(WEBSOCKET_URL).build()
            webSocket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        scope.launch {
                            productCodes.sorted().forEachIndexed { index, productCode ->
                                if (isClosed.get()) return@launch
                                val sent = webSocket.send(subscriptionMessage(productCode))
                                if (!sent) {
                                    onEvent(KisRealtimeEvent.Failure(IOException("KIS 실시간 종목 구독 요청을 보내지 못했습니다.")))
                                    webSocket.cancel()
                                    return@launch
                                }
                                if (index < productCodes.size - 1) delay(SUBSCRIPTION_INTERVAL_MILLIS)
                            }
                            if (!isClosed.get()) onEvent(KisRealtimeEvent.Connected)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.startsWith("0|") || text.startsWith("1|")) {
                            parsePrices(text).forEach { price ->
                                if (price.productCode in productCodes) {
                                    onEvent(KisRealtimeEvent.Price(price))
                                }
                            }
                            return
                        }
                        handleSystemMessage(webSocket, text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!isClosed.get()) {
                            onEvent(KisRealtimeEvent.Closed(reason.ifBlank { "KIS 실시간 연결이 종료되었습니다. (code=$code)" }))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                        if (!isClosed.get()) onEvent(KisRealtimeEvent.Failure(error))
                    }

                    private fun handleSystemMessage(webSocket: WebSocket, text: String) {
                        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val header = message.optJSONObject("header") ?: return
                        if (header.optString("tr_id") == "PINGPONG") {
                            if (!sendPong(webSocket, text)) {
                                onEvent(KisRealtimeEvent.Failure(IOException("KIS 실시간 연결의 PINGPONG 응답에 실패했습니다.")))
                                webSocket.cancel()
                            }
                            return
                        }
                        val body = message.optJSONObject("body") ?: return
                        if (body.optString("rt_cd").ifBlank { "0" } != "0") {
                            val code = body.optString("msg_cd")
                            val reason = body.optString("msg1").ifBlank { "실시간 종목 구독이 거부되었습니다." }
                            onEvent(KisRealtimeEvent.Failure(IOException("KIS 실시간 요청에 실패했습니다. (code=$code, reason=$reason)")))
                            webSocket.cancel()
                        }
                    }
                },
            )
        }

        private fun subscriptionMessage(productCode: String): String {
            val trId = when (market) {
                KisStockMarket.KRX -> KRX_EXECUTION_TR_ID
                KisStockMarket.NXT -> NXT_EXECUTION_TR_ID
                KisStockMarket.UNIFIED -> UNIFIED_EXECUTION_TR_ID
            }
            return JSONObject()
                .put(
                    "header",
                    JSONObject()
                        .put("approval_key", approvalKey)
                        .put("custtype", "P")
                        .put("tr_type", "1")
                        .put("content-type", "utf-8"),
                )
                .put(
                    "body",
                    JSONObject().put(
                        "input",
                        JSONObject()
                            .put("tr_id", trId)
                            .put("tr_key", productCode),
                    ),
                )
                .toString()
        }

        private fun parsePrices(text: String): List<KisRealtimePrice> {
            val parts = text.split("|", limit = 4)
            if (parts.size < 4 || parts[1] !in setOf(KRX_EXECUTION_TR_ID, NXT_EXECUTION_TR_ID, UNIFIED_EXECUTION_TR_ID)) {
                return emptyList()
            }
            val recordCount = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: 1
            val fields = parts[3].split("^")
            val fieldsPerRecord = fields.size / recordCount
            if (fieldsPerRecord < 3) return emptyList()
            return buildList {
                repeat(recordCount) { index ->
                    val offset = index * fieldsPerRecord
                    val productCode = fields.getOrNull(offset).orEmpty()
                    val currentPrice = fields.getOrNull(offset + 2)
                        ?.replace(",", "")
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                    if (productCode.isNotBlank() && currentPrice != null) {
                        add(KisRealtimePrice(productCode, currentPrice))
                    }
                }
            }
        }

        private fun sendPong(webSocket: WebSocket, text: String): Boolean {
            // KIS sends its heartbeat as a text payload but requires the same payload in a PONG frame.
            return runCatching {
                val method = webSocket.javaClass.getMethod("pong", ByteString::class.java)
                method.invoke(webSocket, text.encodeUtf8()) as? Boolean ?: false
            }.getOrDefault(false)
        }

        override fun close() {
            if (!isClosed.compareAndSet(false, true)) return
            scope.cancel()
            webSocket?.close(1000, "monitoring session changed")
            webSocket = null
        }
    }
}
