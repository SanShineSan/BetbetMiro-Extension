package com.melongmovie

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.AcraApplication
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicReference

/**
 * AnimeSail-style Cloudflare/Turnstile request handler adapted for Melongmovie.
 *
 * Important flow:
 * 1) Try the normal request first. If Melongmovie is currently readable, return it immediately.
 * 2) Only when Cloudflare/Turnstile HTML or 403/503/429 appears, open the same URL in WebView.
 * 3) Let WebView/JavaScript/CookieManager obtain the challenge cookie.
 * 4) Continue the original request with WebView cookies + WebView User-Agent.
 */
class TurnstileInterceptor(
    private val targetCookies: List<String> = listOf("cf_clearance", "_as_turnstile")
) : Interceptor {

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)

        val existingCookies = collectCookies(cookieManager, domainUrl, url)
        val firstRequest = originalRequest.withCookies(existingCookies)
        val firstResponse = chain.proceed(firstRequest)

        if (!needsWebViewChallenge(firstResponse)) {
            return canonicalizeResponse(firstResponse)
        }

        firstResponse.close()
        clearTargetCookies(cookieManager, domainUrl)

        val context = AcraApplication.context
            ?: return canonicalizeResponse(chain.proceed(originalRequest.withCookies(existingCookies)))

        val handler = Handler(Looper.getMainLooper())
        val userAgentRef = AtomicReference(originalRequest.header("User-Agent") ?: "")
        val webViewRef = AtomicReference<WebView?>(null)
        val lastUrlRef = AtomicReference(url)

        handler.post {
            val wv = WebView(context)
            webViewRef.set(wv)

            cookieManager.setAcceptThirdPartyCookies(wv, true)

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                val ua = userAgentRef.get()
                if (ua.isNotBlank()) userAgentString = ua
            }

            userAgentRef.set(wv.settings.userAgentString)

            wv.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    if (!finishedUrl.isNullOrBlank()) lastUrlRef.set(finishedUrl)
                    cookieManager.flush()
                }
            }

            wv.loadUrl(url)
        }

        for (i in 0 until 60) {
            Thread.sleep(1000)
            val cookies = collectCookies(cookieManager, domainUrl, lastUrlRef.get())
            if (hasTargetCookie(cookies)) {
                cookieManager.flush()
                break
            }
        }

        handler.post {
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                destroy()
            }
        }

        val finalCookies = collectCookies(cookieManager, domainUrl, lastUrlRef.get())
        val finalUA = userAgentRef.get()

        val finalResponse = chain.proceed(
            originalRequest.newBuilder()
                .apply { if (finalUA.isNotBlank()) header("User-Agent", finalUA) }
                .apply { if (finalCookies.isNotBlank()) header("Cookie", finalCookies) }
                .build()
        )

        return canonicalizeResponse(finalResponse)
    }

    private fun needsWebViewChallenge(response: Response): Boolean {
        if (response.code == 403 || response.code == 503 || response.code == 429) return true

        val body = runCatching {
            response.peekBody(256_000).string().lowercase()
        }.getOrDefault("")

        return body.contains("just a moment") ||
            body.contains("checking your browser") ||
            body.contains("cf-turnstile") ||
            body.contains("challenges.cloudflare.com") ||
            body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("cloudflare") && body.contains("challenge") ||
            body.contains("<title>loading") ||
            body.contains("loading..")
    }

    private fun hasTargetCookie(cookies: String): Boolean {
        return targetCookies.any { cookies.contains("$it=", ignoreCase = true) }
    }

    private fun clearTargetCookies(cookieManager: CookieManager, domainUrl: String) {
        targetCookies.forEach { name ->
            cookieManager.setCookie(domainUrl, "$name=; Max-Age=0; path=/; Secure")
        }
        cookieManager.flush()
    }

    private fun collectCookies(cookieManager: CookieManager, vararg urls: String?): String {
        return urls.asSequence()
            .filterNotNull()
            .filter { it.isNotBlank() }
            .flatMap { (cookieManager.getCookie(it) ?: "").split(';').asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
    }

    private fun Request.withCookies(cookies: String): Request {
        return if (cookies.isBlank()) this else newBuilder().header("Cookie", cookies).build()
    }

    private fun canonicalizeResponse(response: Response): Response {
        val body = response.body ?: return response
        val contentType = body.contentType()
        val type = contentType?.toString().orEmpty().lowercase()

        if (!type.contains("text") && !type.contains("html") && !type.contains("json") && !type.contains("javascript")) {
            return response
        }

        val text = runCatching { body.string() }.getOrNull() ?: return response
        val fixed = canonicalizeMelongHosts(text)

        return response.newBuilder()
            .body(fixed.toResponseBody(contentType))
            .build()
    }

    private fun canonicalizeMelongHosts(text: String): String {
        return text
            .replace("https://tv11.melongmovies.com", "https://tv12.melongmovies.com")
            .replace("http://tv11.melongmovies.com", "https://tv12.melongmovies.com")
            .replace("//tv11.melongmovies.com", "//tv12.melongmovies.com")
    }
}
