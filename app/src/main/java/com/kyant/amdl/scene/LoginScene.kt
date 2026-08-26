package com.kyant.amdl.scene

import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.ui.Block
import com.kyant.amdl.ui.Palette
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LoginScene(appState: AppState) {
    var webViewRef: WebView? by remember { mutableStateOf(null) }
    var isWebViewLoaded by remember { mutableStateOf(false) }
    var webViewProgress by remember { mutableFloatStateOf(0f) }

    val clearData: WebView.() -> Unit = {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
        }
        clearCache(true)
        clearHistory()
        clearFormData()
        WebStorage.getInstance().deleteAllData()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearData()
                destroy()
                webViewRef = null
            }
        }
    }

    val imePadding = WindowInsets.ime.asPaddingValues()

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.card)
            .systemBarsPadding()
            .displayCutoutPadding()
            .graphicsLayer {
                translationY = -imePadding.calculateBottomPadding().toPx() * 0.5f
            }
    ) {
        Block {
            if (!isWebViewLoaded) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .wrapContentSize()
                    ) {
                        BasicText(
                            "在此登录 Apple Music",
                            style = TextStyle(
                                Palette.content,
                                28f.sp,
                                FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .wrapContentSize()
                    ) {
                        val progressIndicatorColor = Palette.accent
                        val progressAnimation = remember { Animatable(webViewProgress) }

                        LaunchedEffect(Unit) {
                            val animationSpec = spring<Float>(1f, 300f)
                            snapshotFlow { webViewProgress }.collectLatest { progress ->
                                progressAnimation.animateTo(progress, animationSpec)
                            }
                        }

                        Box(
                            Modifier
                                .size(48f.dp)
                                .drawBehind {
                                    val progress = progressAnimation.value
                                    drawArc(
                                        progressIndicatorColor,
                                        startAngle = -90f,
                                        sweepAngle = 360f * progress,
                                        useCenter = false,
                                        style = Stroke(2f.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .wrapContentWidth()
                    ) {
                        BasicText(
                            "加载中，可能需要十几秒 …",
                            style = TextStyle(
                                Palette.content.copy(0.6f),
                                16f.sp,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }
            }
        }

        AndroidView(
            factory = { context ->
                val webView = WebView(context)
                webView.clearData()
                webViewRef = webView

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                val lock = Any()
                var areTokensSet = false
                var devToken: String? = null
                var mediaUserToken: String? = null

                webView.apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                    visibility = WebView.GONE

                    settings.apply {
                        @Suppress("SetJavaScriptEnabled")
                        javaScriptEnabled = true
                        domStorageEnabled = true
                    }

                    webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                            return request.hasGesture()
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            if (url == null) return

                            if (url == "https://idmsa.apple.com/appleauth/jslog") {
                                visibility = WebView.VISIBLE
                                isWebViewLoaded = true
                            }

                            if (devToken == null) {
                                if (url.contains("devToken")) {
                                    try {
                                        devToken = url.toUri().getQueryParameter("devToken")
                                    } catch (_: Exception) {
                                    }
                                }
                            }

                            if (mediaUserToken == null) {
                                val cookies: String? = cookieManager.getCookie(url)
                                val prefix = "media-user-token="
                                if (cookies != null && cookies.contains(prefix)) {
                                    mediaUserToken =
                                        cookies
                                            .split(";")
                                            .find { it.trim().startsWith(prefix) }
                                            ?.trim()
                                            ?.removePrefix(prefix)
                                }
                            }

                            synchronized(lock) {
                                if (!areTokensSet && devToken != null && mediaUserToken != null) {
                                    appState.setTokens(
                                        AmTokens(
                                            devToken = devToken!!,
                                            mediaUserToken = mediaUserToken!!
                                        )
                                    )
                                    Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                                    appState.navBackStack -= Scene.Login
                                    areTokensSet = true
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            val js = """
(function() {
    var el = document.querySelector('.signin');
    if (el) {
        el.click();
        return true;
    }
    return false;
})();
""".trimIndent()
                            evaluateJavascript(js, null)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            webViewProgress = newProgress / 100f
                        }
                    }

                    loadUrl("https://music.apple.com")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
