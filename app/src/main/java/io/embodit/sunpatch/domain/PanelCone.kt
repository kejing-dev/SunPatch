package io.embodit.sunpatch.domain

import kotlin.math.abs
import kotlin.math.atan

/**
 * QA #1 q1-3 (profile D14 / D20 sight cone): the system opens the main window 1.75 m ahead on the line of sight and SDK
 * 6.1 cannot place it anywhere else (native-window-move §4). A window marked on the wall ahead then sits right behind
 * the panel: frame, label plate and sun ray are hidden. So the panel opens only while the head's yaw keeps its drawn
 * width out of every marked window's yaw span seen from the head, plus [MARGIN_DEG]; else the Stage says which way to
 * turn (the smaller turn). Yaw is + to the left (`Compass.dirOfYaw`).
 *
 * Pure: the caller passes the head position, the head yaw and each window's corners.
 */
object PanelCone {
    /** Drawn half width of the 560 dp window at 1.75 m: 560 x 0.00173 m / 2 = 0.48 m -> 15.5 deg (+ the TabBar capsule). */
    val PANEL_HALF_DEG = Math.toDegrees(atan(0.48 / PANEL_DIST_M)).toFloat() + 1.5f

    /** Room for the window's label plate (20 cm above the frame) and the sun disc on the ray. */
    const val MARGIN_DEG = 8f

    private const val PANEL_DIST_M = 1.75

    /** Yaw span [min, max] of a window seen from [head], relative to [headYaw] (+ left); null when it is behind. */
    fun span(head: Vec3, headYaw: Float, corners: List<Vec3>): Pair<Float, Float>? {
        val rel = corners.map { c -> wrapDeg(Compass.yawOf(Vec3(c.x - head.x, 0f, c.z - head.z)) - headYaw) }
        if (rel.all { abs(it) > 100f }) return null
        return rel.min() to rel.max()
    }

    /** True when a panel opened now (centred on [headYaw]) would leave every window in sight. */
    fun clear(head: Vec3, headYaw: Float, windows: List<List<Vec3>>): Boolean = turnFor(head, headYaw, windows) == 0f

    /**
     * The smallest yaw turn (deg, + left) after which a panel opened on the line of sight covers no window; 0 when it
     * covers none already; null when no turn clears them all (windows all around: open where the user looks).
     */
    fun turnFor(head: Vec3, headYaw: Float, windows: List<List<Vec3>>): Float? {
        if (windows.isEmpty()) return 0f
        val half = PANEL_HALF_DEG + MARGIN_DEG
        fun ok(t: Float) = windows.all { c -> span(head, headYaw + t, c)?.let { (lo, hi) -> hi < -half || lo > half } ?: true }
        if (ok(0f)) return 0f
        // each window's yaw span around its own middle (no wrap), then the two panel yaws just past either edge
        val candidates = windows.flatMap { c ->
            val mid = Compass.yawOf(Vec3(c.sumOf { it.x.toDouble() }.toFloat() / c.size - head.x, 0f, c.sumOf { it.z.toDouble() }.toFloat() / c.size - head.z))
            val rel = c.map { wrapDeg(Compass.yawOf(Vec3(it.x - head.x, 0f, it.z - head.z)) - mid) }
            listOf(wrapDeg(mid + rel.max() + half + 0.5f - headYaw), wrapDeg(mid + rel.min() - half - 0.5f - headYaw))
        }
        return candidates.filter { ok(it) }.minByOrNull { abs(it) }
    }
}
