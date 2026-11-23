#ifndef MP3CORE_H
#define MP3CORE_H

typedef struct Mp3Enc Mp3Enc;

/* Cree un encodeur MP3 CBR. Retourne NULL en cas d'echec. */
Mp3Enc *mp3enc_create(int sample_rate, int channels, int bitrate_kbps);

/* pcm : echantillons 16 bits entrelaces, frames = nombre d'echantillons PAR CANAL.
 * Retourne le nombre d'octets MP3 ecrits dans out, ou une valeur negative. */
int mp3enc_encode(Mp3Enc *enc, const short *pcm, int frames,
                  unsigned char *out, int out_size);

/* A appeler une fois en fin de flux. Retourne le nombre d'octets ecrits, ou < 0. */
int mp3enc_flush(Mp3Enc *enc, unsigned char *out, int out_size);

void mp3enc_destroy(Mp3Enc *enc);

#endif
