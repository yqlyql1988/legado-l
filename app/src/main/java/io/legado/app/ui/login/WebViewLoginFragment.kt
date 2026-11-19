package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.activityViewModels
import androidx.core.view.WindowInsetsCompat
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.FragmentWebViewLoginBinding
import io.legado.app.help.http.CookieStore
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.gone
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.snackbar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.core.net.toUri
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import splitties.views.bottomPadding

class WebViewLoginFragment : BaseFragment(R.layout.fragment_web_view_login) {

    private val binding by viewBinding(FragmentWebViewLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var pooledWebView: PooledWebView? = null
    private var currentWebView: WebView? = null
    private var popupWebView: WebView? = null
    private val popupBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePopup()
    }

    private var checking = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.setBackgroundColor(requireContext().backgroundColor)
        binding.root.setOnApplyWindowInsetsListenerCompat { root, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            root.bottomPadding = windowInsets.getInsets(typeMask).bottom
            windowInsets
        }
        setSupportToolbar(binding.titleBar.toolbar)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, popupBack)
        binding.titleBar.setNavigationOnClickListener { closePopup() }
        viewModel.source?.let {
            binding.titleBar.title = getString(R.string.login_source, it.getTag())
            initWebView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.source_webview_login, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_ok -> {
                if (!checking) {
                    checking = true
                    binding.titleBar.snackbar(R.string.check_host_cookie)
                    viewModel.source?.let {
                        loadUrl(it)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(source: BaseSource) {
        val webView = WebViewPool.acquire(requireContext()).let {
            pooledWebView = it
            it.realWebView
        }
        webView.onResume()
        binding.webViewContainer.addView(webView)
        currentWebView = webView
        binding.progressBar.fontColor = accentColor
        val requestConfig = viewModel.headerMap.toWebViewRequestConfig(AppConfig.userAgent)
        webView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = requestConfig.userAgent
            setSupportMultipleWindows(true)
        }
        val cookieManager = CookieManager.getInstance()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val cookie = cookieManager.getCookie(url)
                CookieStore.setCookie(source.getKey(), cookie)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cookie = cookieManager.getCookie(url)
                CookieStore.setCookie(source.getKey(), cookie)
                if (checking) {
                    activity?.finish()
                }
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return shouldOverrideUrlLoading(request.url)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return shouldOverrideUrlLoading(url.toUri())
            }

            private fun shouldOverrideUrlLoading(url: Uri): Boolean {
                when (url.scheme) {
                    "http", "https" -> {
                        return false
                    }

                    else -> {
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            context?.openUrl(url)
                        }
                        return true
                    }
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }
        }
        webView.webChromeClient = object : LoginChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                if (!isUserGesture || popupWebView != null || checking) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                // Keep the opener alive so the authorization page can return its result.
                transport.webView = createPopup(view.settings.userAgentString)
                resultMsg.sendToTarget()
                return true
            }
        }
        loadUrl(source)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopup(userAgent: String): WebView {
        val popup = WebView(requireContext()).apply {
            setBackgroundColor(requireContext().backgroundColor)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = userAgent
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (view === popupWebView) binding.titleBar.subtitle = url.toUri().host
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    if (url.scheme == "https" || url.scheme == "http") return false
                    if (request.isForMainFrame && request.hasGesture()) {
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            context?.openUrl(url)
                        }
                    }
                    return true
                }
            }
            webChromeClient = object : LoginChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    window.post { if (window === popupWebView) closePopup() }
                }
            }
        }
        popupWebView = popup
        currentWebView?.visibility = View.INVISIBLE
        binding.webViewContainer.addView(popup, -1, -1)
        popupBack.isEnabled = true
        binding.titleBar.toolbar.apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.back)
            menu.findItem(R.id.menu_ok)?.isVisible = false
        }
        binding.titleBar.applyForegroundColor()
        updateProgress(0)
        return popup
    }

    private fun closePopup() {
        val popup = popupWebView ?: return
        popupWebView = null
        binding.webViewContainer.removeView(popup)
        popup.destroy()
        popupBack.isEnabled = false
        binding.titleBar.subtitle = null
        binding.titleBar.toolbar.apply {
            navigationIcon = null
            menu.findItem(R.id.menu_ok)?.isVisible = true
        }
        currentWebView?.let {
            it.visibility = View.VISIBLE
            updateProgress(it.progress)
        }
    }

    private open inner class LoginChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (view != null && view === (popupWebView ?: currentWebView)) {
                updateProgress(newProgress)
            }
        }
    }

    private fun updateProgress(progress: Int) {
        binding.progressBar.setDurProgress(progress)
        binding.progressBar.gone(progress == 100)
    }

    private fun loadUrl(source: BaseSource) {
        val loginUrl = source.loginUrl ?: return
        val absoluteUrl = NetworkUtils.getAbsoluteURL(source.getKey(), loginUrl)
        val requestConfig = viewModel.headerMap.toWebViewRequestConfig(AppConfig.userAgent)
        currentWebView?.apply {
            settings.userAgentString = requestConfig.userAgent
            loadUrl(absoluteUrl, requestConfig.additionalHeaders)
        }
    }

    override fun onDestroyView() {
        closePopup()
        currentWebView?.settings?.setSupportMultipleWindows(false)
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
        currentWebView = null
        checking = false
        super.onDestroyView()
    }

}
