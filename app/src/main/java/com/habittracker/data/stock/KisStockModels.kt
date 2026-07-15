package com.habittracker.data.stock

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class KisEnvironment(
    val label: String,
    val apiValue: String,
) {
    REAL("실전", "real"),
}

enum class KisOrderSide(val label: String) {
    BUY("매수"),
    SELL("매도"),
}

data class KisAccountRef(
    val accountNumber: String,
    val accountProductCode: String,
)

data class KisApiConfig(
    val environment: KisEnvironment,
    val appKey: String,
    val appSecret: String,
    val accountNumber: String,
    val accountProductCode: String,
)

data class KisCashOrderDraft(
    val side: KisOrderSide,
    val productCode: String,
    val orderDivisionCode: String,
    val orderQuantity: String,
    val orderUnitPrice: String,
    val exchangeIdDivisionCode: String,
    val sellType: String,
    val conditionPrice: String,
)

data class KisCurrentPrice(
    val productCode: String,
    val currentPrice: String,
    val changeRatePercent: Double,
    val baseDateTime: String,
)

data class KisSellableQuantity(
    val productCode: String,
    val quantity: String,
)

data class KisBalanceStock(
    val productCode: String,
    val productName: String,
    val quantity: String,
    val averagePrice: String,
    val currentPrice: String,
)

data class KisMarketCapStock(
    val rank: Int,
    val productCode: String,
    val productName: String,
)

data class KisOrderResult(
    val orderNumber: String,
    val orderTime: String,
    val message: String,
)

data class KisAccessToken(
    val value: String,
    val expiresAt: LocalDateTime,
)

interface KisDomesticStockGateway {
    suspend fun getCurrentPrice(productCode: String): KisCurrentPrice

    suspend fun getBalance(account: KisAccountRef): List<KisBalanceStock>

    suspend fun getSellableQuantity(account: KisAccountRef, productCode: String): KisSellableQuantity

    suspend fun placeCashOrder(order: KisCashOrderDraft): KisOrderResult
}

internal class KisDomesticStockClient {
    private companion object {
        const val BASE_URL = "https://openapi.koreainvestment.com:9443"
        const val TOKEN_PATH = "/oauth2/tokenP"
        const val BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance"
        const val BALANCE_TR_ID = "TTTC8434R"
        const val CURRENT_PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price"
        const val CURRENT_PRICE_TR_ID = "FHKST01010100"
        const val INDEX_PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-index-price"
        const val INDEX_PRICE_TR_ID = "FHPUP02100000"
        const val HOLIDAY_PATH = "/uapi/domestic-stock/v1/quotations/chk-holiday"
        const val HOLIDAY_TR_ID = "CTCA0903R"
        const val BUYABLE_PATH = "/uapi/domestic-stock/v1/trading/inquire-psbl-order"
        const val BUYABLE_TR_ID = "TTTC8908R"
        const val SELLABLE_PATH = "/uapi/domestic-stock/v1/trading/inquire-psbl-sell"
        const val SELLABLE_TR_ID = "TTTC8408R"
        const val CASH_ORDER_PATH = "/uapi/domestic-stock/v1/trading/order-cash"
        const val CASH_BUY_TR_ID = "TTTC0012U"
        const val CASH_SELL_TR_ID = "TTTC0011U"
        const val DAILY_EXECUTION_PATH = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
        const val DAILY_EXECUTION_TR_ID = "TTTC0081R"
        const val MARKET_CAP_PATH = "/uapi/domestic-stock/v1/ranking/market-cap"
        const val MARKET_CAP_TR_ID = "FHPST01740000"
        const val MARKET_CAP_STOCK_LIMIT = 20
        const val MAX_BALANCE_PAGES = 10
        const val HTTP_TIMEOUT_MILLIS = 10_000
        val tokenExpiredAtFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    fun issueAccessToken(config: KisApiConfig): KisAccessToken {
        val response = requestJson(
            url = "$BASE_URL$TOKEN_PATH",
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "text/plain",
                "charset" to "UTF-8",
            ),
            body = JSONObject()
                .put("grant_type", "client_credentials")
                .put("appkey", config.appKey)
                .put("appsecret", config.appSecret),
        ).body
        val accessToken = response.optString("access_token").takeIf(String::isNotBlank)
            ?: throw IOException("KIS 접근토큰 응답에 토큰이 없습니다.")
        val expiresAt = response.optString("access_token_token_expired")
            .takeIf(String::isNotBlank)
            ?.let { expiredAt ->
                runCatching { LocalDateTime.parse(expiredAt, tokenExpiredAtFormatter) }
                    .getOrElse { throw IOException("KIS 접근토큰 만료시간을 해석하지 못했습니다.", it) }
            }
            ?: response.optLong("expires_in")
                .takeIf { it > 0L }
                ?.let { LocalDateTime.now().plusSeconds(it) }
            ?: throw IOException("KIS 접근토큰 응답에 만료시간이 없습니다.")
        return KisAccessToken(accessToken, expiresAt)
    }

