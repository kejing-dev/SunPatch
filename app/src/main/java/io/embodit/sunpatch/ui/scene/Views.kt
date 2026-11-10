package io.embodit.sunpatch.ui.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import io.embodit.sunpatch.domain.HeatRamp
import io.embodit.sunpatch.domain.HoursMap
import io.embodit.sunpatch.domain.LitSurface
import io.embodit.sunpatch.domain.Quat4
import io.embodit.sunpatch.domain.Raster
import io.embodit.sunpatch.domain.SunLight
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.domain.Verdict
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.ui.theme.SpColors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Sizes in the room (design `spatialLayout`), metres. */
internal object Dims {
    const val LAYER_ABOVE = 0.004f
    const val FRAME_W = 0.02f
    const val FRAME_OUT = 0.005f
    const val CORNER = 0.04f
    const val PLATE_ABOVE = 0.12f
    const val WINDOW_TEXT_H = 0.035f
    const val RAY_LEN = 1.2f
    const val RAY_W = 0.01f
    const val SUN_D = 0.10f
    const val RING_D = 0.16f
    const val RING_ABOVE = 0.005f
    const val DOT_D = 0.02f
    const val HIT_R = 0.06f
    const val MARKER_PLATE_ABOVE = 0.20f
    const val MARKER_TEXT_H = 0.03f
    const val CROSS = 0.06f
    const val ROOM_LINE_W = 0.01f
}

/**
 * The light layer of one receiving surface (design `spatialLayout.patch`): the shared unit square on the surface frame,
 * 4 mm off the surface, with its own bitmap at 4 cm / texel: the patch (45 % sunPatch, a brighter rim, overlap capped)
 * over the hours colours (heat on, or the sweep's trail; floors and upward faces only).
 */
internal class LightLayerView(root: Entity, meshes: SpMeshes, private val m: SpMaterials, private val queue: WorkQueue, val surface: LitSurface) {
    private val material: UnlitMaterial = m.textured(soft = true)
    private val part = Part(root, meshes.square, material, "light:${surface.id.take(14)}")
    private var texture: TextureResource? = null
    private var paintedKey: Any? = null
    private var placed = false
    private var visible = false

    init {
        part.show(false)
    }

    /** [key] changes whenever the picture would; [raster] the patch coverage (null = none), [heat] the hours layer. */
    fun show(key: Any, raster: Raster?, heat: HoursMap.Layer?, alpha: Float) {
        if (raster == null && heat == null) {
            hide()
            paintedKey = null
            return
        }
        if (key == paintedKey) {
            if (texture != null && visible) part.show(true)
            return
        }
        paintedKey = key
        queue.enqueue(this) { paint(raster, heat, alpha) }
    }

