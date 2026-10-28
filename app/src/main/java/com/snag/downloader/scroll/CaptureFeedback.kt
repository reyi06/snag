package com.snag.downloader.scroll

import android.os.Handler
import android.os.Looper

sealed interface CaptureFeedbackState {
    data object Capturing : CaptureFeedbackState
    data object Success : CaptureFeedbackState
    data class Error(val message: String) : CaptureFeedbackState
}

/** In-process bridge from BackgroundCapture to the floating button animation. */
internal object CaptureFeedback {

    @Volatile
    private var listener: ((CaptureFeedbackState) -> Unit)? = null

    fun setListener(next: ((CaptureFeedbackState) -> Unit)?) {
        listener = next
    }

    fun emit(state: CaptureFeedbackState) {
        Handler(Looper.getMainLooper()).post {
            listener?.invoke(state)
        }
    }
}
