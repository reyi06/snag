package com.snag.downloader.ui

import kotlin.math.roundToInt

/**
 * The app's motion runs at 1.4x the baseline speed so taps and screen changes feel
 * immediate rather than decorative.
 */
object SnagMotion {
    const val Speed = 1.4f

    fun ms(base: Int): Int = (base / Speed).roundToInt()

    fun stiffness(base: Float): Float = base * Speed * Speed
}
