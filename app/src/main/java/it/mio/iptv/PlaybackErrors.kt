package it.mio.iptv

import androidx.media3.common.PlaybackException

/** Aiuti per capire quale errore di riproduzione mostrare all'utente. */
object Playback {
    /** Errori del decoder video (es. la TV non riesce ad aprire altri decoder hardware). */
    fun isDecoderError(e: PlaybackException) = e.errorCode in 4000..4999

    /** Errori di formato/manifest (es. ERROR_CODE_PARSING_MANIFEST_MALFORMED). */
    fun isFormatError(e: PlaybackException) = e.errorCode in 3000..3999

    /**
     * Dopo il secondo tentativo (come HLS) l'errore "manifest malformato" è quasi sempre solo l'effetto del
     * tentativo sbagliato: se il primo errore era un altro (rete, decoder...) è quello da mostrare.
     */
    fun pickError(first: PlaybackException?, last: PlaybackException): PlaybackException =
        if (first != null && isFormatError(last) && !isFormatError(first)) first else last
}
