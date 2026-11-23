package com.lethargicrunner.mediacapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var recording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }

        if (captureThread != null) {
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sendStatus("ERREUR : Android 10 minimum requis")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val requestedBitrate = intent?.getIntExtra(
            EXTRA_BITRATE,
            DEFAULT_BITRATE_KBPS
        ) ?: DEFAULT_BITRATE_KBPS
        val bitrate = if (requestedBitrate in ALLOWED_BITRATES) {
            requestedBitrate
        } else {
            DEFAULT_BITRATE_KBPS
        }
        val channels = if (
            intent?.getIntExtra(EXTRA_CHANNELS, DEFAULT_CHANNELS) == 1
        ) 1 else 2
        val outputTree = intent
            ?.getStringExtra(EXTRA_OUTPUT_TREE_URI)
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }
        val projectionData = getProjectionIntent(intent)

        if (projectionData == null) {
            sendStatus("ERREUR : autorisation MediaProjection absente")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            createNotificationChannel()
            startCaptureForeground()

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                projectionData
            )

            val currentProjection = projection
                ?: throw IllegalStateException("MediaProjection indisponible")

            currentProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        requestStop()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            startAudioCapture(currentProjection, bitrate, channels, outputTree)

        } catch (e: Throwable) {
            sendStatus("ERREUR : ${e.message ?: e.javaClass.simpleName}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getProjectionIntent(source: Intent?): Intent? {
        if (source == null) return null

        return if (Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(
                EXTRA_PROJECTION_DATA,
                Intent::class.java
            )
        } else {
            source.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
    }

    private fun startCaptureForeground() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Capture audio",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Media Capture")
                .setContentText("Capture audio en cours")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Media Capture")
                .setContentText("Capture audio en cours")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private class OutputTarget(
        val stream: OutputStream,
        val label: String,
        val uri: Uri?,
        val file: File?
    )

    /**
     * Ordre d'essai : dossier choisi (Storage Access Framework), puis Téléchargements
     * (MediaStore), puis dossier privé de l'application.
     */
    private fun openOutput(outputTree: Uri?): OutputTarget {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val name = "capture_$timestamp.mp3"

        if (outputTree != null) {
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    outputTree,
                    DocumentsContract.getTreeDocumentId(outputTree)
                )
                val document = DocumentsContract.createDocument(
                    contentResolver,
                    parent,
                    "audio/mpeg",
                    name
                )
                val stream = document?.let { contentResolver.openOutputStream(it) }

                if (document != null && stream != null) {
                    return OutputTarget(
                        stream,
                        "${folderLabel(outputTree)}/$name",
                        document,
                        null
                    )
                }

                if (document != null) {
                    try {
                        DocumentsContract.deleteDocument(contentResolver, document)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                // Dossier inaccessible (permission retirée, dossier supprimé...) : repli.
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        )
        val stream = uri?.let { contentResolver.openOutputStream(it) }

        if (uri != null && stream != null) {
            return OutputTarget(
                stream,
                "${Environment.DIRECTORY_DOWNLOADS}/$name",
                uri,
                null
            )
        }

        // Dernier repli : dossier privé de l'application.
        if (uri != null) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }

        val directory = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: filesDir
        directory.mkdirs()

        val file = File(directory, name)
        return OutputTarget(
            FileOutputStream(file),
            file.absolutePath,
            null,
            file
        )
    }

    private fun deleteOutput(target: OutputTarget) {
        try {
            target.uri?.let {
                if (DocumentsContract.isDocumentUri(this, it)) {
                    DocumentsContract.deleteDocument(contentResolver, it)
                } else {
                    contentResolver.delete(it, null, null)
                }
            }
            target.file?.delete()
        } catch (_: Exception) {
        }
    }

    private fun formatDuration(frames: Long): String {
        val seconds = frames / SAMPLE_RATE
        return String.format(
            Locale.US,
            "%d:%02d",
            seconds / 60,
            seconds % 60
        )
    }

    private fun startAudioCapture(
        mediaProjection: MediaProjection,
        bitrate: Int,
        channels: Int,
        outputTree: Uri?
    ) {
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(
            mediaProjection
        )
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelMask)
            .build()

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimumBuffer <= 0) {
            throw IllegalStateException(
                "Taille de tampon audio invalide : $minimumBuffer"
            )
        }

        // Taille en octets ; on lit ensuite en ShortArray (2 octets par valeur).
        val bufferSize = maxOf(minimumBuffer * 2, 16384)

        val encoder = Mp3Encoder(SAMPLE_RATE, channels, bitrate)

        val target = try {
            openOutput(outputTree)
        } catch (e: Exception) {
            encoder.close()
            throw e
        }

        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            encoder.close()
            try {
                target.stream.close()
            } catch (_: Exception) {
            }
            deleteOutput(target)
            throw e
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            encoder.close()
            try {
                target.stream.close()
            } catch (_: Exception) {
            }
            deleteOutput(target)
            throw IllegalStateException("AudioRecord non initialisé")
        }

        audioRecord = recorder

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            audioRecord = null
            encoder.close()
            try {
                target.stream.close()
            } catch (_: Exception) {
            }
            deleteOutput(target)
            throw e
        }

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            audioRecord = null
            encoder.close()
            try {
                target.stream.close()
            } catch (_: Exception) {
            }
            deleteOutput(target)
            throw IllegalStateException("AudioRecord n'a pas démarré")
        }

        recording = true
        startedAtMs = SystemClock.elapsedRealtime()
        capturing = true
        val modeLabel = if (channels == 1) "mono" else "stéréo"
        sendStatus("CAPTURE EN COURS (MP3 $bitrate kb/s, $modeLabel)")

        captureThread = Thread {
            var errorMessage: String? = null
            var totalFrames = 0L

            try {
                val output = BufferedOutputStream(target.stream, 16 * 1024)
                val pcm = ShortArray(bufferSize / 2)
                val mp3 = ByteArray(
                    encoder.maxOutputSize(pcm.size / channels)
                )

                while (recording) {
                    val count = recorder.read(pcm, 0, pcm.size)

                    if (count > 0) {
                        val written = encoder.encode(pcm, count, mp3)
                        if (written > 0) {
                            output.write(mp3, 0, written)
                        }
                        totalFrames += count / channels
                    } else if (
                        count == AudioRecord.ERROR_DEAD_OBJECT ||
                        count == AudioRecord.ERROR_INVALID_OPERATION
                    ) {
                        // Arrêt normal : stop() fait échouer la lecture en cours.
                        break
                    } else if (count < 0) {
                        errorMessage = "Lecture audio : code $count"
                        break
                    }
                }

                val tail = encoder.flush(mp3)
                if (tail > 0) {
                    output.write(mp3, 0, tail)
                }
                output.flush()
                output.close()
            } catch (e: Exception) {
                errorMessage = e.message ?: e.javaClass.simpleName
                try {
                    target.stream.close()
                } catch (_: Exception) {
                }
            } finally {
                recording = false
                capturing = false

                try {
                    encoder.close()
                } catch (_: Exception) {
                }

                try {
                    recorder.release()
                } catch (_: Exception) {
                }

                audioRecord = null

                try {
                    projection?.stop()
                } catch (_: Exception) {
                }

                projection = null

                if (totalFrames == 0L) {
                    deleteOutput(target)
                }

                val finalMessage = when {
                    errorMessage != null -> "ERREUR : $errorMessage"
                    totalFrames == 0L ->
                        "CAPTURE TERMINEE : aucun son capturé " +
                            "(application non capturable ou rien en lecture)"
                    else ->
                        "CAPTURE TERMINEE (${formatDuration(totalFrames)})"
                }

                sendStatus(
                    finalMessage,
                    if (totalFrames == 0L) "" else target.label
                )

                Handler(Looper.getMainLooper()).post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.apply {
            name = "MediaCapture-Audio"
            start()
        }
    }

    private fun requestStop() {
        recording = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        if (captureThread == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun sendStatus(status: String, path: String = "") {
        val message = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_FILE_PATH, path)

        sendBroadcast(message)
    }

    override fun onDestroy() {
        recording = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.lethargicrunner.mediacapture.START_AUDIO"
        const val ACTION_STOP = "com.lethargicrunner.mediacapture.STOP_AUDIO"
        const val ACTION_STATUS = "com.lethargicrunner.mediacapture.AUDIO_STATUS"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_BITRATE = "bitrate_kbps"
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_OUTPUT_TREE_URI = "output_tree_uri"

        const val DEFAULT_BITRATE_KBPS = 192
        const val DEFAULT_CHANNELS = 2
        val ALLOWED_BITRATES = listOf(128, 192, 256, 320)

        /** Nom lisible d'un dossier choisi via le sélecteur Android (ex. « Music/Captures »). */
        fun folderLabel(treeUri: Uri): String {
            val documentId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Exception) {
                return treeUri.toString()
            }
            val path = documentId.substringAfter(':', "")
            return if (path.isBlank()) "Stockage" else path
        }

        /** Vrai tant qu'une capture est en cours (lu par l'interface). */
        @Volatile
        var capturing = false

        /** Instant de début de la capture, en SystemClock.elapsedRealtime(). */
        @Volatile
        var startedAtMs = 0L

        private const val CHANNEL_ID = "media_capture_audio"
        private const val NOTIFICATION_ID = 4102

        private const val SAMPLE_RATE = 44100
    }
}
