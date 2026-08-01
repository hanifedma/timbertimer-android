package com.example.timbertimer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeCap
import com.example.timbertimer.data.model.TreeSpecies
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The web app's eight hand-drawn trees, redrawn as Compose vectors.
 *
 * Every species keeps the original's `0 0 100 116` coordinate space and its
 * exact control points, so a forest looks the same on the phone as on the
 * website — down to which way a palm frond bends. The whole space is scaled to
 * whatever box the caller gives, anchored bottom-centre, which is what the SVG's
 * `preserveAspectRatio="xMidYMax meet"` did.
 */
@Composable
fun TreeArt(
    species: TreeSpecies,
    palette: TreePalette,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) { drawTree(species, palette) }
}

fun DrawScope.drawTree(species: TreeSpecies, palette: TreePalette) {
    if (size.width <= 0f || size.height <= 0f) return

    val scale = min(size.width / VIEW_WIDTH, size.height / VIEW_HEIGHT)
    val offsetX = (size.width - VIEW_WIDTH * scale) / 2f
    val offsetY = size.height - VIEW_HEIGHT * scale

    withTransform({
        translate(offsetX, offsetY)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        when (species) {
            TreeSpecies.PINE -> pine(palette)
            TreeSpecies.CANOPY -> canopy(palette)
            TreeSpecies.PALM -> palm(palette)
            TreeSpecies.BAMBOO -> bamboo(palette)
            TreeSpecies.FERN -> fern(palette)
            TreeSpecies.KAPOK -> kapok(palette)
            TreeSpecies.MANGROVE -> mangrove(palette)
            TreeSpecies.WILTED -> wilted()
        }
    }
}

// ---------- species ----------

private fun DrawScope.pine(p: TreePalette) {
    trunk(topY = 100f, baseWidth = 11f, topWidth = 7f, palette = p)
    tier(70f, 102f, 33f, p)
    tier(46f, 80f, 27f, p)
    tier(24f, 58f, 20f, p)
}

private fun DrawScope.tier(apexY: Float, baseY: Float, halfWidth: Float, p: TreePalette) {
    drawPath(
        polygon(50f to apexY, (50f + halfWidth) to baseY, (50f - halfWidth) to baseY),
        p.leafA,
    )
    // The right half in the darker leaf gives the light a direction.
    drawPath(
        polygon(50f to apexY, (50f + halfWidth) to baseY, 50f to baseY),
        p.leafB,
        alpha = 0.28f,
    )
}

private fun DrawScope.canopy(p: TreePalette) {
    // The trunk runs up into the crown so no bare gap shows where they meet.
    trunk(topY = 68f, baseWidth = 12f, topWidth = 8f, palette = p)
    blob(50f, 52f, 28f, p.leafA)
    blob(30f, 62f, 19f, p.leafA)
    blob(70f, 62f, 19f, p.leafA)
    blob(36f, 40f, 17f, p.leafA)
    blob(64f, 42f, 16f, p.leafA)
    blob(50f, 32f, 19f, p.leafA)
    blob(58f, 60f, 14f, p.leafB, 0.24f)
    blob(44f, 66f, 12f, p.leafB, 0.22f)
}

private fun DrawScope.palm(p: TreePalette) {
    val originX = 55f
    val originY = 60f

    drawPath(
        Path().apply {
            moveTo(44f, 116f)
            quadraticTo(50f, 88f, originX - 4f, originY + 2f)
            lineTo(originX + 4f, originY + 2f)
            quadraticTo(54f, 88f, 52f, 116f)
            close()
        },
        p.barkA,
    )
    drawPath(
        Path().apply {
            moveTo(48f, 116f)
            quadraticTo(53f, 88f, originX + 4f, originY + 2f)
            lineTo(originX + 4f, originY + 2f)
            quadraticTo(54f, 88f, 52f, 116f)
            close()
        },
        p.barkB,
        alpha = 0.4f,
    )

    val fronds = listOf(-158f to 30f, -130f to 36f, -100f to 38f, -80f to 38f, -50f to 36f, -22f to 30f)
    fronds.forEachIndexed { index, (angle, length) ->
        blade(originX, originY, angle, length, 7f, 8f, if (index % 2 == 1) p.leafB else p.leafA)
    }
    blob(originX, originY, 4f, p.barkB)
}

private fun DrawScope.bamboo(p: TreePalette) {
    stalk(40f, 46f, 7f, p)
    stalk(50f, 30f, 7.5f, p)
    stalk(60f, 52f, 6.5f, p)
    blade(50f, 30f, -60f, 16f, 3.5f, 2f, p.leafA)
    blade(50f, 34f, -110f, 16f, 3.5f, 2f, p.leafA)
    blade(40f, 46f, -50f, 16f, 3.5f, 2f, p.leafA)
    blade(60f, 52f, -120f, 16f, 3.5f, 2f, p.leafA)
}

private fun DrawScope.stalk(x: Float, topY: Float, width: Float, p: TreePalette) {
    val height = 116f - topY
    drawRoundRect(
        color = p.leafA,
        topLeft = Offset(x - width / 2f, topY),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f),
    )
    drawRoundRect(
        color = p.leafB,
        topLeft = Offset(x, topY),
        size = Size(width / 2f, height),
        cornerRadius = CornerRadius(width / 4f),
        alpha = 0.35f,
    )
    var nodeY = topY + 14f
    while (nodeY < 114f) {
        drawRoundRect(
            color = p.leafB,
            topLeft = Offset(x - width / 2f - 1f, nodeY),
            size = Size(width + 2f, 2.5f),
            cornerRadius = CornerRadius(1f),
        )
        nodeY += 18f
    }
}

