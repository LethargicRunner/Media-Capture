package com.lethargicrunner.mediacapture

/**
 * Encodeur MP3 (LAME) en flux : on lui donne des blocs PCM 16 bits entrelacés,
 * il rend des octets MP3 prêts à être écrits dans un fichier.
 */
class Mp3Encoder(
    sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int
) : AutoCloseable {

    private var handle: Long = nativeCreate(sampleRate, channels, bitrateKbps)

    init {
        if (handle == 0L) {
            throw IllegalStateException("Initialisation de l'encodeur MP3 impossible")
        }
    }

    /** Taille de sortie suffisante pour [frames] échantillons par canal (règle LAME). */
    fun maxOutputSize(frames: Int): Int = (frames * 1.25).toInt() + 7200

    /** [shortCount] = nombre de valeurs 16 bits lues (tous canaux confondus). */
    fun encode(pcm: ShortArray, shortCount: Int, out: ByteArray): Int {
        check(handle != 0L) { "Encodeur fermé" }
        val frames = shortCount / channels
        if (frames <= 0) return 0

        val written = nativeEncode(handle, pcm, frames, out)
        if (written < 0) {
            throw IllegalStateException("Erreur d'encodage MP3 ($written)")
        }
        return written
    }

    /** À appeler une seule fois, à la fin du flux. */
    fun flush(out: ByteArray): Int {
        check(handle != 0L) { "Encodeur fermé" }
        val written = nativeFlush(handle, out)
        if (written < 0) {
            throw IllegalStateException("Erreur de finalisation MP3 ($written)")
        }
        return written
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        bitrateKbps: Int
    ): Long

    private external fun nativeEncode(
        handle: Long,
        pcm: ShortArray,
        frames: Int,
        out: ByteArray
    ): Int

    private external fun nativeFlush(handle: Long, out: ByteArray): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("mp3enc")
        }
    }
}
