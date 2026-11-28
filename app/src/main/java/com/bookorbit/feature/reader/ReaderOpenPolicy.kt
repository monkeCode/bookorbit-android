package com.bookorbit.feature.reader

/** EPUB archives are fetched in full only by the explicit download action. */
internal fun shouldStreamEpub(format: String?, downloaded: Boolean): Boolean =
    !downloaded && format.equals("epub", ignoreCase = true)