    fun getBalance(config: KisApiConfig, accessToken: String): List<KisBalanceStock> {
        val stocks = mutableListOf<KisBalanceStock>()
        var foreignKey = ""
        var nextKey = ""
        var continuation = ""
        var page = 0

        do {
            val params = linkedMapOf(
                "CANO" to config.accountNumber,
                "ACNT_PRDT_CD" to config.accountProductCode,
                "AFHR_FLPR_YN" to "N",
                "OFL_YN" to "",
                "INQR_DVSN" to "02",
                "UNPR_DVSN" to "01",
                "FUND_STTL_ICLD_YN" to "N",
                "FNCG_AMT_AUTO_RDPT_YN" to "N",
                "PRCS_DVSN" to "00",
                "CTX_AREA_FK100" to foreignKey,
                "CTX_AREA_NK100" to nextKey,
            )
            val response = requestJson(
                url = "$BASE_URL$BALANCE_PATH?${params.toQueryString()}",
                method = "GET",
                headers = buildMap {
                    put("Content-Type", "application/json")
                    put("Accept", "text/plain")
                    put("authorization", "Bearer $accessToken")
                    put("appkey", config.appKey)
                    put("appsecret", config.appSecret)
                    put("tr_id", BALANCE_TR_ID)
                    put("custtype", "P")
                    if (continuation.isNotEmpty()) put("tr_cont", continuation)
                },
            )
            val output = response.body.optJSONArray("output1")
                ?: throw IOException("KIS 잔고 응답에 보유 종목이 없습니다.")
            for (index in 0 until output.length()) {
                val stock = output.getJSONObject(index)
                val holdingQuantity = stock.optString("hldg_qty")
                    .takeIf(String::isNotBlank)
                    ?: stock.optString("ord_psbl_qty")
                if (holdingQuantity.toLongOrNull()?.let { it > 0L } == true) {
                    stocks += KisBalanceStock(
                        productCode = stock.optString("pdno"),
                        productName = stock.optString("prdt_name"),
                        quantity = holdingQuantity,
                        averagePrice = stock.optString("pchs_avg_pric"),
                        currentPrice = stock.optString("prpr"),
                    )
                }
            }

            foreignKey = response.body.optString("ctx_area_fk100")
            nextKey = response.body.optString("ctx_area_nk100")
            continuation = if (response.trContinuation in setOf("M", "F")) "N" else ""
            page += 1
        } while (continuation.isNotEmpty() && page < MAX_BALANCE_PAGES)

        return stocks.filter { it.productCode.isNotBlank() }.distinctBy(KisBalanceStock::productCode)
    }

    fun getCurrentPrice(config: KisApiConfig, accessToken: String, productCode: String): KisCurrentPrice {
        val params = linkedMapOf(
            "FID_COND_MRKT_DIV_CODE" to "J",
            "FID_INPUT_ISCD" to productCode,
        )
        val output = requestJson(
            url = "$BASE_URL$CURRENT_PRICE_PATH?${params.toQueryString()}",
            method = "GET",
            headers = authorizedHeaders(config, accessToken, CURRENT_PRICE_TR_ID),
        ).body.requireOutputObject("KIS 현재가 응답")
        val currentPrice = output.optString("stck_prpr").takeIf(String::isNotBlank)
            ?: throw IOException("KIS 현재가 응답에 가격이 없습니다. (종목=$productCode)")
        return KisCurrentPrice(
            productCode = productCode,
            currentPrice = currentPrice,
            changeRatePercent = output.optString("prdy_ctrt").toDoubleOrNull() ?: 0.0,
            baseDateTime = LocalDateTime.now().toString(),
        )
    }

