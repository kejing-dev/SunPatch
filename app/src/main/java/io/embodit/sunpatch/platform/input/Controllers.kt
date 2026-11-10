package io.embodit.sunpatch.platform.input

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.sunpatch.domain.Quat4
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.platform.SpLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A pointing ray in Stage metres (unit direction). */
data class Ray(val origin: Vec3, val dir: Vec3)

/**
 * Both PICO controllers (design `input.modes`): the aiming controller's ray (pose from `latestData`, -Z forward; the one
 * whose trigger was pressed last aims), the trigger state (held = dragging a marker with the ray) and B / Y presses
 * (cancel corner marking, design `spatialLayout.stagePlate`). The listener runs on the data source's thread and only
 * stores values; the frame system reads them. The trigger itself arrives as the system tap / drag on the app's own
 * colliders (light-planner pattern), so a press is never counted twice.
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    private val trigR = AtomicBoolean(false)
    private val trigL = AtomicBoolean(false)
    private val backDown = AtomicBoolean(false)
    private val backPresses = AtomicInteger(0)
    @Volatile private var useRight = true

    /** Debug `ray`: a stand-in ray that wins over the controllers until [debugUntilMs] (the emulator has no controller pose). */
    @Volatile private var debugRay: Ray? = null
    @Volatile private var debugUntilMs = 0L

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
            val r = runCatching { a.right.triggerPressed }.getOrDefault(false)
            val l = runCatching { a.left.triggerPressed }.getOrDefault(false)
            if (l && !r) useRight = false
            if (r && !l) useRight = true
            trigR.set(r)
            trigL.set(l)
            val back = runCatching { a.right.bButtonPressed || a.left.yButtonPressed }.getOrDefault(false)
            if (back && !backDown.getAndSet(true)) backPresses.incrementAndGet()
            if (!back) backDown.set(false)
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val r = runCatching { p.start() }
        SpLog.i("TRACK controller start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        trigR.set(false)
        trigL.set(false)
    }

    private fun supported(): Boolean = provider?.let { runCatching { it.supportState }.getOrNull() } == DataProvider.SupportState.SUPPORTED

    val triggerHeld: Boolean
        get() = trigR.get() || trigL.get()

    fun takeBackPresses(): Int = backPresses.getAndSet(0)

    /** Ray of the aiming controller, or the debug ray, or null. */
    fun ray(): Ray? {
        if (SystemClock.elapsedRealtime() < debugUntilMs) return debugRay
        val p = provider ?: return null
        if (!supported()) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val pose = (if (useRight) data.right ?: data.left else data.left ?: data.right) ?: return null
        val pos = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val q = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!pos.isFinite() || pos.length < 1e-3f) return null
        val fwd = q.rotate(Vec3.AHEAD)
        if (!fwd.isFinite() || fwd.length < 0.5f) return null
        return Ray(pos, fwd.normalized())
    }

    val debugRayActive: Boolean
        get() = SystemClock.elapsedRealtime() < debugUntilMs

    fun debugAim(origin: Vec3, dir: Vec3, ms: Long) {
        debugRay = Ray(origin, dir.normalized())
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${provider?.let { runCatching { it.supportState }.getOrNull() }} debugRay=$debugRayActive trigger=$triggerHeld"
}
