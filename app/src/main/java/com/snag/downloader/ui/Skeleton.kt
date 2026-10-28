package com.snag.downloader.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A quiet gold-wash loading page shown while the first screen settles. */
@Composable
internal fun SnagSkeletonPage(
    tab: Tab,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val gold = snagColors().gold
    val transition = rememberInfiniteTransition(label = "skeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-phase",
    )
    val base = scheme.surfaceVariant
    val highlight = scheme.outline.copy(alpha = 0.62f)
    val wash = Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            gold.copy(alpha = 0.06f),
            Color.Transparent,
        ),
        start = Offset.Zero,
        end = Offset(1_000f, 2_400f),
    )

    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .background(wash),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                SkeletonBlock(phase, base, highlight, Modifier.width(132.dp).height(34.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBlock(phase, base, highlight, Modifier.size(28.dp), CircleShape)
            }
            SkeletonBlock(phase, base, highlight, Modifier.width(210.dp).height(13.dp))
            Spacer(Modifier.height(6.dp))
            when (tab) {
                Tab.SAVE -> SaveSkeleton(phase, base, highlight)
                Tab.LIBRARY -> LibrarySkeleton(phase, base, highlight)
                Tab.SETTINGS -> SettingsSkeleton(phase, base, highlight)
            }
        }
    }
}

@Composable
private fun SaveSkeleton(
    phase: Float,
    base: Color,
    highlight: Color,
) {
    SkeletonBlock(
        phase, base, highlight,
        Modifier.fillMaxWidth().height(172.dp),
        RoundedCornerShape(12.dp),
    )
    SkeletonBlock(
        phase, base, highlight,
        Modifier.fillMaxWidth().height(46.dp),
        RoundedCornerShape(8.dp),
    )
    SkeletonBlock(
        phase, base, highlight,
        Modifier.fillMaxWidth().height(224.dp),
        RoundedCornerShape(12.dp),
    )
    Spacer(Modifier.height(4.dp))
    SkeletonBlock(phase, base, highlight, Modifier.width(100.dp).height(17.dp))
    repeat(3) {
        SkeletonRow(phase, base, highlight)
    }
}

@Composable
private fun LibrarySkeleton(
    phase: Float,
    base: Color,
    highlight: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(54, 80, 78, 68, 64).forEach { width ->
            SkeletonBlock(
                phase, base, highlight,
                Modifier.width(width.dp).height(40.dp),
                RoundedCornerShape(8.dp),
            )
        }
    }
    Spacer(Modifier.height(2.dp))
    SkeletonBlock(phase, base, highlight, Modifier.width(74.dp).height(13.dp))
    repeat(4) {
        SkeletonRow(phase, base, highlight)
    }
}

@Composable
private fun SettingsSkeleton(
    phase: Float,
    base: Color,
    highlight: Color,
) {
    SkeletonBlock(phase, base, highlight, Modifier.width(130.dp).height(18.dp))
    SkeletonBlock(
        phase, base, highlight,
        Modifier.fillMaxWidth().height(174.dp),
        RoundedCornerShape(12.dp),
    )
    SkeletonBlock(phase, base, highlight, Modifier.width(92.dp).height(18.dp))
    SkeletonBlock(
        phase, base, highlight,
        Modifier.fillMaxWidth().height(174.dp),
        RoundedCornerShape(12.dp),
    )
    SkeletonBlock(phase, base, highlight, Modifier.width(112.dp).height(18.dp))
    SkeletonBlock(
        phase, base, highlight,
        Modifier.fillMaxWidth().height(58.dp),
        RoundedCornerShape(12.dp),
    )
}

@Composable
private fun SkeletonRow(
    phase: Float,
    base: Color,
    highlight: Color,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        SkeletonBlock(
            phase, base, highlight,
            Modifier.size(52.dp),
            RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonBlock(phase, base, highlight, Modifier.fillMaxWidth(0.78f).height(13.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBlock(phase, base, highlight, Modifier.fillMaxWidth(0.48f).height(10.dp))
        }
    }
}

@Composable
private fun SkeletonBlock(
    phase: Float,
    base: Color,
    highlight: Color,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp),
) {
    val shift = phase * 1_200f
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(shift - 500f, 0f),
        end = Offset(shift, 0f),
    )
    Box(modifier.background(brush, shape))
}