private fun DrawScope.fern(p: TreePalette) {
    val fronds = listOf(
        -160f to 38f, -140f to 44f, -118f to 48f, -95f to 50f,
        -85f to 50f, -62f to 48f, -40f to 44f, -20f to 38f,
    )
    fronds.forEachIndexed { index, (angle, length) ->
        blade(50f, 114f, angle, length, 5f, 6f, if (index % 2 == 1) p.leafB else p.leafA)
    }
}

private fun DrawScope.kapok(p: TreePalette) {
    trunk(topY = 54f, baseWidth = 16f, topWidth = 9f, palette = p)

    drawPath(
        Path().apply {
            moveTo(41f, 116f)
            quadraticTo(44f, 107f, 50f, 107f)
            lineTo(50f, 116f)
            close()
        },
        p.barkB,
    )
    drawPath(
        Path().apply {
            moveTo(59f, 116f)
            quadraticTo(56f, 107f, 50f, 107f)
            lineTo(50f, 116f)
            close()
        },
        p.barkA,
    )

    oval(50f, 50f, 40f, 15f, p.leafA)
    blob(36f, 42f, 13f, p.leafA)
    blob(50f, 38f, 15f, p.leafA)
    blob(64f, 42f, 13f, p.leafA)
    oval(52f, 56f, 34f, 9f, p.leafB, 0.3f)
}

