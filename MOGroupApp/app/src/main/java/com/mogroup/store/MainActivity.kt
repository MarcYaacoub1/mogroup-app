package com.mogroup.store

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var errorView: View

    private var hasError = false
    private var failedUrl: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)

        setupInsets()
        setupWebView()
        setupSwipe()
        setupBackButton()

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            hasError = false
            webView.loadUrl(failedUrl ?: defaultHome())
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            loadFromIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null) loadFromIntent(intent)
    }

    // ---------------------------------------------------------------- setup

    private fun setupInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, if (imeVisible) ime.bottom else bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString MOGroupApp/1.0"
        }

        webView.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hasError = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                swipe.isRefreshing = false
                if (!hasError) errorView.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    hasError = true
                    failedUrl = request.url.toString()
                    swipe.isRefreshing = false
                    errorView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    true
                }
            }
        }
    }

    private fun setupSwipe() {
        swipe.setColorSchemeResources(R.color.brand)
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipe.setOnRefreshListener {
            if (hasError) {
                hasError = false
                webView.loadUrl(failedUrl ?: defaultHome())
            } else {
                webView.reload()
            }
        }
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    // ----------------------------------------------------------- navigation

    private fun defaultHome(): String =
        if (Locale.getDefault().language == "ar") HOME_AR else HOME

    private fun loadFromIntent(intent: Intent?) {
        val data = intent?.data
        val url = if (data != null && isSiteUri(data)) data.toString() else defaultHome()
        webView.loadUrl(url)
    }

    private fun isSiteUri(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") &&
            (host == SITE_HOST || host == "www.$SITE_HOST")
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            "http", "https" -> {
                val host = uri.host?.lowercase().orEmpty()
                if (EXTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    openExternal(uri)
                    true
                } else {
                    false
                }
            }
            "about", "javascript", "data", "blob" -> false
            "intent" -> {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> {
                openExternal(uri)
                true
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val SITE_HOST = "mogroup-comp.com"
        private const val HOME = "https://mogroup-comp.com/"
        private const val HOME_AR = "https://mogroup-comp.com/ar/"

        private val EXTERNAL_HOSTS = listOf(
            "wa.me", "api.whatsapp.com", "web.whatsapp.com", "whatsapp.com",
            "facebook.com", "instagram.com", "tiktok.com",
            "youtube.com", "youtu.be", "t.me", "x.com", "twitter.com"
        )
    }
}