    fun getIndexPrice(
        config: KisApiConfig,
        accessToken: String,
        indexCode: String,
    ): KisMarketIndex {
        val params = linkedMapOf(
            "FID_COND_MRKT_DIV_CODE" to "U",
            "FID_INPUT_ISCD" to indexCode,
        )
        val output = requestJson(
            url = "$BASE_URL$INDEX_PRICE_PATH?${params.toQueryString()}",
            method = "GET",
            headers = authorizedHeaders(config, accessToken, INDEX_PRICE_TR_ID),
        ).body.requireOutputObject("KIS 지수 응답")
        val currentValue = output.optString("bstp_nmix_prpr")
            .ifBlank { output.optString("bstp_nmix_prdy_vrss") }
        val changeRate = output.optString("bstp_nmix_prdy_ctrt")
            .ifBlank { output.optString("prdy_ctrt") }
            .toDoubleOrNull()
            ?: throw IOException("KIS 지수 응답에 전일 대비율이 없습니다. (지수=$indexCode)")
        return KisMarketIndex(
            code = indexCode,
            name = when (indexCode) {
                "0001" -> "코스피"
                "1001" -> "코스닥"
                "2001" -> "코스피200"
                else -> indexCode
            },
            currentValue = currentValue,
            changeRatePercent = changeRate,
        )
    }