    private fun paint(raster: Raster?, heat: HoursMap.Layer?, alpha: Float) {
        val (w, h, cell) = if (raster != null) Triple(raster.w, raster.h, raster.cell) else SunLight.grid(surface, SunLight.LAYER_CELL_M, 192)
        val px = IntArray(w * h)
        val patch = SpColors.SunPatch.toArgb() and 0xFFFFFF
        val rim = SpColors.PatchRim.toArgb() and 0xFFFFFF
        val cap = min(0.95f, alpha * SunLight.PATCH_ALPHA_CAP / SunLight.PATCH_ALPHA)
        var any = false
        for (j in 0 until h) for (i in 0 until w) {
            var c = 0
            if (heat != null) {
                val hi = floor((i + 0.5f) * cell / heat.cell).toInt()
                val hj = floor((j + 0.5f) * cell / heat.cell).toInt()
                if (hi in 0 until heat.w && hj in 0 until heat.h) c = HeatRamp.argb(heat.hoursAt(hi, hj))
            }
            if (raster != null) {
                val cov = raster.cells[j * w + i].toInt()
                if (cov > 0) {
                    val edge = (i == 0 || raster.cells[j * w + i - 1].toInt() == 0) || (i == w - 1 || raster.cells[j * w + i + 1].toInt() == 0) ||
                        (j == 0 || raster.cells[(j - 1) * w + i].toInt() == 0) || (j == h - 1 || raster.cells[(j + 1) * w + i].toInt() == 0)
                    val a = if (edge) min(1f, (if (cov > 1) cap else alpha) + 0.25f) else if (cov > 1) cap else alpha
                    c = over(((a * 255f).toInt() shl 24) or (if (edge) rim else patch), c)
                }
            }
            if (c ushr 24 != 0) any = true
            px[j * w + i] = c
        }
        visible = any
        if (!any) {
            part.show(false)
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
        val old = texture
        val tex = runCatching { TextureResource.create(bitmap) }.onFailure { SpLog.e("FRAME layer texture failed: $it", it) }.getOrNull()
        bitmap.recycle()
        if (tex == null) return
        material.setBaseColorTexture(tex)
        texture = tex
        old?.let { runCatching { it.close() } }
        if (!placed) place(w * cell, h * cell)
        part.show(true)
    }

    /** Unit square -> the surface frame: local X = u, Z = v, Y = v x u; texel (i, j) at origin + u (i + .5) c + v (j + .5) c. */
    private fun place(width: Float, height: Float) {
        val f = surface.frame
        val y = f.v.cross(f.u).normalized()
        val c = f.origin + f.u * (width / 2f) + f.v * (height / 2f) + surface.normal * Dims.LAYER_ABOVE
        part.place(c, Vec3(width, 1f, height), Quat4.fromBasis(f.u, y, f.v))
        placed = true
    }

    fun hide() = part.show(false)

    fun destroy() {
        queue.cancel(this)
        part.destroy()
        texture?.let { runCatching { it.close() } }
        texture = null
        m.release(material)
    }

    private fun over(top: Int, bottom: Int): Int {
        val ta = (top ushr 24) / 255f
        val ba = (bottom ushr 24) / 255f
        val oa = ta + ba * (1f - ta)
        if (oa <= 0f) return 0
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = ((ch(top, s) * ta + ch(bottom, s) * ba * (1f - ta)) / oa).toInt().coerceIn(0, 255)
        return ((oa * 255f).toInt().coerceIn(0, 255) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/** One marked window (design `spatialLayout.window`): frame lines, corner blocks, label plate, sun ray + disc. */
internal class WindowView(root: Entity, meshes: SpMeshes, private val m: SpMaterials, queue: WorkQueue, val id: String) {
    private val lines = List(4) { Strip(root, meshes.band, m.frame, "winFrame:$id:$it") }
    private val corners = List(4) { Part(root, meshes.cube, m.frame, "winCorner:$id:$it") }
    val plate = TextPlate(root, meshes, m, queue, "winPlate:$id")
    private val ray = Strip(root, meshes.dashes, m.sunRay, "sunRay:$id")
    private val disc = Part(root, meshes.disc, m.sunDisc, "sunDisc:$id")
    var plateViewer = Vec3.ZERO
        private set

    init {
        corners.forEach { it.show(false) }
        disc.show(false)
    }

    fun place(cornersAt: List<Vec3>, outward: Vec3) {
        val n = -outward
        val c = cornersAt.map { it + n * Dims.FRAME_OUT }
        for (i in 0 until 4) lines[i].draw(c[i], c[(i + 1) % 4], Dims.FRAME_W, n)
        val rot = Quat4.fromTo(Vec3(0f, 0f, 1f), n)
        for (i in 0 until 4) corners[i].place(c[i], Vec3(Dims.CORNER, Dims.CORNER, 0.006f), rot)
        val top = (cornersAt[0] + cornersAt[1]) * 0.5f
        val at = top + Vec3(0f, Dims.PLATE_ABOVE + Dims.WINDOW_TEXT_H, 0f) + n * 0.01f
        plate.place(at, Dims.WINDOW_TEXT_H)
        plateViewer = at + n
    }

    fun sun(center: Vec3, s: Vec3?) {
        if (s == null) {
            ray.hide()
            disc.show(false)
            return
        }
        val end = center + s * Dims.RAY_LEN
        ray.draw(center, end, Dims.RAY_W, s.cross(Vec3.UP).let { if (it.length < 1e-3f) Vec3.RIGHT else it }.cross(s))
        disc.placeFacing(end, Vec3(Dims.SUN_D, 1f, Dims.SUN_D), -s)
    }

    fun hide() {
        lines.forEach { it.hide() }
        corners.forEach { it.show(false) }
        plate.hide()
        ray.hide()
        disc.show(false)
    }

    fun destroy() {
        lines.forEach { it.destroy() }
        corners.forEach { it.destroy() }
        plate.destroy()
        ray.destroy()
        disc.destroy()
    }
}

/** One marker (design `spatialLayout.marker`): verdict ring 16 cm, centre dot, 12 cm sphere collider, plate 20 cm above. */
internal class MarkerView(root: Entity, private val meshes: SpMeshes, private val m: SpMaterials, queue: WorkQueue, val id: String) {
    private var verdict: Verdict? = null
    private var ring = Part(root, meshes.ring, m.ringNeutral, "markerRing:$id")
    private val root = root
    private val dot = Part(root, meshes.disc, m.dot, "markerDot:$id")
    private val breath = Part(root, meshes.ringThin, m.breath, "markerSel:$id")
    val plate = TextPlate(root, meshes, m, queue, "markerPlate:$id")
    val hit = Entity()
    var pos = Vec3.ZERO
        private set

    init {
        runCatching { hit.setName("markerHit:$id") }
        // gate:allow geometry invisible 12 cm sphere collider of a marker (select / drag), design spatialLayout.marker.ring
        hit.components.set(sphereCollision(Dims.HIT_R))
        hit.components.set(InteractableComponent())
        root.addChild(hit)
        breath.show(false)
    }

    fun owns(e: Entity): Boolean = e.id == hit.id || e.id == ring.entity.id || e.id == dot.entity.id

    fun place(p: Vec3, v: Verdict?, selected: Boolean, nowMs: Long) {
        pos = p
        if (v != verdict) {
            verdict = v
            ring.destroy()
            val (mesh, mat) = when (v) {
                Verdict.OK -> meshes.ring to m.ringOk
                Verdict.MEH -> meshes.ringDashed to m.ringMeh
                Verdict.NO -> meshes.ringBroken to m.ringNo
                else -> meshes.ring to m.ringNeutral
            }
            ring = Part(root, mesh, mat, "markerRing:$id")
        }
        ring.place(p + Vec3(0f, Dims.RING_ABOVE, 0f), Vec3(Dims.RING_D, 1f, Dims.RING_D))
        dot.place(p + Vec3(0f, Dims.RING_ABOVE + 0.0005f, 0f), Vec3(Dims.DOT_D, 1f, Dims.DOT_D))
        if (selected) {
            val k = 0.21f + 0.02f * sin(nowMs / 1000.0 * 2.0 * Math.PI / 1.6).toFloat()
            breath.place(p + Vec3(0f, Dims.RING_ABOVE + 0.001f, 0f), Vec3(k, 1f, k))
        } else breath.show(false)
        hit.setLocal(position = p + Vec3(0f, 0.03f, 0f))
        hit.on(true)
        plate.place(p + Vec3(0f, Dims.MARKER_PLATE_ABOVE, 0f), Dims.MARKER_TEXT_H)
    }

    fun hide() {
        ring.show(false)
        dot.show(false)
        breath.show(false)
        plate.hide()
        hit.on(false)
    }

    fun destroy() {
        ring.destroy()
        dot.destroy()
        breath.destroy()
        plate.destroy()
        runCatching { hit.destroy(true) }
    }
}

/**
 * The aim feedback (design `spatialLayout.reticle`): S04 a 6 cm cross on a wall + a word, the dashed preview rectangle
 * after the first corner, a coral cross where it cannot go; S11 the ghost ring on a floor / upward face.
 */
internal class ReticleView(root: Entity, meshes: SpMeshes, private val m: SpMaterials, queue: WorkQueue) {
    private val cross = List(2) { Strip(root, meshes.band, m.reticle, "reticleCross:$it") }
    private val preview = List(4) { Strip(root, meshes.dashes, m.reticleDash, "reticlePreview:$it") }
    private val ghost = Part(root, meshes.ringDashed, m.ghost, "ghostRing")
    private val ghostDot = Part(root, meshes.disc, m.ghost, "ghostDot")
    val word = TextPlate(root, meshes, m, queue, "reticleWord")

    init {
        hide()
    }

    fun crossAt(at: Vec3, normal: Vec3, bad: Boolean) {
        val n = normal.normalized()
        var u = Vec3.UP.rejectFrom(n)
        if (u.length < 0.1f) u = Vec3.RIGHT.rejectFrom(n)
        u = u.normalized()
        val v = n.cross(u).normalized()
        val c = at + n * 0.006f
        val s = Dims.CROSS / 2f
        cross.forEach { it.material(if (bad) m.bad else m.reticle) }
        if (bad) {
            cross[0].draw(c - u * s - v * s, c + u * s + v * s, 0.008f, n)
            cross[1].draw(c - u * s + v * s, c + u * s - v * s, 0.008f, n)
        } else {
            cross[0].draw(c - u * s, c + u * s, 0.006f, n)
            cross[1].draw(c - v * s, c + v * s, 0.006f, n)
        }
    }

    fun previewRect(corners: List<Vec3>?, normal: Vec3, bad: Boolean) {
        if (corners == null) {
            preview.forEach { it.hide() }
            return
        }
        val c = corners.map { it + normal * 0.005f }
        for (i in 0 until 4) {
            preview[i].material(if (bad) m.bad else m.reticleDash)
            preview[i].draw(c[i], c[(i + 1) % 4], 0.01f, normal)
        }
    }

    fun ghostAt(at: Vec3) {
        ghost.place(at + Vec3(0f, Dims.RING_ABOVE + 0.001f, 0f), Vec3(Dims.RING_D, 1f, Dims.RING_D))
        ghostDot.place(at + Vec3(0f, Dims.RING_ABOVE + 0.0015f, 0f), Vec3(Dims.DOT_D, 1f, Dims.DOT_D))
    }

    fun hideCross() = cross.forEach { it.hide() }

    fun hideGhost() {
        ghost.show(false)
        ghostDot.show(false)
    }

    fun hide() {
        hideCross()
        preview.forEach { it.hide() }
        hideGhost()
        word.hide()
    }

    fun destroy() {
        cross.forEach { it.destroy() }
        preview.forEach { it.destroy() }
        ghost.destroy()
        ghostDot.destroy()
        word.destroy()
    }
}

/** A big invisible shell (9 m) that catches a pinch / trigger anywhere while marking corners or placing a marker. */
internal class TapCatcher(root: Entity) {
    private val shell = Entity()
    private var on = false

    init {
        runCatching { shell.setName("tapCatcher") }
        root.addChild(shell)
        val h = 9f
        val t = 0.2f
        val slabs = listOf(
            Vec3(h, 0f, 0f) to Vec3(t, 2 * h, 2 * h), Vec3(-h, 0f, 0f) to Vec3(t, 2 * h, 2 * h),
            Vec3(0f, 0f, h) to Vec3(2 * h, 2 * h, t), Vec3(0f, 0f, -h) to Vec3(2 * h, 2 * h, t),
            Vec3(0f, h, 0f) to Vec3(2 * h, t, 2 * h), Vec3(0f, -h, 0f) to Vec3(2 * h, t, 2 * h),
        )
        for ((i, s) in slabs.withIndex()) {
            val slab = Entity()
            runCatching { slab.setName("tapCatcher:$i") }
            // gate:allow geometry invisible catch-all collider shell (no mesh), active only in S04 / S11
            slab.components.set(boxCollision(s.second))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.setLocal(position = s.first)
        }
        shell.enabled = false
    }

    fun owns(e: Entity?): Boolean = runCatching { e?.getName() }.getOrNull()?.startsWith("tapCatcher") == true

    fun set(active: Boolean, center: Vec3) {
        if (active) shell.setLocal(position = center)
        if (active != on) {
            on = active
            shell.enabled = active
        }
    }

    fun destroy() {
        runCatching { shell.destroy(true) }
    }
}

/** Bitmaps for the 3D plates: text lines on an ink capsule, an optional bright border (marker in the patch). */
internal object Plates {
    const val LINE_PX = 64
    private const val PAD = 24

    private fun paint(size: Float, color: Int, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    fun text(lines: List<String>, border: Int? = null, ink: Int = SpColors.White.toArgb()): Bitmap {
        val ps = lines.mapIndexed { i, _ -> paint(if (i == 0) 40f else 34f, ink, i == 0) }
        val w = max(LINE_PX * 2, (lines.indices.maxOf { ps[it].measureText(lines[it]) } + PAD * 2).toInt())
        val h = LINE_PX * lines.size
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SpColors.PlateInk.toArgb() }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 26f, 26f, bg)
        if (border != null) {
            val b = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; style = Paint.Style.STROKE; strokeWidth = 6f }
            c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), 24f, 24f, b)
        }
        for ((i, line) in lines.withIndex()) {
            val p = ps[i]
            val fm = p.fontMetrics
            c.drawText(line, PAD.toFloat(), i * LINE_PX + LINE_PX / 2f - (fm.ascent + fm.descent) / 2f, p)
        }
        return bmp
    }
}

internal fun verdictMaterial(m: SpMaterials, v: Verdict?): Material = when (v) {
    Verdict.OK -> m.ringOk
    Verdict.MEH -> m.ringMeh
    Verdict.NO -> m.ringNo
    else -> m.ringNeutral
}
