package com.lethargicrunner.mediacapture

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var mediaList: TextView
    private lateinit var statusText: TextView

    private val detectedMedia = Collections.synchronizedSet(
        LinkedHashSet<String>()
    )

    private val mediaExtensions = listOf(
        ".m3u8", ".mpd", ".mp4", ".webm",
        ".mp3", ".m4a", ".aac", ".wav",
        ".ogg", ".oga", ".ts", ".m4s"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val title = TextView(this).apply {
            text = "MEDIA CAPTURE"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(title)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        addressBar = EditText(this).apply {
            hint = "Adresse du site"
            singleLine = true
            inputType =  UriInputType
            imeOptions = EditorInfo.IME_ACTION_GO
            setText("https://")
        }

        val goButton = Button(this).apply {
            text = "GO"
            setOnClickListener {
                openAddress(addressBar.text.toString())
            }
        }

        addressRow.addView(
            addressBar,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addressRow.addView(goButton)
        root.addView(addressRow)

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openAddress(addressBar.text.toString())
                true
            } else {
                false
            }
        }

        statusText = TextView(this).apply {
            text = "Médias détectés : 0"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 8)
        }
        root.addView(statusText)

        val mediaScroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF0F0F0.toInt())
        }

        mediaList = TextView(this).apply {
            text = "Les médias détectés apparaîtront ici."
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }

        mediaScroll.addView(mediaList)
        root.addView(
            mediaScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                120
            )
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    runOnUiThread {
                        addressBar.setText(url ?: "")
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString()

                    if (url != null && isMediaUrl(url)) {
                        registerMedia(url)
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val backButton = Button(this).apply {
            text = "←"
            setOnClickListener {
                if (webView.canGoBack()) webView.goBack()
            }
        }

        val forwardButton = Button(this).apply {
            text = "→"
            setOnClickListener {
                if (webView.canGoForward()) webView.goForward()
            }
        }

        val reloadButton = Button(this).apply {
            text = "↻"
            setOnClickListener {
                webView.reload()
            }
        }

        val clearButton = Button(this).apply {
            text = "Effacer"
            setOnClickListener {
                detectedMedia.clear()
                updateMediaList()
            }
        }

        bottomRow.addView(backButton)
        bottomRow.addView(forwardButton)
        bottomRow.addView(reloadButton)
        bottomRow.addView(clearButton)
        root.addView(bottomRow)

        setContentView(root)

        webView.loadUrl("https://www.google.com")
    }

    private fun openAddress(raw: String) {
        var address = raw.trim()

        if (address.isEmpty()) return

        if (!address.contains("://")) {
            address = if (address.contains(" ") || !address.contains(".")) {
                "https://www.google.com/search?q=" +
                    java.net.URLEncoder.encode(address, "UTF-8")
            } else {
                "https://$address"
            }
        }

        webView.loadUrl(address)
    }

    private fun isMediaUrl(rawUrl: String): Boolean {
        val url = rawUrl.lowercase(Locale.ROOT)
        return mediaExtensions.any { extension ->
            url.substringBefore("?").substringBefore("#").endsWith(extension)
        }
    }

    private fun registerMedia(url: String) {
        val added = detectedMedia.add(url)

        if (added) {
            runOnUiThread {
                updateMediaList()
            }
        }
    }

    private fun updateMediaList() {
        val snapshot = synchronized(detectedMedia) {
            detectedMedia.toList()
        }

        statusText.text = "Médias détectés : ${snapshot.size}"

        mediaList.text = if (snapshot.isEmpty()) {
            "Aucun média détecté pour le moment."
        } else {
            snapshot.joinToString("\n\n") { it }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val UriInputType = 17
    }
}
