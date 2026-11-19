package com.habittracker.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal sealed interface OfferResult {
    data class Available(val delivered: Long) : OfferResult
    data class Unavailable(val reason: String) : OfferResult
    data class Failed(val reason: String) : OfferResult
}

private val catalogPath = Regex("/(?:ns/)?catalog/([0-9]+)/?")
private val storePath = Regex("/([A-Za-z0-9._-]+)/products/([0-9]+)/?")
private val coupangPath = Regex("/(?:vp|vm)/products/([0-9]+)/?")

internal fun offerUrl(raw: String): String? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val path = url.encodedPath
    return when (url.host) {
        "search.shopping.naver.com", "msearch.shopping.naver.com" -> catalogPath.matchEntire(path)?.let {
            "https://search.shopping.naver.com/catalog/${it.groupValues[1]}"
        }
        "brand.naver.com", "m.brand.naver.com", "smartstore.naver.com", "m.smartstore.naver.com" ->
            storePath.matchEntire(path)?.let { "https://${url.host.removePrefix("m.")}${path.trimEnd('/')}" }
        "www.coupang.com", "m.coupang.com", "coupang.com" -> {
            val id = coupangPath.matchEntire(path)?.groupValues?.get(1) ?: return null
            val clean = url.newBuilder().host("www.coupang.com").encodedPath("/vp/products/$id").query(null).fragment(null)
            for (name in listOf("itemId", "vendorItemId")) {
                val value = url.queryParameter(name) ?: continue
                if (!value.matches(Regex("[0-9]+"))) return null
                clean.addQueryParameter(name, value)
            }
            clean.build().toString()
        }
        else -> null
    }
}

internal fun offerSite(url: String) = when (url.toHttpUrlOrNull()?.host) {
    "www.coupang.com" -> "쿠팡"
    "search.shopping.naver.com" -> "네이버 가격비교"
    "brand.naver.com" -> "네이버 브랜드스토어"
    else -> "네이버 스마트스토어"
}

private val shareClient by lazy {
    OkHttpClient.Builder().followRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()
}

internal fun resolveOfferUrl(raw: String): String? {
    offerUrl(raw)?.let { return it }
    var url = raw.trim().toHttpUrlOrNull() ?: return null
    repeat(5) {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.host !in setOf("naver.me", "link.coupang.com", "coupa.ng")) return null
        val next = shareClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isRedirect) return null
            response.header("Location")?.let(url::resolve) ?: return null
        }
        offerUrl(next.toString())?.let { return it }
        url = next
    }
    return null
}

private fun sameOffer(expected: String, actual: String): Boolean {
    val target = offerUrl(actual)?.toHttpUrlOrNull() ?: return false
    val source = expected.toHttpUrlOrNull() ?: return false
    return source.host == target.host && source.encodedPath == target.encodedPath &&
        source.queryParameterNames.all { target.queryParameter(it) == source.queryParameter(it) }
}

@SuppressLint("SetJavaScriptEnabled")
internal suspend fun readHouseholdOffer(context: Context, link: HouseholdOfferLink): OfferResult {
    val address = requireNotNull(offerUrl(link.url)) { "지원하는 상품 주소가 아닙니다." }
    val script = withContext(Dispatchers.IO) {
        context.assets.open("household_offer.js").bufferedReader().use { it.readText() }
    }
    return withContext(Dispatchers.Main.immediate) {
        val browser = WebView(context)
        val loaded = CompletableDeferred<Unit>()
        var problem: String? = null
        var gone = false
        var missingPage = false
        var rendererGone = false
        try {
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkImage = true
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame || sameOffer(address, request.url.toString())) return false
                    problem = "상품 주소 또는 판매 옵션이 변경됐습니다."
                    loaded.complete(Unit)
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    rendererGone = true
                    problem = "상품 페이지 표시가 중단됐습니다."
                    loaded.complete(Unit)
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { problem = "접속 실패 (${error.errorCode})"; loaded.complete(Unit) }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (!request.isForMainFrame) return
                    if (response.statusCode == 410 && sameOffer(address, request.url.toString())) {
                        gone = true
                        loaded.complete(Unit)
                    } else if (response.statusCode == 404 && sameOffer(address, request.url.toString())) {
                        missingPage = true
                    } else {
                        problem = "페이지 응답 오류 (HTTP ${response.statusCode})"
                        loaded.complete(Unit)
                    }
                }
            }
            browser.loadUrl(address)
            withTimeoutOrNull(30_000L) {
                loaded.await()
                val evaluate = "($script)(${JSONObject.quote(link.option)}, $missingPage)"
                while (true) {
                    problem?.let { return@withTimeoutOrNull OfferResult.Failed(it) }
                    if (gone) return@withTimeoutOrNull OfferResult.Unavailable("판매 페이지 삭제")
                    if (!sameOffer(address, browser.url.orEmpty())) return@withTimeoutOrNull OfferResult.Failed("등록한 상품 주소를 확인할 수 없습니다.")
                    val raw = suspendCancellableCoroutine<String> { continuation ->
                        browser.evaluateJavascript(evaluate) { if (continuation.isActive) continuation.resume(it ?: "null") }
                    }
                    if (raw.startsWith("{")) {
                        val data = JSONObject(raw)
                        if (!sameOffer(address, data.optString("url"))) return@withTimeoutOrNull OfferResult.Failed("상품 옵션이 변경됐습니다.")
                        if (data.has("unavailable")) return@withTimeoutOrNull OfferResult.Unavailable(data.getString("unavailable"))
                        if (data.has("error")) return@withTimeoutOrNull OfferResult.Failed(data.getString("error"))
                        val total = data.getLong("total")
                        if (total > 0) return@withTimeoutOrNull OfferResult.Available(total)
                    }
                    delay(1500)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } ?: OfferResult.Failed("판매 상태 또는 배송비 포함 가격을 확인하지 못했습니다.")
        } finally {
            if (!rendererGone) browser.stopLoading()
            browser.destroy()
        }
    }
}
