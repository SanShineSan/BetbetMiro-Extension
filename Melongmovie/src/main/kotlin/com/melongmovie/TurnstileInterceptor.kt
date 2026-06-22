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
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

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
        cookieManager.setCookie(domainUrl, "_as_ipin_lc=id-ID; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_tz=Asia/Jakarta; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_ct=ID; path=/; SameSite=Strict")
        cookieManager.flush()

        val existingCookies = cookieManager.getCookie(domainUrl) ?: ""
        if (hasTargetCookie(existingCookies)) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", existingCookies)
                    .build()
            )
            if (response.code != 403 && response.code != 503) return response

            response.close()
            clearTargetCookies(cookieManager, domainUrl)
        }

        val context = AcraApplication.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        val userAgentRef = AtomicReference(originalRequest.header("User-Agent") ?: "")
        val webViewRef = AtomicReference<WebView?>(null)

        handler.post {
            val webView = WebView(context)
            webViewRef.set(webView)

            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                val userAgent = userAgentRef.get()
                if (userAgent.isNotBlank()) userAgentString = userAgent
            }

            userAgentRef.set(webView.settings.userAgentString)

            webView.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    cookieManager.flush()
                }
            }

            webView.loadUrl(url)
        }

        for (i in 0 until 60) {
            Thread.sleep(1000)
            val cookies = cookieManager.getCookie(domainUrl) ?: ""
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

        val finalCookies = cookieManager.getCookie(domainUrl) ?: ""
        val finalUserAgent = userAgentRef.get()

        return chain.proceed(
            originalRequest.newBuilder()
                .apply { if (finalUserAgent.isNotBlank()) header("User-Agent", finalUserAgent) }
                .apply { if (finalCookies.isNotBlank()) header("Cookie", finalCookies) }
                .build()
        )
    }

    private fun hasTargetCookie(cookies: String): Boolean {
        return targetCookies.any { cookieName -> cookies.contains("$cookieName=") }
    }

    private fun clearTargetCookies(cookieManager: CookieManager, domainUrl: String) {
        targetCookies.forEach { cookieName ->
            cookieManager.setCookie(domainUrl, "$cookieName=; Max-Age=0; path=/; Secure")
        }
        cookieManager.flush()
    }
}
