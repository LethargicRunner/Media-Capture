package com.lethargicrunner.mediacapture

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var fab: CaptureFab

    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences("media_capture", Context.MODE_PRIVATE)
    }

    private var captureRequestPending = false
    private var receiverRegistered = false

    private var recording = false
    private var recordingStartMs = 0L
    private var captureStatus = "Capture audio : inactive"

    // Éléments du panneau (non nuls uniquement quand il est ouvert).
    private var panel: Dialog? = null
    private var panelToggle: TextView? = null
    private var panelStatus: TextView? = null
    private var panelOptions: View? = null
    private var panelFolderLabel: TextView? = null
    private var panelFolderReset: TextView? = null
    private var panelMediaHeader: TextView? = null
    private var panelMediaList: LinearLayout? = null

    private val ticker = object : Runnable {
        override fun run() {
            refreshPanelCapture()
            if (recording) handler.postDelayed(this, 1000)
        }
    }

    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioCaptureService.ACTION_STATUS) return

            val status = intent.getStringExtra(
                AudioCaptureService.EXTRA_STATUS
            ) ?: "Statut de capture inconnu"

            val filePath = intent.getStringExtra(
                AudioCaptureService.EXTRA_FILE_PATH
            ) ?: ""

            when {
                status.startsWith("CAPTURE EN COURS") -> {
                    recording = true
                    recordingStartMs = AudioCaptureService.startedAtMs.takeIf {
                        it > 0L
                    } ?: SystemClock.elapsedRealtime()
                    // On libère l'écran pour laisser lancer la lecture dans la page.
                    panel?.dismiss()
                }

                status.startsWith("CAPTURE TERMINEE") ||
                    status.startsWith("ERREUR") -> {
                    recording = false
                    captureRequestPending = false

                    // Le panneau est fermé pendant la capture : on prévient par un message.
                    val message = if (filePath.isNotBlank()) {
                        "$status\n$filePath"
                    } else {
                        status
                    }
                    Toast.makeText(
                        this@MainActivity,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            captureStatus = if (filePath.isNotBlank()) {
                "$status\nFichier : $filePath"
            } else {
                status
            }
            syncCaptureUi()
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

    private var bitrate: Int
        get() = prefs.getInt("bitrate", AudioCaptureService.DEFAULT_BITRATE_KBPS)
            .takeIf { it in AudioCaptureService.ALLOWED_BITRATES }
            ?: AudioCaptureService.DEFAULT_BITRATE_KBPS
        set(value) {
            prefs.edit().putInt("bitrate", value).apply()
        }

    private var channels: Int
        get() = if (prefs.getInt("channels", AudioCaptureService.DEFAULT_CHANNELS) == 1) 1 else 2
        set(value) {
            prefs.edit().putInt("channels", value).apply()
        }

    /** Dossier de sortie choisi, ou null = Téléchargements (si la permission existe encore). */
    private val outputTree: Uri?
        get() {
            val saved = prefs.getString("output_tree", null) ?: return null
            val uri = Uri.parse(saved)
            val stillGranted = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            return if (stillGranted) uri else null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        column.addView(buildAddressBar())
        column.addView(
            View(this).apply { setBackgroundColor(COLOR_DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
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
                    favicon: Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    runOnUiThread {
                        addressBar.setText(url ?: "")
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString()

                    if (url != null && isMediaUrl(url)) {
                        registerMedia(url)
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }
        }

        column.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        fab = CaptureFab(this).apply {
            onTap = { showPanel() }
            onMoved = { saveFabPosition() }
        }
        root.addView(
            fab,
            FrameLayout.LayoutParams(dp(FAB_SIZE_DP), dp(FAB_SIZE_DP))
        )

        root.addOnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = (right - left) != (oldRight - oldLeft) ||
                (bottom - top) != (oldBottom - oldTop)
            if (sizeChanged) placeFab()
        }

        setContentView(root)

        registerCaptureReceiver()

        webView.loadUrl("https://www.google.com")
    }

    override fun onResume() {
        super.onResume()

        // Resynchronise l'interface avec le service (activité recréée, etc.).
        if (AudioCaptureService.capturing) {
            if (!recording) {
                recording = true
                recordingStartMs = AudioCaptureService.startedAtMs
                captureRequestPending = true
                captureStatus = "CAPTURE EN COURS"
            }
        } else if (recording) {
            recording = false
            captureRequestPending = false
        }
        syncCaptureUi()
    }

    // ---------------------------------------------------------------- barre d'adresse

    private fun buildAddressBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        addressBar = EditText(this).apply {
            hint = "Adresse du site"
            maxLines = 1
            inputType = UriInputType
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            minHeight = 0
            setSelectAllOnFocus(true)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(COLOR_LIGHT, 20)
            setText("https://")
        }

        val goButton = TextView(this).apply {
            text = "GO"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(COLOR_ACCENT, 20)
            setOnClickListener { openAddress(addressBar.text.toString()) }
        }

        bar.addView(
            addressBar,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(
            goButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        )

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openAddress(addressBar.text.toString())
                true
            } else {
                false
            }
        }

        return bar
    }

    // ---------------------------------------------------------------- bouton flottant

    private fun placeFab() {
        if (!::fab.isInitialized) return
        if (root.width == 0 || root.height == 0 || fab.width == 0) return

        val margin = dp(CaptureFab.EDGE_MARGIN_DP)
        val rangeX = (root.width - fab.width - 2 * margin).coerceAtLeast(0)
        val rangeY = (root.height - fab.height - 2 * margin).coerceAtLeast(0)

        val fx = prefs.getFloat("fab_fx", 1f).coerceIn(0f, 1f)
        val fy = prefs.getFloat("fab_fy", 0.85f).coerceIn(0f, 1f)

        fab.animate().cancel()
        fab.x = margin + rangeX * fx
        fab.y = margin + rangeY * fy
    }

    private fun saveFabPosition() {
        val margin = dp(CaptureFab.EDGE_MARGIN_DP)
        val rangeX = (root.width - fab.width - 2 * margin).coerceAtLeast(1)
        val rangeY = (root.height - fab.height - 2 * margin).coerceAtLeast(1)

        prefs.edit()
            .putFloat("fab_fx", ((fab.x - margin) / rangeX).coerceIn(0f, 1f))
            .putFloat("fab_fy", ((fab.y - margin) / rangeY).coerceIn(0f, 1f))
            .apply()
    }

    // ---------------------------------------------------------------- panneau

    private fun showPanel() {
        if (panel?.isShowing == true) return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = sheetBackground()
        }

        // En-tête
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "Media Capture"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_DARK)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            TextView(this).apply {
                text = "✕"
                textSize = 18f
                setTextColor(COLOR_MUTED)
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener { dialog.dismiss() }
            }
        )
        content.addView(header)

        // Bouton principal
        val toggle = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                if (recording) stopAudioCapture() else beginAudioCapture()
            }
        }
        content.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
        content.addView(status)

        // Options
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        options.addView(sectionLabel("Qualité MP3 (kb/s)"))
        val bitrates = AudioCaptureService.ALLOWED_BITRATES
        options.addView(
            chipRow(
                bitrates.map { it.toString() },
                bitrates.indexOf(bitrate).coerceAtLeast(0)
            ) { index -> bitrate = bitrates[index] }
        )
        options.addView(sectionLabel("Canaux"))
        options.addView(
            chipRow(
                listOf("Stéréo", "Mono"),
                if (channels == 1) 1 else 0
            ) { index -> channels = if (index == 1) 1 else 2 }
        )
        options.addView(sectionLabel("Dossier de sortie"))

        val folderLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_DARK)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(COLOR_LIGHT, 10)
            ellipsize = TextUtils.TruncateAt.START
            maxLines = 1
        }
        options.addView(folderLabel)

        val folderButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        folderButtons.addView(
            TextView(this).apply {
                text = "Choisir un dossier…"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                background = rounded(COLOR_ACCENT, 10)
                setOnClickListener { pickOutputFolder() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val folderReset = TextView(this).apply {
            text = "Téléchargements"
            textSize = 13f
            setTextColor(COLOR_DARK)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = rounded(COLOR_LIGHT, 10)
            setOnClickListener { resetOutputFolder() }
        }
        folderButtons.addView(
            folderReset,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) }
        )
        options.addView(
            folderButtons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        content.addView(options)

        // Navigation
        content.addView(sectionLabel("Navigation"))
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val navItems = listOf<Pair<String, () -> Unit>>(
            "←  Retour" to { if (webView.canGoBack()) webView.goBack() },
            "→  Avancer" to { if (webView.canGoForward()) webView.goForward() },
            "↻  Actualiser" to { webView.reload() }
        )
        navItems.forEachIndexed { index, item ->
            navRow.addView(
                TextView(this).apply {
                    text = item.first
                    textSize = 13f
                    setTextColor(COLOR_DARK)
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = rounded(COLOR_LIGHT, 10)
                    setOnClickListener {
                        item.second()
                        dialog.dismiss()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index > 0) leftMargin = dp(6) }
            )
        }
        content.addView(navRow)

        // Médias détectés
        val mediaHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(2))
        }
        val mediaHeader = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_MUTED)
        }
        mediaHeaderRow.addView(
            mediaHeader,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        mediaHeaderRow.addView(
            TextView(this).apply {
                text = "Effacer"
                textSize = 12f
                setTextColor(COLOR_ACCENT)
                setPadding(dp(10), dp(4), dp(2), dp(4))
                setOnClickListener {
                    detectedMedia.clear()
                    onMediaChanged()
                }
            }
        )
        content.addView(mediaHeaderRow)

        val mediaList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(mediaList)

        val scroll = ScrollView(this).apply { addView(content) }
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.25f)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setLayout(
                minOf(resources.displayMetrics.widthPixels, dp(520)),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        panelToggle = toggle
        panelStatus = status
        panelOptions = options
        panelFolderLabel = folderLabel
        panelFolderReset = folderReset
        panelMediaHeader = mediaHeader
        panelMediaList = mediaList

        dialog.setOnDismissListener {
            handler.removeCallbacks(ticker)
            panel = null
            panelToggle = null
            panelStatus = null
            panelOptions = null
            panelFolderLabel = null
            panelFolderReset = null
            panelMediaHeader = null
            panelMediaList = null
        }

        panel = dialog
        refreshPanelCapture()
        refreshFolderLabel()
        fillMediaList()
        dialog.show()

        if (recording) handler.post(ticker)
    }

    private fun refreshPanelCapture() {
        val toggle = panelToggle ?: return

        if (recording) {
            toggle.text = "■  Arrêter  ·  ${formatElapsed()}"
            toggle.background = rounded(COLOR_RECORD, 14)
        } else {
            toggle.text = "●  Capturer le son"
            toggle.background = rounded(COLOR_ACCENT, 14)
        }

        panelStatus?.text = captureStatus

        panelOptions?.let {
            setEnabledRecursive(it, !recording && !captureRequestPending)
            it.alpha = if (recording || captureRequestPending) 0.45f else 1f
        }
    }

    private fun refreshFolderLabel() {
        val tree = outputTree
        panelFolderLabel?.text = if (tree == null) {
            "Téléchargements (par défaut)"
        } else {
            AudioCaptureService.folderLabel(tree)
        }
        panelFolderReset?.alpha = if (tree == null) 0.4f else 1f
    }

    private fun pickOutputFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download"
                )
            )
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    private fun resetOutputFolder() {
        releaseSavedFolderPermission()
        prefs.edit().remove("output_tree").apply()
        refreshFolderLabel()
    }

    private fun releaseSavedFolderPermission() {
        val saved = prefs.getString("output_tree", null) ?: return
        try {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(saved),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun fillMediaList() {
        val list = panelMediaList ?: return
        val header = panelMediaHeader ?: return

        val snapshot = synchronized(detectedMedia) { detectedMedia.toList() }
        header.text = "Médias détectés (${snapshot.size})"

        list.removeAllViews()

        if (snapshot.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = "Aucun média détecté pour le moment."
                    textSize = 12f
                    setTextColor(COLOR_MUTED)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                }
            )
            return
        }

        snapshot.asReversed().take(MAX_LISTED_MEDIA).forEach { url ->
            list.addView(
                TextView(this).apply {
                    text = url
                    textSize = 11f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(COLOR_DARK)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = rounded(COLOR_LIGHT, 8)
                    setOnClickListener { copyToClipboard(url) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
        }

        if (snapshot.size > MAX_LISTED_MEDIA) {
            list.addView(
                TextView(this).apply {
                    text = "… et ${snapshot.size - MAX_LISTED_MEDIA} plus anciens"
                    textSize = 11f
                    setTextColor(COLOR_MUTED)
                    setPadding(dp(2), dp(6), dp(2), dp(0))
                }
            )
        }
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("media", url))
        Toast.makeText(this, "URL copiée", Toast.LENGTH_SHORT).show()
    }

    private fun sectionLabel(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(COLOR_MUTED)
        setPadding(dp(2), dp(14), dp(2), dp(6))
    }

    private fun chipRow(
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val chips = mutableListOf<TextView>()

        fun paint(selectedIndex: Int) {
            chips.forEachIndexed { index, chip ->
                val on = index == selectedIndex
                chip.background = rounded(if (on) COLOR_ACCENT else COLOR_LIGHT, 10)
                chip.setTextColor(if (on) Color.WHITE else COLOR_DARK)
            }
        }

        labels.forEachIndexed { index, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                setOnClickListener {
                    paint(index)
                    onSelect(index)
                }
            }
            chips.add(chip)
            row.addView(
                chip,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index > 0) leftMargin = dp(6) }
            )
        }

        paint(selected)
        return row
    }

    private fun setEnabledRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setEnabledRecursive(view.getChildAt(i), enabled)
            }
        }
    }

    // ---------------------------------------------------------------- état de capture

    private fun syncCaptureUi() {
        if (::fab.isInitialized) fab.recording = recording

        handler.removeCallbacks(ticker)
        refreshPanelCapture()
        if (recording && panel != null) handler.post(ticker)
    }

    private fun setStatus(text: String) {
        captureStatus = text
        refreshPanelCapture()
    }

    private fun formatElapsed(): String {
        val total = ((SystemClock.elapsedRealtime() - recordingStartMs) / 1000)
            .coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun beginAudioCapture() {
        if (captureRequestPending) {
            setStatus("Une capture est déjà en cours ou demandée.")
            return
        }

        captureRequestPending = true
        refreshPanelCapture()

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

        setStatus("En attente de l'autorisation de capture système.")

        @Suppress("DEPRECATION")
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
        setStatus("Arrêt de la capture demandé.")
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
            setStatus("Permission audio refusée. Capture impossible.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_FOLDER) {
            handleFolderResult(resultCode, data)
            return
        }

        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            captureRequestPending = false
            setStatus("Autorisation de capture système refusée.")
            return
        }

        val serviceIntent = Intent(
            this,
            AudioCaptureService::class.java
        ).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_PROJECTION_DATA, data)
            putExtra(AudioCaptureService.EXTRA_BITRATE, bitrate)
            putExtra(AudioCaptureService.EXTRA_CHANNELS, channels)
            outputTree?.let {
                putExtra(AudioCaptureService.EXTRA_OUTPUT_TREE_URI, it.toString())
            }
        }

        startForegroundService(serviceIntent)
        setStatus("Démarrage de la capture audio...")
    }

    private fun handleFolderResult(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Dossier refusé : ${e.message ?: e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val previous = prefs.getString("output_tree", null)
        if (previous != null && previous != uri.toString()) {
            releaseSavedFolderPermission()
        }

        prefs.edit().putString("output_tree", uri.toString()).apply()
        refreshFolderLabel()
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

    // ---------------------------------------------------------------- navigation et médias

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
                onMediaChanged()
            }
        }
    }

    private fun onMediaChanged() {
        if (::fab.isInitialized) fab.badgeCount = detectedMedia.size
        fillMediaList()
    }

    // ---------------------------------------------------------------- utilitaires d'affichage

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun sheetBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.WHITE)
            val r = dp(22).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

    // ---------------------------------------------------------------- cycle de vie

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        panel?.dismiss()

        if (receiverRegistered) {
            unregisterReceiver(captureStatusReceiver)
            receiverRegistered = false
        }

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }

    @Suppress("DEPRECATION")
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
        private const val REQUEST_PICK_FOLDER = 5103

        private const val FAB_SIZE_DP = 56
        private const val MAX_LISTED_MEDIA = 8

        private val COLOR_ACCENT = Color.parseColor("#7C3AED")
        private val COLOR_RECORD = Color.parseColor("#E11D48")
        private val COLOR_LIGHT = Color.parseColor("#F3F4F6")
        private val COLOR_DARK = Color.parseColor("#1F2937")
        private val COLOR_MUTED = Color.parseColor("#6B7280")
        private val COLOR_DIVIDER = Color.parseColor("#E5E7EB")
    }
}
