cmake_minimum_required(VERSION 3.22.1)
project(mp3enc C)

set(LAME_SOURCES
    lame/bitstream.c
    lame/encoder.c
    lame/fft.c
    lame/gain_analysis.c
    lame/id3tag.c
    lame/lame.c
    lame/mpglib_interface.c
    lame/newmdct.c
    lame/presets.c
    lame/psymodel.c
    lame/quantize.c
    lame/quantize_pvt.c
    lame/reservoir.c
    lame/set_get.c
    lame/tables.c
    lame/takehiro.c
    lame/util.c
    lame/vbrquantize.c
    lame/VbrTag.c
    lame/version.c
)

add_library(mp3enc SHARED mp3_jni.c mp3core.c ${LAME_SOURCES})

target_include_directories(mp3enc PRIVATE lame)
target_compile_definitions(mp3enc PRIVATE STDC_HEADERS HAVE_STDINT_H)
# Sources LAME historiques : on masque leurs avertissements.
target_compile_options(mp3enc PRIVATE -O3 -w)
# Compatibilite appareils Android 15+ a pages memoire de 16 Ko.
target_link_options(mp3enc PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(mp3enc log)