    fun isMarketOpenDay(
        config: KisApiConfig,
        accessToken: String,
        date: LocalDate,
    ): Boolean {
        val dateText = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val params = linkedMapOf(
            "BASS_DT" to dateText,
            "CTX_AREA_FK" to "",
            "CTX_AREA_NK" to "",
        )
        val response = requestJson(
            url = "$BASE_URL$HOLIDAY_PATH?${params.toQueryString()}",
            method = "GET",
            headers = authorizedHeaders(config, accessToken, HOLIDAY_TR_ID),
        ).body
        val output = response.optJSONArray("output")
            ?: response.optJSONObject("output")?.let { single -> org.json.JSONArray().put(single) }
            ?: throw IOException("KIS 휴장일 응답에 output이 없습니다.")
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("bass_dt") == dateText) {
                return item.optString("opnd_yn").equals("Y", ignoreCase = true)
            }
        }
        throw IOException("KIS 휴장일 응답에 기준일자가 없습니다. (일자=$dateText)")
    }

    fun getBuyableQuantity(
        config: KisApiConfig,
        accessToken: String,
        productCode: String,
        unitPrice: String,
        orderDivisionCode: String,
    ): KisBuyableQuantity {
        val params = linkedMapOf(
            "CANO" to config.accountNumber,
            "ACNT_PRDT_CD" to config.accountProductCode,
            "PDNO" to productCode,
            "ORD_UNPR" to unitPrice,
            "ORD_DVSN" to orderDivisionCode,
            "CMA_EVLU_AMT_ICLD_YN" to "N",
            "OVRS_ICLD_YN" to "N",
        )
        val output = requestJson(
            url = "$BASE_URL$BUYABLE_PATH?${params.toQueryString()}",
            method = "GET",
            headers = authorizedHeaders(config, accessToken, BUYABLE_TR_ID),
        ).body.requireOutputObject("KIS 매수가능 응답")
        return KisBuyableQuantity(
            quantity = output.firstLong("nrcvb_buy_qty", "max_buy_qty"),
            amount = output.firstLong("nrcvb_buy_amt", "max_buy_amt"),
        )
    }

    fun getSellableQuantity(
        config: KisApiConfig,
        accessToken: String,
        productCode: String,
    ): KisSellableQuantity {
        val params = linkedMapOf(
            "CANO" to config.accountNumber,
            "ACNT_PRDT_CD" to config.accountProductCode,
            "PDNO" to productCode,
        )
        val output = requestJson(
            url = "$BASE_URL$SELLABLE_PATH?${params.toQueryString()}",
            method = "GET",
            headers = authorizedHeaders(config, accessToken, SELLABLE_TR_ID),
        ).body.requireOutputObject("KIS 매도가능 응답")
        val quantity = output.firstLong("ord_psbl_qty", "sell_psbl_qty", "psbl_qty")
        return KisSellableQuantity(productCode = productCode, quantity = quantity.toString())
    }

    fun placeCashOrder(
        config: KisApiConfig,
        accessToken: String,
        order: KisCashOrderDraft,
    ): KisOrderResult {
        val body = JSONObject()
            .put("CANO", config.accountNumber)
            .put("ACNT_PRDT_CD", config.accountProductCode)
            .put("PDNO", order.productCode)
            .put("ORD_DVSN", order.orderDivisionCode)
            .put("ORD_QTY", order.orderQuantity)
            .put("ORD_UNPR", order.orderUnitPrice)
            .put("EXCG_ID_DVSN_CD", order.exchangeIdDivisionCode)
            .put("SLL_TYPE", if (order.side == KisOrderSide.SELL) order.sellType else "")
            .put("CNDT_PRIC", order.conditionPrice)
        val response = requestJson(
            url = "$BASE_URL$CASH_ORDER_PATH",
            method = "POST",
            headers = authorizedHeaders(
                config = config,
                accessToken = accessToken,
                trId = if (order.side == KisOrderSide.BUY) CASH_BUY_TR_ID else CASH_SELL_TR_ID,
            ),
            body = body,
        ).body
        val output = response.requireOutputObject("KIS 현금주문 응답")
        val orderNumber = output.optString("ODNO").takeIf(String::isNotBlank)
            ?: throw IOException("KIS 현금주문 응답에 주문번호가 없습니다.")
        return KisOrderResult(
            orderNumber = orderNumber,
            orderTime = output.optString("ORD_TMD").ifBlank { LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) },
            message = response.optString("msg1"),
        )
    }

    fun getOrderExecutions(
        config: KisApiConfig,
        accessToken: String,
        startDate: LocalDate,
        endDate: LocalDate,
        orderNumber: String = "",
    ): List<KisOrderExecution> {
        val executions = mutableListOf<KisOrderExecution>()
        var foreignKey = ""
        var nextKey = ""
        var continuation = ""
        var page = 0
        do {
            val params = linkedMapOf(
                "CANO" to config.accountNumber,
                "ACNT_PRDT_CD" to config.accountProductCode,
                "INQR_STRT_DT" to startDate.format(DateTimeFormatter.BASIC_ISO_DATE),
                "INQR_END_DT" to endDate.format(DateTimeFormatter.BASIC_ISO_DATE),
                "SLL_BUY_DVSN_CD" to "00",
                "PDNO" to "",
                "CCLD_DVSN" to "00",
                "INQR_DVSN" to "00",
                "INQR_DVSN_3" to "00",
                "ORD_GNO_BRNO" to "",
                "ODNO" to orderNumber,
                "INQR_DVSN_1" to "",
                "CTX_AREA_FK100" to foreignKey,
                "CTX_AREA_NK100" to nextKey,
                "EXCG_ID_DVSN_CD" to "ALL",
            )
            val response = requestJson(
                url = "$BASE_URL$DAILY_EXECUTION_PATH?${params.toQueryString()}",
                method = "GET",
                headers = authorizedHeaders(config, accessToken, DAILY_EXECUTION_TR_ID, continuation),
            )
            val output = response.body.optJSONArray("output1") ?: break
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                val number = item.optString("odno")
                if (number.isBlank() || (orderNumber.isNotBlank() && number != orderNumber)) continue
                val side = when (item.optString("sll_buy_dvsn_cd")) {
                    "01" -> KisOrderSide.SELL
                    "02" -> KisOrderSide.BUY
                    else -> if (item.optString("sll_buy_dvsn_cd_name").contains("매도")) KisOrderSide.SELL else KisOrderSide.BUY
                }
                val filledQuantity = item.firstLong("tot_ccld_qty", "ccld_qty")
                val filledAveragePrice = item.firstLong("avg_prvs", "avg_ccld_unpr", "ccld_unpr")
                    .takeIf { it > 0L }
                    ?: item.firstLong("tot_ccld_amt").takeIf { it > 0L && filledQuantity > 0L }
                        ?.div(filledQuantity)
                val date = runCatching { LocalDate.parse(item.optString("ord_dt"), DateTimeFormatter.BASIC_ISO_DATE) }
                    .getOrDefault(startDate)
                executions += KisOrderExecution(
                    orderNumber = number,
                    productCode = item.optString("pdno"),
                    productName = item.optString("prdt_name"),
                    side = side,
                    orderDate = date,
                    orderTime = item.optString("ord_tmd"),
                    orderedQuantity = item.firstLong("ord_qty"),
                    filledQuantity = filledQuantity,
                    filledAveragePrice = filledAveragePrice,
                    remainingQuantity = item.firstLong("rmn_qty"),
                    canceledQuantity = item.firstLong("cnc_cfrm_qty"),
                    rejectedQuantity = item.firstLong("rjct_qty"),
                    isCanceled = item.optString("cncl_yn").equals("Y", ignoreCase = true),
                )
            }
            foreignKey = response.body.optString("ctx_area_fk100")
            nextKey = response.body.optString("ctx_area_nk100")
            continuation = if (response.trContinuation in setOf("M", "F")) "N" else ""
            page += 1
        } while (continuation.isNotEmpty() && page < MAX_BALANCE_PAGES)
        return executions.distinctBy { Triple(it.orderDate, it.orderNumber, it.productCode) }
    }

    fun getMarketCapRanking(config: KisApiConfig, accessToken: String): List<KisMarketCapStock> {
        val params = linkedMapOf(
            "fid_input_price_2" to "",
            "fid_cond_mrkt_div_code" to "J",
            "fid_cond_scr_div_code" to "20174",
            "fid_div_cls_code" to "0",
            "fid_input_iscd" to "0000",
            "fid_trgt_cls_code" to "0",
            "fid_trgt_exls_cls_code" to "0",
            "fid_input_price_1" to "",
            "fid_vol_cnt" to "",
        )
        val response = requestJson(
            url = "$BASE_URL$MARKET_CAP_PATH?${params.toQueryString()}",
            method = "GET",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "text/plain",
                "authorization" to "Bearer $accessToken",
                "appkey" to config.appKey,
                "appsecret" to config.appSecret,
                "tr_id" to MARKET_CAP_TR_ID,
                "custtype" to "P",
            ),
        )
        val output = response.body.optJSONArray("output")
            ?: throw IOException("KIS 시가총액 순위 응답에 종목이 없습니다.")
        val stocks = mutableListOf<KisMarketCapStock>()
        for (index in 0 until output.length()) {
            val stock = output.getJSONObject(index)
            val rank = stock.optString("data_rank").toIntOrNull() ?: continue
            val productCode = stock.optString("mksc_shrn_iscd")
            val productName = stock.optString("hts_kor_isnm")
            if (productCode.isNotBlank() && productName.isNotBlank()) {
                stocks += KisMarketCapStock(
                    rank = rank,
                    productCode = productCode,
                    productName = productName,
                )
            }
        }
        return stocks
            .distinctBy(KisMarketCapStock::productCode)
            .sortedBy(KisMarketCapStock::rank)
            .take(MARKET_CAP_STOCK_LIMIT)
    }

    private fun requestJson(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: JSONObject? = null,
    ): JsonResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = HTTP_TIMEOUT_MILLIS
            connection.readTimeout = HTTP_TIMEOUT_MILLIS
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }

            val statusCode = connection.responseCode
            val responseText = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val responseBody = runCatching { JSONObject(responseText) }
                .getOrElse { throw IOException("KIS API 응답을 해석하지 못했습니다. (HTTP $statusCode)", it) }
            val resultCode = responseBody.optString("rt_cd")
            if (statusCode !in 200..299 || (resultCode.isNotEmpty() && resultCode != "0")) {
                val apiCode = responseBody.optString("msg_cd").ifBlank { responseBody.optString("error_code") }
                val apiMessage = responseBody.optString("msg1")
                    .ifBlank { responseBody.optString("error_description") }
                    .take(200)
                throw IOException("KIS API 요청에 실패했습니다. (HTTP $statusCode, code=$apiCode, message=$apiMessage)")
            }
            JsonResponse(
                body = responseBody,
                trContinuation = connection.getHeaderField("tr_cont").orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun Map<String, String>.toQueryString(): String = entries.joinToString("&") { (key, value) ->
        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        "$encodedKey=$encodedValue"
    }

    private fun authorizedHeaders(
        config: KisApiConfig,
        accessToken: String,
        trId: String,
        continuation: String = "",
    ): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "text/plain")
        put("authorization", "Bearer $accessToken")
        put("appkey", config.appKey)
        put("appsecret", config.appSecret)
        put("tr_id", trId)
        put("custtype", "P")
        if (continuation.isNotEmpty()) put("tr_cont", continuation)
    }

    private fun JSONObject.requireOutputObject(label: String): JSONObject {
        optJSONObject("output")?.let { return it }
        optJSONArray("output")?.optJSONObject(0)?.let { return it }
        throw IOException("${label}에 output이 없습니다.")
    }

    private fun JSONObject.firstLong(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
        optString(key).replace(",", "").toLongOrNull()
    } ?: 0L

    private data class JsonResponse(
        val body: JSONObject,
        val trContinuation: String,
    )
}
