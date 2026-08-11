package com.focus.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Two colours, and nothing else. Every other tone in the app is Bone at a
 * lower opacity, which keeps the whole interface tonally coherent and means
 * a single value change re-skins everything.
 *
 * Ink is cool and slightly blue; Bone is warm. That small temperature gap is
 * what stops a black-and-white screen reading as a void.
 */
object Focus {

    val Ink = Color(0xFF0F1214)
    val Bone = Color(0xFFE7E2D8)

    /** Raised surfaces: search field, pressed rows. */
    val Surface = Bone.copy(alpha = 0.05f)
    val SurfacePressed = Bone.copy(alpha = 0.09f)

    /** Text roles. */
    val Primary = Bone
    val Secondary = Bone.copy(alpha = 0.45f)
    val Tertiary = Bone.copy(alpha = 0.26f)
    val Ghost = Bone.copy(alpha = 0.14f)

    /** Shape. Generous but not pill-shaped — softened rectangles, not bubbles. */
    val RadiusField = 18.dp
    val RadiusRow = 14.dp

    /** Rhythm. One horizontal gutter used everywhere. */
    val Gutter = 24.dp
    val RowInset = 14.dp

    /** Type scale. */
    val ClockSize = 60.sp
    val SearchSize = 20.sp
    val AppSize = 19.sp
    val MetaSize = 13.sp

    /** Slightly open tracking makes lowercase text feel calmer. */
    val Tracking = 0.4.sp
}
