package io.embodit.sunpatch.ui.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.sunpatch.domain.Billboard
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.platform.SpLog

/**
 * The one Stage attachment slot `stagePlate` (design `panels.stagePlate`, 360 x 160 dp, 1 dp = 0.8 mm). It is declared
 * once and bound to a holder entity; showing = moving the holder into place and enabling it, hiding = disabling it and
 * moving it far away (drone-sight / Spatial Ruler pool findings: a panel only composes after it has been in view for a
 * couple of frames, [onVisibility]). Upright, turned about the vertical only (D9 / D10). No lazy follow.
 */
internal class PlateSlot(private val stageRoot: Entity, val id: String, private val onVisibility: (Boolean) -> Unit) {
    private val holder = Entity()
    private var panel: Entity? = null
    private var boundFrame = 0L
    private var boundMs = 0L
    private var frame = 0L
    private var settled = false
    private var shown = false
    private var shownFrame = 0L
    private var reported: Boolean? = null
    private var enabledNow = true
    var position: Vec3 = Vec3.ZERO
        private set

    init {
        runCatching { holder.setName("holder:$id") }
        stageRoot.addChild(holder)
        holder.setLocal(position = PARK, scale = Vec3(PARK_SCALE, PARK_SCALE, PARK_SCALE))
    }

    val bound: Boolean
        get() = panel != null

    fun bind(attachments: SpatialViewAttachments, nowMs: Long): Boolean {
        if (panel != null) return true
        val e = attachments.entity(id) ?: return false
        holder.addChild(e)
        runCatching { e.setName("panel:$id") }
        panel = e
        boundFrame = frame
        boundMs = nowMs
        SpLog.i("PANEL bound id=$id")
        return true
    }

    fun onFrame(nowMs: Long) {
        frame++
        if (!settled && panel != null && frame - boundFrame >= 6 && nowMs - boundMs >= 600) {
            settled = true
            if (!shown) stow()
        }
        val visible = shown && frame - shownFrame >= 2
        if (reported != visible) {
            reported = visible
            onVisibility(visible)
        }
    }

    /** Shows the plate at [at], upright, its face towards [viewer] (yaw only). */
    fun show(at: Vec3, viewer: Vec3) {
        if (!shown) {
            shown = true
            shownFrame = frame
        }
        position = at
        if (!enabledNow) {
            holder.enabled = true
            enabledNow = true
        }
        holder.setLocal(position = at, rotation = Billboard.yawTowards(at, viewer), scale = Vec3(1f, 1f, 1f))
    }

    fun hide() {
        if (!shown) return
        shown = false
        stow()
    }

    private fun stow() {
        holder.setLocal(position = PARK, scale = Vec3(PARK_SCALE, PARK_SCALE, PARK_SCALE))
        if (settled && enabledNow) {
            holder.enabled = false
            enabledNow = false
        }
    }

    val isShown: Boolean
        get() = shown

    fun release() {
        runCatching { holder.removeAllChildren() }
        panel = null
        runCatching { holder.destroy(true) }
    }

    private companion object {
        val PARK = Vec3(0f, -40f, 0f)
        const val PARK_SCALE = 0.001f
    }
}
