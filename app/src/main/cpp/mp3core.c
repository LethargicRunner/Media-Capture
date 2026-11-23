#include <stdlib.h>
#include "mp3core.h"
#include "lame.h"

struct Mp3Enc {
    lame_t lame;
    int channels;
};

Mp3Enc *mp3enc_create(int sample_rate, int channels, int bitrate_kbps) {
    if (channels != 1 && channels != 2) return NULL;

    Mp3Enc *enc = (Mp3Enc *) calloc(1, sizeof(Mp3Enc));
    if (enc == NULL) return NULL;

    enc->lame = lame_init();
    if (enc->lame == NULL) {
        free(enc);
        return NULL;
    }
    enc->channels = channels;

    lame_set_in_samplerate(enc->lame, sample_rate);
    lame_set_out_samplerate(enc->lame, sample_rate);
    lame_set_num_channels(enc->lame, channels);
    lame_set_mode(enc->lame, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(enc->lame, vbr_off);
    lame_set_brate(enc->lame, bitrate_kbps);
    lame_set_quality(enc->lame, 2);
    /* Flux ecrit en continu : pas d'en-tete Xing a reecrire en fin de fichier. */
    lame_set_bWriteVbrTag(enc->lame, 0);

    if (lame_init_params(enc->lame) < 0) {
        lame_close(enc->lame);
        free(enc);
        return NULL;
    }
    return enc;
}

int mp3enc_encode(Mp3Enc *enc, const short *pcm, int frames,
                  unsigned char *out, int out_size) {
    if (enc == NULL || enc->lame == NULL) return -3;
    if (enc->channels == 2) {
        return lame_encode_buffer_interleaved(
            enc->lame, (short *) pcm, frames, out, out_size);
    }
    return lame_encode_buffer(
        enc->lame, pcm, pcm, frames, out, out_size);
}

int mp3enc_flush(Mp3Enc *enc, unsigned char *out, int out_size) {
    if (enc == NULL || enc->lame == NULL) return -3;
    return lame_encode_flush(enc->lame, out, out_size);
}

void mp3enc_destroy(Mp3Enc *enc) {
    if (enc == NULL) return;
    if (enc->lame != NULL) lame_close(enc->lame);
    free(enc);
}