private fun DrawScope.mangrove(p: TreePalette) {
    listOf(34f, 42f, 58f, 66f).forEachIndexed { index, x ->
        val width = if (index == 1 || index == 2) 5f else 4f
        drawPath(
            Path().apply {
                moveTo(50f, 86f)
                quadraticTo((50f + x) / 2f, 100f, x, 116f)
            },
            p.barkB,
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
    }

    drawRoundRect(
        color = p.barkA,
        topLeft = Offset(46f, 62f),
        size = Size(8f, 30f),
        cornerRadius = CornerRadius(3f),
    )

    blob(50f, 52f, 22f, p.leafA)
    blob(34f, 60f, 15f, p.leafA)
    blob(66f, 60f, 15f, p.leafA)
    blob(42f, 44f, 14f, p.leafA)
    blob(60f, 44f, 14f, p.leafA)
    blob(56f, 58f, 12f, p.leafB, 0.24f)
    blob(44f, 62f, 11f, p.leafB, 0.22f)
}

/** A sad, bare tree: leaning trunk, dead branches, a few drooping leaves. */
private fun DrawScope.wilted() {
    val bark = Color(0xFF7A6A4E)
    val barkDark = Color(0xFF5D5039)
    val leafA = Color(0xFF8A7550)
    val leafB = Color(0xFF6A5940)

    drawPath(
        Path().apply {
            moveTo(50f, 116f)
            quadraticTo(45f, 92f, 54f, 70f)
            quadraticTo(60f, 50f, 51f, 32f)
        },
        bark,
        style = Stroke(width = 9f, cap = StrokeCap.Round),
    )

    branch(53f, 72f, 38f, 66f, 28f, 52f, barkDark, 5.5f)
    branch(57f, 56f, 72f, 52f, 80f, 39f, barkDark, 5.5f)
    branch(52f, 90f, 66f, 86f, 73f, 76f, barkDark, 4.5f)

    blade(28f, 52f, 105f, 19f, 6.5f, 13f, leafA)
    blade(80f, 39f, 72f, 19f, 6.5f, 13f, leafB)
    blade(73f, 76f, 84f, 16f, 5.5f, 11f, leafA)
    blade(51f, 32f, 96f, 17f, 6f, 11f, leafB)
}

// ---------- primitives ----------

private fun DrawScope.trunk(
    topY: Float,
    baseWidth: Float,
    topWidth: Float,
    palette: TreePalette,
    baseY: Float = 116f,
) {
    val baseHalf = baseWidth / 2f
    val topHalf = topWidth / 2f
    val midY = (baseY + topY) / 2f

    drawPath(
        Path().apply {
            moveTo(50f - baseHalf, baseY)
            quadraticTo(50f - baseHalf + 1f, midY, 50f - topHalf, topY)
            lineTo(50f + topHalf, topY)
            quadraticTo(50f + baseHalf - 1f, midY, 50f + baseHalf, baseY)
            close()
        },
        palette.barkA,
    )
    drawPath(
        Path().apply {
            moveTo(50f, baseY)
            lineTo(50f + topHalf, topY)
            quadraticTo(50f + baseHalf - 1f, midY, 50f + baseHalf, baseY)
            close()
        },
        palette.barkB,
        alpha = 0.4f,
    )
}

/**
 * A leaf or frond: out along [angleDegrees] for [length], bulging by [width] to
 * either side of its midpoint, with [droop] pulling that midpoint downward.
 */
private fun DrawScope.blade(
    originX: Float,
    originY: Float,
    angleDegrees: Float,
    length: Float,
    width: Float,
    droop: Float,
    color: Color,
) {
    val radians = angleDegrees * Math.PI.toFloat() / 180f
    val dirX = cos(radians)
    val dirY = sin(radians)
    val tipX = originX + dirX * length
    val tipY = originY + dirY * length
    // Perpendicular to the direction, so the bulge is across the blade.
    val perpX = -dirY
    val perpY = dirX
    val midX = originX + dirX * length * 0.5f
    val midY = originY + dirY * length * 0.5f + droop

    drawPath(
        Path().apply {
            moveTo(originX, originY)
            quadraticTo(midX + perpX * width, midY + perpY * width, tipX, tipY)
            quadraticTo(midX - perpX * width, midY - perpY * width, originX, originY)
            close()
        },
        color,
    )
}

private fun DrawScope.branch(
    startX: Float,
    startY: Float,
    controlX: Float,
    controlY: Float,
    endX: Float,
    endY: Float,
    color: Color,
    width: Float,
) {
    drawPath(
        Path().apply {
            moveTo(startX, startY)
            quadraticTo(controlX, controlY, endX, endY)
        },
        color,
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

private fun DrawScope.blob(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float = 1f) {
    drawCircle(color = color, radius = radius, center = Offset(cx, cy), alpha = alpha)
}

private fun DrawScope.oval(
    cx: Float,
    cy: Float,
    radiusX: Float,
    radiusY: Float,
    color: Color,
    alpha: Float = 1f,
) {
    drawOval(
        color = color,
        topLeft = Offset(cx - radiusX, cy - radiusY),
        size = Size(radiusX * 2f, radiusY * 2f),
        alpha = alpha,
    )
}

private fun polygon(vararg points: Pair<Float, Float>): Path = Path().apply {
    points.forEachIndexed { index, (x, y) ->
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private const val VIEW_WIDTH = 100f
private const val VIEW_HEIGHT = 116f
