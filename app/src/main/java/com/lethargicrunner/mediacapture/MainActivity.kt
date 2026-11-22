package com.lethargicrunner.mediacapture

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
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
    private lateinit var captureStatusText: TextView

    private var captureRequestPending = false
    private var receiverRegistered = false

    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioCaptureService.ACTION_STATUS) return

            val status = intent.getStringExtra(
                AudioCaptureService.EXTRA_STATUS
            ) ?: "Statut de capture inconnu"

            val filePath = intent.getStringExtra(
                AudioCaptureService.EXTRA_FILE_PATH
            ) ?: ""

            if (status.startsWith("CAPTURE TERMINEE") ||
                status.startsWith("ERREUR")) {
                captureRequestPending = false
            }

            captureStatusText.text = if (filePath.isNotBlank()) {
                "$status\nFichier : $filePath"
            } else {
                status
            }
        }
    }

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
            maxLines = 1
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

        captureStatusText = TextView(this).apply {
            text = "Capture audio : inactive"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 4, 0, 8)
        }
        root.addView(captureStatusText)

        val captureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val captureButton = Button(this).apply {
            text = "Capturer le son"
            setOnClickListener {
                beginAudioCapture()
            }
        }

        val stopCaptureButton = Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                stopAudioCapture()
            }
        }

        captureRow.addView(
            captureButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        captureRow.addView(
            stopCaptureButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        root.addView(captureRow)

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

        registerCaptureReceiver()

        webView.loadUrl("https://www.google.com")
    }

    private fun beginAudioCapture() {
        if (captureRequestPending) {
            captureStatusText.text = "Une capture est déjà en cours ou demandée."
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            captureStatusText.text = "Android 10 minimum requis."
            return
        }

        captureRequestPending = true

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
            return
        }

        requestMediaProjectionPermission()
    }

    private fun requestMediaProjectionPermission() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        captureStatusText.text = "En attente de l'autorisation de capture système."

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    private fun stopAudioCapture() {
        val intent = Intent(
            this,
            AudioCaptureService::class.java
        ).apply {
            action = AudioCaptureService.ACTION_STOP
        }

        startService(intent)
        captureStatusText.text = "Arrêt de la capture demandé."
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != REQUEST_AUDIO_PERMISSION) return

        if (grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestMediaProjectionPermission()
        } else {
            captureRequestPending = false
            captureStatusText.text =
                "Permission audio refusée. Capture impossible."
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            captureRequestPending = false
            captureStatusText.text =
                "Autorisation de capture système refusée."
            return
        }

        val serviceIntent = Intent(
            this,
            AudioCaptureService::class.java
        ).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(
                AudioCaptureService.EXTRA_RESULT_CODE,
                resultCode
            )
            putExtra(
                AudioCaptureService.EXTRA_PROJECTION_DATA,
                data
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        captureStatusText.text = "Démarrage de la capture audio..."
    }

    @Suppress("DEPRECATION")
    private fun registerCaptureReceiver() {
        val filter = IntentFilter(
            AudioCaptureService.ACTION_STATUS
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                captureStatusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(captureStatusReceiver, filter)
        }

        receiverRegistered = true
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

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(captureStatusReceiver)
            receiverRegistered = false
        }

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
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
        private const val REQUEST_AUDIO_PERMISSION = 5101
        private const val REQUEST_MEDIA_PROJECTION = 5102
    }
}
