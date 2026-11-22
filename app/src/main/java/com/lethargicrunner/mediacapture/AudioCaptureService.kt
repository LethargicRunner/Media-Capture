package com.lethargicrunner.mediacapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var outputFile: File? = null

    @Volatile
    private var recording = false

    @Volatile
    private var dataBytes = 0L

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

            startAudioCapture(currentProjection)

        } catch (e: Exception) {
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

    private fun startAudioCapture(mediaProjection: MediaProjection) {
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
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimumBuffer <= 0) {
            throw IllegalStateException(
                "Taille de tampon audio invalide : $minimumBuffer"
            )
        }

        val bufferSize = maxOf(minimumBuffer * 2, 8192)

        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord non initialisé")
        }

        val musicDirectory = getExternalFilesDir(
            Environment.DIRECTORY_MUSIC
        ) ?: filesDir

        if (!musicDirectory.exists()) {
            musicDirectory.mkdirs()
        }

        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val file = File(
            musicDirectory,
            "capture_$timestamp.wav"
        )

        audioRecord = recorder
        outputFile = file
        dataBytes = 0L

        recorder.startRecording()

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            audioRecord = null
            throw IllegalStateException("AudioRecord n'a pas démarré")
        }

        recording = true
        sendStatus("CAPTURE EN COURS")

        captureThread = Thread {
            var errorMessage: String? = null

            try {
                FileOutputStream(file).use { output ->
                    output.write(ByteArray(44))

                    val buffer = ByteArray(bufferSize)

                    while (recording) {
                        val count = recorder.read(
                            buffer,
                            0,
                            buffer.size
                        )

                        if (count > 0) {
                            output.write(buffer, 0, count)
                            dataBytes += count
                        } else if (
                            count == AudioRecord.ERROR_DEAD_OBJECT ||
                            count == AudioRecord.ERROR_INVALID_OPERATION
                        ) {
                            break
                        }
                    }

                    output.flush()
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: e.javaClass.simpleName
            } finally {
                recording = false

                try {
                    writeWavHeader(file, dataBytes)
                } catch (e: Exception) {
                    errorMessage = "En-tête WAV : ${e.message}"
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

                val finalMessage = if (errorMessage == null) {
                    "CAPTURE TERMINEE"
                } else {
                    "ERREUR : $errorMessage"
                }

                sendStatus(finalMessage, file.absolutePath)

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

    private fun writeWavHeader(file: File, audioLength: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)

            raf.writeBytes("RIFF")
            writeIntLE(raf, (36 + audioLength).toInt())
            raf.writeBytes("WAVE")

            raf.writeBytes("fmt ")
            writeIntLE(raf, 16)
            writeShortLE(raf, 1)
            writeShortLE(raf, CHANNELS)
            writeIntLE(raf, SAMPLE_RATE)
            writeIntLE(raf, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
            writeShortLE(raf, CHANNELS * BITS_PER_SAMPLE / 8)
            writeShortLE(raf, BITS_PER_SAMPLE)

            raf.writeBytes("data")
            writeIntLE(raf, audioLength.toInt())
        }
    }

    private fun writeIntLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
        file.write((value shr 16) and 0xFF)
        file.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
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

        private const val CHANNEL_ID = "media_capture_audio"
        private const val NOTIFICATION_ID = 4102

        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }
}
