package io.embodit.sunpatch.domain

/**
 * The 3D label plates (furniture tags, lamp numbers, spacing labels; taste profile D10): a quad in its
 * local XY plane whose front is +Z, turned about the vertical only so it faces the viewer, never pitched,
 * never mirrored.
 *
 * 0.1.0 drew every plate upside down (headset feedback 2026-10-26, "dark coffee table" unreadable): the
 * engine samples a bitmap with v = 0 at its bottom row (Sky Dome QA q1-2 found the same), while the quad
 * gave its top edge v = 0. The corner UVs below are written for the engine's convention, and
 * [BillboardTest] checks the whole chain: yaw-only, front to the viewer, +X to the viewer's right, the
 * bitmap's top row on top and its left column on the left.
 */
object Billboard {
    /** Quad corners in local metres (unit size): bottom-left, bottom-right, top-right, top-left. */
    val CORNERS: List<Vec3> = listOf(Vec3(-0.5f, -0.5f, 0f), Vec3(0.5f, -0.5f, 0f), Vec3(0.5f, 0.5f, 0f), Vec3(-0.5f, 0.5f, 0f))

    /** UV of each corner: u = 0 on the left, v = 1 on the top (engine: v = 1 is the bitmap's top row). */
    val UV: List<Pair<Float, Float>> = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)

    /** Counter-clockwise seen from +Z, the plate's front. */
    val TRIANGLES: List<Int> = listOf(0, 1, 2, 0, 2, 3)

    /** Bitmap row (0 = top) that the engine samples for [v], as a fraction of the height. */
    fun bitmapRow(v: Float): Float = 1f - v

    /** Rotation that turns the plate at [center] towards [viewer] about the vertical axis only. */
    fun yawTowards(center: Vec3, viewer: Vec3): Quat4 {
        val to = viewer - center
        val flat = Vec3(to.x, 0f, to.z)
        val front = if (flat.length < 1e-3f) Vec3(0f, 0f, 1f) else flat.normalized()
        val right = Vec3.UP.cross(front).normalized()
        return Quat4.fromBasis(right, Vec3.UP, front)
    }
}

/**
 * The light layer quad (a unit square in the local XZ plane, facing +Y, scaled to a surface field's box). Field
 * row iz = 0 is the cell row at the box's min Z and is bitmap row 0; with the engine's bottom-up v, the corners at
 * local z = -0.5 need v = 1. 0.1.0 had v = 0 there, which mirrors the pools along Z unless a lamp sits over the
 * surface's middle (`LayerMappingTest`; `flipv on` in a debug build swaps back to the 0.1.0 mapping).
 */
object LayerQuad {
    /** Corners in local metres: (-x,-z), (+x,-z), (+x,+z), (-x,+z). */
    val CORNERS: List<Vec3> = listOf(Vec3(-0.5f, 0f, -0.5f), Vec3(0.5f, 0f, -0.5f), Vec3(0.5f, 0f, 0.5f), Vec3(-0.5f, 0f, 0.5f))

    /** UV per corner: u = 0 at min X, v = 1 at min Z (bitmap row 0). */
    fun uv(legacy: Boolean = false): List<Pair<Float, Float>> =
        if (legacy) listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f) else listOf(0f to 1f, 1f to 1f, 1f to 0f, 0f to 0f)
}
