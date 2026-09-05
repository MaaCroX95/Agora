package com.newoether.agora.ui.chat

import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter

/** Stable semantic state shared by every fixed-geometry media viewport. */
internal enum class MediaLoadPresentation {
    LOADING,
    LOADED,
    FAILED,
}

internal const val MEDIA_STATE_CROSSFADE_MILLIS = 200
internal val MEDIA_LOADING_INDICATOR_STROKE_WIDTH = 3.dp

internal fun mediaLoadPresentation(
    loaded: Boolean,
    failed: Boolean,
): MediaLoadPresentation = when {
    failed -> MediaLoadPresentation.FAILED
    loaded -> MediaLoadPresentation.LOADED
    else -> MediaLoadPresentation.LOADING
}

internal fun AsyncImagePainter.State.toMediaLoadPresentation(): MediaLoadPresentation =
    mediaLoadPresentation(
        loaded = this is AsyncImagePainter.State.Success,
        failed = this is AsyncImagePainter.State.Error,
    )
