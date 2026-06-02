package com.example.pitchflight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val RECORD_AUDIO_REQUEST_CODE = 101
    private val TAG = "PitchFlightAndroid"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Initializing MainActivity")
        
        webView = WebView(this)
        setContentView(webView)

        // Enable Chrome remote debugging
        WebView.setWebContentsDebuggingEnabled(true)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        
        // Ensure webview respects viewport meta tag (fixes mobile scaling/responsiveness)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        // Enable local file loading support inside webview
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                // Intercept custom request permission route to trigger native mic prompt
                if (url.host == "appassets.androidplatform.net" && url.path == "/request-permission") {
                    Log.d(TAG, "shouldOverrideUrlLoading: Intercepted request-permission click. Prompting user...")
                    requestMicrophonePermission()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host == "appassets.androidplatform.net") {
                    val path = url.path ?: ""
                    val assetPath = if (path.startsWith("/")) path.substring(1) else path
                    try {
                        val inputStream = assets.open(assetPath)
                        val mimeType = getMimeType(assetPath)
                        return WebResourceResponse(mimeType, "UTF-8", inputStream)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading asset: $assetPath", e)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                Log.d(TAG, "onPermissionRequest: Received permission request for resources: " + request?.resources?.joinToString())
                runOnUiThread {
                    if (request != null) {
                        try {
                            request.grant(request.resources)
                            Log.d(TAG, "onPermissionRequest: Granted resources successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "onPermissionRequest: Error granting permissions", e)
                        }
                    }
                }
            }
        }

        // Delay loading the app until the microphone permission is confirmed
        if (hasMicrophonePermission()) {
            Log.d(TAG, "onCreate: Microphone permission already granted. Loading app...")
            loadApp()
        } else {
            Log.d(TAG, "onCreate: Requesting microphone permission...")
            requestMicrophonePermission()
        }
    }

    private fun loadApp() {
        webView.loadUrl("https://appassets.androidplatform.net/index.html")
    }

    private fun loadPermissionDeniedPage() {
        webView.loadUrl("https://appassets.androidplatform.net/permission_denied.html")
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophonePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: requestCode = $requestCode")
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onRequestPermissionsResult: Permission granted! Loading app...")
                loadApp()
            } else {
                Log.w(TAG, "onRequestPermissionsResult: Permission denied! Redirecting to instruction page...")
                loadPermissionDeniedPage()
            }
        }
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".ttf") -> "font/ttf"
            else -> "text/plain"
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
