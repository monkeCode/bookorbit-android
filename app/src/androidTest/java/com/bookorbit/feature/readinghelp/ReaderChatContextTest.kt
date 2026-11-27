package com.bookorbit.feature.readinghelp

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real DOM Range and production Foliate CFI round trips; synthetic text only, no server/model. */
class ReaderChatContextTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @Test fun exactBoundariesAndChapterAnchorsExcludeUnreadText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        var result = "not completed"
        val callback = object {
            @JavascriptInterface fun complete(value: String) { result = value; done.countDown() }
            @JavascriptInterface fun stage(value: String) { result = value }
        }
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val html = instrumentation.context.assets.open("reader-chat-context-test.html").bufferedReader().use { it.readText() }
        lateinit var webView: WebView
        activity.scenario.onActivity { host ->
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instrumentation.targetContext)).build()
            webView = WebView(host).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(callback, "TestResult")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            result = "Synthetic console: ${message.message()}"; done.countDown()
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
                        if (request.url.path == "/assets/reader/chat-test.html") WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                        else {
                            requests.add(request.url.path.orEmpty())
                            loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                        }
                }
            }
            host.setContentView(webView)
            webView.loadUrl("https://appassets.androidplatform.net/assets/reader/chat-test.html")
        }
        try {
            val completed = done.await(40, TimeUnit.SECONDS)
            assertTrue("Synthetic DOM checks timed out: $result; $requests", completed)
            assertEquals("ok:10", result)
        } finally { instrumentation.runOnMainSync { webView.destroy() } }
    }
}
