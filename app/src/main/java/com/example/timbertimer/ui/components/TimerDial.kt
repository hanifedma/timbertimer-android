package com.example.timbertimer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.ui.theme.clockStyle

/**
 * The centrepiece: a progress ring around a tree that grows as the session runs,
 * with the clock beneath it.
 *
 * [growth] is a separate signal from [progress] on purpose. The ring tracks the
 * countdown exactly, while the tree grows on its own curve — and a stopwatch,
 * which has no goal to show a ring for, still has a tree that gets bigger.
 */
@Composable
fun TimerDial(
    species: TreeSpecies,
    palette: TreePalette,
    progress: Float,
    showRing: Boolean,
    growth: Float,
    clockText: String,
    stateLabel: String,
    progressLabel: String?,
    modifier: Modifier = Modifier,
) {
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    // Eased so a session adopted mid-flight from another device settles into
    // place instead of snapping, and so each tick is a glide, not a jump.
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420),
        label = "ring",
    )
    val animatedGrowth by animateFloatAsState(
        targetValue = growth.coerceIn(0.08f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "growth",
    )

    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        if (showRing) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.045f
                // Inset by half the stroke so the ring's outer edge sits inside
                // the box rather than being clipped by it.
                val diameter = size.minDimension - stroke
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f,
                )

                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (animatedProgress > 0f) {
                    drawArc(
                        color = ringColor,
                        // Twelve o'clock, clockwise — the way a clock face reads.
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (showRing) 34.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TreeArt(
                    species = species,
                    palette = palette,
                    modifier = Modifier
                        .fillMaxSize()
                        // Scaled about the base, so it grows upward out of the
                        // ground rather than swelling from its middle.
                        .scale(0.55f + animatedGrowth * 0.45f),
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = clockText,
                style = clockStyle(if (clockText.length > 5) 40.sp else 48.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (progressLabel != null) "$stateLabel · $progressLabel" else stateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
