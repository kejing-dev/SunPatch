package io.embodit.sunpatch.ui.scene

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.sunpatch.R
import io.embodit.sunpatch.domain.Compass
import io.embodit.sunpatch.domain.Dirs
import io.embodit.sunpatch.domain.HeadPose
import io.embodit.sunpatch.domain.HeadSampler
import io.embodit.sunpatch.domain.HitRole
import io.embodit.sunpatch.domain.HoursMap
import io.embodit.sunpatch.domain.PanelCone
import io.embodit.sunpatch.domain.Quat4
import io.embodit.sunpatch.domain.RoomHit
import io.embodit.sunpatch.domain.RoomKind
import io.embodit.sunpatch.domain.Solar
import io.embodit.sunpatch.domain.SunLight
import io.embodit.sunpatch.domain.SunWindow
import io.embodit.sunpatch.domain.SurfaceRole
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.domain.wrapDeg
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.platform.input.Controllers
import io.embodit.sunpatch.platform.input.Ray
import io.embodit.sunpatch.platform.tracking.HeadTracker
import io.embodit.sunpatch.ui.Ev
import io.embodit.sunpatch.ui.HeadTarget
import io.embodit.sunpatch.ui.SpController
import io.embodit.sunpatch.ui.SpUiState
import io.embodit.sunpatch.ui.components.localizedResources
import io.embodit.sunpatch.ui.theme.SpColors
import io.embodit.sunpatch.ui.window.WindowCtl
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The scene of the open Stage (debug hooks and the frame system talk to it). Main thread only. */
internal object ActiveScene {
    var current: SunScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in SpScreen). */
class SunFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context)
    }
}

/** What the Stage plate shows (design `spatialLayout.stagePlate`). */
enum class PlateMode { NONE, LOADING, MARKING, LEVEL, ASIDE, LOOK_AHEAD, LOST, BACK, OPEN }

/** Compose state shared with the plate's content (the only Compose state the frame system writes, with [SunScene.plateShown]). */
object StageUi {
    var mode by mutableStateOf(PlateMode.NONE)
    var plateShown by mutableStateOf(false)
}

/**
 * The 3D side (design `entities3d`): light layers on every receiving surface, window frames + label plates + sun rays,
 * markers, the reticle / ghost, virtual room edges, the tap catcher and the one Stage plate. Per-frame work happens in
 * [onFrame]: head (LevelGate, head samples, yaw-only plates), controller ray x room (analytic, D24), drags, rendering
 * of what changed. Head pose is never used to aim or place (D1).
 */
class SunScene {
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    val head = HeadTracker()
    val controllers = Controllers()
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var contentRoot: Entity? = null
    private var shared: SharedResources? = null
    private var materials: SpMaterials? = null
    private var meshes: SpMeshes? = null
    private val work = WorkQueue()
    private var buildJob: Job? = null
    private var attached = false
    private var built = false
    private var paused = false
    private var appContext: Context? = null
    private var res: Resources? = null
    private var resLang: Any? = null

    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastFrameMs = 0L
    private var lastLogMs = 0L
    private var startMs = 0L

    private var plate: PlateSlot? = null
    private var plateMode = PlateMode.NONE
    private var platePos: Vec3? = null
    private var plateBearing = 0f
    private var plateOffSince = -1L
    private var lastPlatePressMs = -1L

    private var catcher: TapCatcher? = null
    private var reticle: ReticleView? = null
    private var roomLines: LinePool? = null
    private val layers = HashMap<String, LightLayerView>()
    private val windowViews = HashMap<String, WindowView>()
    private val markerViews = HashMap<String, MarkerView>()
    private var renderedRoom = -1
    private var renderedWindows: Any? = null
    private var renderedLight: Any? = null
    private var renderedLang: Any? = null
    private val markerKeys = HashMap<String, Any>()

    private val sampler = HeadSampler()
    private var sampling: HeadTarget? = null
    private var aim: Ray? = null
    private var aimHit: RoomHit? = null
    private var lastRejectSeq = -1

    /** Debug `pitch` / `yawRate`: what the gate and the head sampler read instead of the real head. */
    @Volatile var debugPitch: Float? = null
    @Volatile var debugYawRate: Float? = null

    private class Drag(val id: String, val start: Vec3, val levelY: Float, val startPx: Offset3D?) {
        var live: Vec3 = start
        var total = Vec3.ZERO
        var debug = false
        var lastLogMs = 0L
    }

    private var drag: Drag? = null

    // =========================================================================================== lifecycle

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments, context: Context) {
        if (attached || stageRoot != null) detach()
        appContext = context.applicationContext
        startMs = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val cr = Entity()
        runCatching { cr.setName("content") }
        root.addChild(cr)
        contentRoot = cr
        plate = PlateSlot(root, PLATE_ID) { shown -> StageUi.plateShown = shown }
        catcher = TapCatcher(root)
        head.start()
        controllers.start()
        ActiveScene.current = this
        attached = true
        SpController.headProvider = { if (hasHead) pose else null }
        SpController.aimProvider = { aimHitNow() }
        SpController.quitHook = { releaseTrackers() }
        bindPanels(attachments)
        buildJob = scope?.launch { build(cr) }
        SpLog.i("FRAME scene attached root offset=${SpLog.pos(offset.domain())}")
    }

    private suspend fun build(root: Entity) {
        delay(150)
        val t0 = SystemClock.elapsedRealtime()
        val sh = SharedResources()
        shared = sh
        val m = SpMaterials(sh)
        materials = m
        val ms = SpMeshes(sh)
        for (step in ms.steps) {
            step()
            delay(16)
        }
        meshes = ms
        reticle = ReticleView(root, ms, m, work)
        roomLines = LinePool(root, ms.band, m.roomLine, "roomLine")
        built = true
        SpLog.i("FRAME scene built in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        controllers.stop()
        SpLog.i("FRAME scene paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        controllers.start()
        lastFrameMs = 0L
        SpLog.i("FRAME scene resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val p = plate ?: return
        if (!p.bind(attachments, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    private fun releaseTrackers() {
        head.stop()
        controllers.stop()
    }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.current === this) ActiveScene.current = null
        SpController.headProvider = null
        SpController.aimProvider = null
        SpController.quitHook = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        releaseTrackers()
        StageUi.plateShown = false
        runCatching { plate?.release() }
        layers.values.forEach { it.destroy() }
        layers.clear()
        windowViews.values.forEach { it.destroy() }
        windowViews.clear()
        markerViews.values.forEach { it.destroy() }
        markerViews.clear()
        runCatching { reticle?.destroy() }
        runCatching { roomLines?.destroy() }
        runCatching { catcher?.destroy() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        work.clear()
        shared?.close()
        shared = null
        stageRoot = null
        contentRoot = null
        plate = null
        built = false
        renderedRoom = -1
        renderedWindows = null
        renderedLight = null
        markerKeys.clear()
        SpLog.i("FRAME scene detached")
    }

    // =========================================================================================== per frame

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val slow = lastFrameMs > 0 && now - lastFrameMs > 40
        lastFrameMs = now
        frames++
        head.pose()?.let {
            // all zeros + identity is "not tracking yet" (hand-sign teacher lesson)
            val zero = it.position.length < 1e-3f && abs(it.rotation.w) > 0.99999f
            if (!zero) {
                pose = it
                hasHead = true
            }
        }
        val pitch = if (hasHead) debugPitch ?: pitchDeg(pose) else null
        plate?.onFrame(now)
        // D9: the main window opens only while the head is level (native-window-move §7.9); q1-3: and not in front of a
        // marked window (PanelCone)
        val windows = SpController.state.value.windows.map { it.corners }
        val yaw = headYaw(pose)
        WindowCtl.stepGate(now, pitch, if (hasHead) PanelCone.turnFor(pose.position, yaw, windows) else 0f, if (hasHead) pose.position else null, yaw)
        stepPanelAside(windows)
        if (!attached || paused) return
        try {
            val s = SpController.state.value
            SpController.onLost(hasHead && head.lost(now))
            stepHeadSample(s, now, pitch)
            if (controllers.takeBackPresses() > 0 && s.marking != null) {
                SpLog.i("TAP back key -> cancel marking")
                SpController.onEvent(Ev.CornerCancel)
            }
            if (built) {
                stepAim(s, now)
                stepDrag(s, now)
                render(s, now)
            }
            stepPlate(s, now)
            work.run(if (slow) 2 else 4)
            catcher?.set((s.marking != null || s.placing) && built, if (hasHead) pose.position else Vec3.ZERO)
        } catch (t: Throwable) {
            if (now - lastLogMs > 2000) SpLog.e("FRAME step failed: $t", t)
        }
        if (now - lastLogMs > LOG_EVERY_MS) {
            lastLogMs = now
            SpLog.i(SpLog.f("FRAME n=%d head=%s yaw=%.0f pitch=%.0f plate=%s window=%s ray=%s aim=%s work=%d", frames, SpLog.pos(pose.position), headYaw(pose), pitch ?: Float.NaN, plateMode, WindowCtl.panelState, aim?.let { SpLog.pos(it.dir) }, aimHit?.let { "${it.role}@${SpLog.pos(it.point)}" }, work.pending))
        }
    }

    /**
     * q1-3: a window added or moved while the panel is open (e.g. [放一扇标准窗] on the wall behind it) would sit behind
     * the panel; then the panel is recalled once, and the gate opens it again beside the window.
     */
    private fun stepPanelAside(windows: List<List<Vec3>>) {
        val sig = windows.hashCode()
        if (sig == asideSig) return
        val grew = windows.size >= asideCount
        asideSig = sig
        asideCount = windows.size
        if (!grew || !WindowCtl.open || SpController.state.value.marking != null) return
        val head = WindowCtl.openedHead ?: return
        val yaw = WindowCtl.openedYaw ?: return
        val turn = PanelCone.turnFor(head, yaw, windows) ?: return
        if (turn == 0f) return
        SpLog.i(SpLog.f("WINDOW aside: a window is behind the panel (opened at yaw %.0f, turn %.0f) -> recall", yaw, turn))
        WindowCtl.recall("aside")
    }

    private var asideSig = 0
    private var asideCount = 0

    private fun pitchDeg(p: HeadPose): Float = Math.toDegrees(asin(p.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()

    private fun headYaw(p: HeadPose): Float = Compass.yawOf(Vec3(p.forward.x, 0f, p.forward.z))

    // ---------------------------------------------------------------------------------- head sample (D29)

    private fun stepHeadSample(s: SpUiState, now: Long, pitch: Float?) {
        val target = s.headSample
        if (target == null) {
            if (sampling != null) sampler.cancel()
            sampling = null
            return
        }
        if (sampling != target) {
            sampling = target
            sampler.start(now)
            SpLog.i("ROOM headSample start target=$target")
        }
        val r = sampler.step(now, if (hasHead) pose.position else null, if (hasHead) headYaw(pose) else null, pitch, debugYawRate) ?: return
        sampling = null
        SpController.onHeadSample(target, r)
    }

    // ---------------------------------------------------------------------------------- aim (ray x room, analytic)

    private fun aimHitNow(): RoomHit? {
        val room = SpController.state.value.room ?: return null
        val r = controllers.ray() ?: return null
        return room.hit(r.origin, r.dir)
    }

    private fun stepAim(s: SpUiState, now: Long) {
        val ret = reticle ?: return
        val room = s.room
        val active = s.marking != null || s.placing
        aim = if (active || drag != null) controllers.ray() else null
        aimHit = if (room != null && aim != null && active) room.hit(aim!!.origin, aim!!.dir) else null
        if (!active || s.lost) {
            ret.hide()
            return
        }
        val h = aimHit
        val marking = s.marking
        if (marking != null) {
            ret.hideGhost()
            if (h == null) {
                ret.hide()
                return
            }
            val onWall = h.role == HitRole.WALL && (marking.wallId == null || marking.wallId == h.surface?.id)
            ret.crossAt(h.point, h.surface?.normal ?: (pose.position - h.point).normalized(), !onWall)
            var rect: List<Vec3>? = null
            var bad = false
            if (marking.first != null && h.role == HitRole.WALL && h.surface != null) {
                val wall = room?.surface(marking.wallId)
                if (wall != null && h.surface.id == wall.id) {
                    val sw = SunWindow.fromCorners("preview", wall.id, marking.first, h.point, -wall.normal)
                    rect = sw?.corners ?: previewRaw(marking.first, h.point, wall.normal)
                    bad = sw == null
                }
            }
            ret.previewRect(rect, h.surface?.normal ?: Vec3.UP, bad)
            if (onWall) {
                val r = res() ?: return
                val text = r.getString(if (marking.first == null) R.string.ret_top_left else R.string.ret_bottom_right)
                ret.word.content("w:$text") { Plates.text(listOf(text)) }
                ret.word.place(h.point + h.surface!!.normal * 0.01f + Vec3(0f, -0.07f, 0f), 0.025f)
                ret.word.face(h.point + h.surface.normal)
            } else ret.word.hide()
        } else {
            ret.hideCross()
            ret.previewRect(null, Vec3.UP, false)
            ret.word.hide()
            if (h == null) {
                ret.hideGhost()
                return
            }
            if (h.role == HitRole.FLOOR || h.role == HitRole.UP) {
                ret.ghostAt(h.point)
            } else {
                ret.hideGhost()
                ret.crossAt(h.point, h.surface?.normal ?: (pose.position - h.point).normalized(), true)
            }
        }
    }

    private fun previewRaw(a: Vec3, b: Vec3, n: Vec3): List<Vec3> {
        val right = (-n).cross(Vec3.UP).normalized()
        val la = a.dot(right)
        val lb = b.dot(right)
        val top = maxOf(a.y, b.y)
        val bottom = minOf(a.y, b.y)
        val base = a - right * la
        fun p(x: Float, y: Float) = Vec3(base.x + right.x * x, y, base.z + right.z * x)
        return listOf(p(minOf(la, lb), top), p(maxOf(la, lb), top), p(maxOf(la, lb), bottom), p(minOf(la, lb), bottom))
    }

    // ---------------------------------------------------------------------------------- render

    private fun res(): Resources? {
        val c = appContext ?: return null
        val lang = SpController.state.value.prefs.lang
        if (res == null || resLang != lang) {
            res = localizedResources(c, lang)
            resLang = lang
        }
        return res
    }

    private fun zh(): Boolean = res()?.configuration?.locales?.get(0)?.language == "zh"

    private fun render(s: SpUiState, now: Long) {
        val root = contentRoot ?: return
        val m = materials ?: return
        val ms = meshes ?: return
        root.on(!s.lost)
        val room = s.room
        // layers + room lines follow the room
        if (room != null && s.roomVersion != renderedRoom) {
            renderedRoom = s.roomVersion
            layers.values.forEach { it.destroy() }
            layers.clear()
            for (surf in room.surfaces) layers[surf.id] = LightLayerView(root, ms, m, work, surf)
            renderedLight = null
            drawRoomLines(s)
        }
        // windows
        val lang = s.prefs.lang
        if (s.windows !== renderedWindows || renderedLang != lang || s.light !== renderedLight) {
            val ids = s.windows.map { it.id }.toSet()
            windowViews.keys.filter { it !in ids }.forEach { windowViews.remove(it)?.destroy() }
            val r = res()
            for (w in s.windows) {
                val v = windowViews.getOrPut(w.id) { WindowView(root, ms, m, work, w.id) }
                if (s.windows !== renderedWindows) v.place(w.corners, w.outward)
                val lit = s.light?.frame?.litWindows?.contains(w.id) == true
                v.sun(w.center, if (lit) s.light?.frame?.sun else null)
                if (r != null) {
                    val zh = zh()
                    val size = String.format(Locale.US, "%.1f × %.1f m", w.width, w.height)
                    val line1 = if (s.project.northSet) {
                        val b = s.project.bearingOf(w)
                        "${Dirs.windowName(b, zh)} · $size · ${r.getString(R.string.faces_deg, Math.round(b))}"
                    } else "${r.getString(R.string.win_n, w.no)} · $size"
                    val sunUp = (s.light?.readout?.altDeg ?: 1.0) > 0.0
                    val line2 = if (!s.project.northSet || lit || s.light == null) null else r.getString(if (sunUp) R.string.plate_behind else R.string.plate_set)
                    val lines = listOfNotNull(line1, line2)
                    v.plate.content(lines.joinToString("|")) { Plates.text(lines) }
                }
            }
            renderedWindows = s.windows
            renderedLang = lang
        }
        // light layers: patch over the hours colours
        val light = s.light
        val trail = s.sweep?.trail
        val heatMap: HoursMap? = trail ?: if (s.heat) s.hours?.map else null
        val layerKey = Triple(light?.key, java.lang.System.identityHashCode(heatMap), s.prefs.brightness)
        if (layerKey != renderedLight && room != null) {
            for (surf in room.surfaces) {
                val v = layers[surf.id] ?: continue
                val raster = light?.rasters?.get(surf.id)
                val heat = if (surf.role == SurfaceRole.WALL) null else heatMap?.layers?.get(surf.id)
                v.show(Triple(light?.key, java.lang.System.identityHashCode(heat), s.prefs.brightness), raster, heat, s.prefs.brightness.alpha)
            }
            renderedLight = layerKey
        }
        // markers
        val ids = s.markers.map { it.id }.toSet()
        markerViews.keys.filter { it !in ids }.forEach { id -> markerViews.remove(id)?.destroy(); markerKeys.remove(id) }
        val r = res()
        for (mk in s.markers) {
            val v = markerViews.getOrPut(mk.id) { MarkerView(root, ms, m, work, mk.id) }
            val d = drag
            val p = if (d != null && d.id == mk.id) d.live else mk.pos
            val verdict = s.verdicts[mk.id]
            val sel = s.selected == mk.id
            val key = Triple(p, verdict, sel)
            if (sel || markerKeys[mk.id] != key) {
                v.place(p, verdict, sel, now)
                markerKeys[mk.id] = key
            }
            if (r != null) {
                val zh = zh()
                val kind = if (zh) mk.kind.zh else mk.kind.en
                val want = r.getString(wantRes(mk.want))
                val st = s.hours?.stats?.get(mk.id)
                val sweep = s.sweep
                val line2 = when {
                    sweep != null -> r.getString(R.string.so_far, String.format(Locale.US, "%.1f", sweep.soFar[mk.id] ?: 0f))
                    st == null -> r.getString(R.string.computing)
                    st.hours > 0f -> r.getString(R.string.today_line, String.format(Locale.US, "%.1f", st.hours), Solar.fmt(st.first?.toDouble()), Solar.fmt(st.last?.toDouble()))
                    else -> r.getString(R.string.no_sun_today)
                }
                val inPatch = (s.light?.conclusion as? io.embodit.sunpatch.ui.Conclusion.Lands)?.markers?.contains(mk.id) == true
                val lines = listOf("$kind · $want", line2)
                v.plate.content(lines.joinToString("|") + inPatch) { Plates.text(lines, if (inPatch) SpColors.PatchRim.toArgb() else null) }
            }
        }
        // plates face the viewer (yaw only, D10); wall plates face straight out of the wall
        if (frames % 3 == 0L) {
            for ((_, v) in windowViews) v.plate.face(v.plateViewer)
            for ((_, v) in markerViews) v.plate.face(pose.position)
        }
    }

    private fun wantRes(w: io.embodit.sunpatch.domain.Want): Int = when (w) {
        io.embodit.sunpatch.domain.Want.MORE -> R.string.want_more
        io.embodit.sunpatch.domain.Want.LESS -> R.string.want_less
        io.embodit.sunpatch.domain.Want.LOOK -> R.string.want_look
    }

    /** Virtual room edges (design `entities3d.virtualRoom`): floor outline, wall corners, table outline, 1 cm. */
    private fun drawRoomLines(s: SpUiState) {
        val pool = roomLines ?: return
        pool.begin()
        val room = s.room
        val v = room?.virtual
        if (room != null && v != null && room.kind == RoomKind.VIRTUAL) {
            val a = v.ahead * (v.depth / 2f)
            val rr = v.ahead.right * (v.width / 2f)
            val c = listOf(v.center - a - rr, v.center - a + rr, v.center + a + rr, v.center + a - rr)
            val y0 = v.floorY + 0.003f
            for (i in 0 until 4) {
                val p = c[i]
                val q = c[(i + 1) % 4]
                pool.draw(p.at(y0), q.at(y0), Dims.ROOM_LINE_W, Vec3.UP)
                val inward = (v.center - p).normalized().at(0f)
                pool.draw(p.at(v.floorY), p.at(v.ceilingY), Dims.ROOM_LINE_W, inward)
            }
            room.surfaces.filter { it.role == SurfaceRole.UP }.forEach { t ->
                val poly = t.polygon
                for (i in poly.indices) pool.draw(poly[i] + Vec3(0f, 0.004f, 0f), poly[(i + 1) % poly.size] + Vec3(0f, 0.004f, 0f), Dims.ROOM_LINE_W, Vec3.UP)
            }
        }
        pool.end()
    }

    // ---------------------------------------------------------------------------------- stage plate (R1-3, R2-1)

    private fun stepPlate(s: SpUiState, now: Long) {
        val slot = plate ?: return
        val mode = when {
            !hasHead -> PlateMode.NONE
            !built -> PlateMode.LOADING
            s.marking != null -> PlateMode.MARKING
            WindowCtl.levelHint -> PlateMode.LEVEL
            WindowCtl.asideTurn != 0 -> PlateMode.ASIDE
            s.headSample != null -> PlateMode.LOOK_AHEAD
            s.lost -> PlateMode.LOST
            s.panelBack && !WindowCtl.open -> PlateMode.BACK
            WindowCtl.chipWanted(now) -> PlateMode.OPEN
            else -> PlateMode.NONE
        }
        if (mode != plateMode) {
            SpLog.i("GATE plate mode=$mode (was $plateMode) window=${WindowCtl.panelState}")
            val keep = (plateMode == PlateMode.MARKING && mode == PlateMode.BACK)
            plateMode = mode
            StageUi.mode = mode
            if (!keep) platePos = null
        }
        val yaw = headYaw(pose)
        when (mode) {
            PlateMode.NONE -> {
                slot.hide()
                return
            }
            PlateMode.MARKING, PlateMode.BACK -> {
                // locked in front, 20 deg under the line of sight; re-posed once when the head turned > 45 deg for 0.5 s
                val off = abs(wrapDeg(yaw - plateBearing))
                if (platePos == null || (off > 45f && plateOffSince >= 0 && now - plateOffSince >= 500)) {
                    plateBearing = yaw
                    platePos = ahead(yaw, -20f, 0.75f)
                    plateOffSince = -1L
                    SpLog.i("GATE plate placed mode=$mode at=${SpLog.pos(platePos)} yaw=${"%.0f".format(Locale.US, yaw)}")
                }
                if (off > 45f) {
                    if (plateOffSince < 0) plateOffSince = now
                } else plateOffSince = -1L
            }
            PlateMode.OPEN, PlateMode.LOADING -> {
                if (platePos == null) {
                    plateBearing = yaw
                    platePos = ahead(yaw, -12f, 0.9f)
                }
            }
            PlateMode.ASIDE -> {
                // under the line of sight and 25 deg toward the side to turn to, so the plate itself leaves the window clear
                val p = (debugPitch ?: pitchDeg(pose)).coerceIn(-40f, 40f) - 22f
                platePos = ahead(yaw + 25f * WindowCtl.asideTurn, p, 0.75f)
            }
            PlateMode.LEVEL, PlateMode.LOOK_AHEAD, PlateMode.LOST -> {
                // on the line of sight (pitch clamped to +-40 deg) so a lowered head still sees it; upright
                val p = (debugPitch ?: pitchDeg(pose)).coerceIn(-40f, 40f)
                platePos = ahead(yaw, p, 0.75f)
            }
        }
        slot.show(platePos!!, pose.position)
    }

    private fun ahead(yawDeg: Float, pitchDeg: Float, dist: Float): Vec3 {
        val d = Compass.dirOfYaw(yawDeg)
        val pr = Math.toRadians(pitchDeg.toDouble())
        return pose.position + d * (dist * cos(pr).toFloat()) + Vec3(0f, (dist * sin(pr)).toFloat(), 0f)
    }

    /** A press on the Stage plate: the trigger that pressed it must not also count as a corner / marker tap. */
    fun onPlatePointer() {
        lastPlatePressMs = SystemClock.elapsedRealtime()
    }

    // ---------------------------------------------------------------------------------- gestures

    private fun markerOf(e: Entity?): String? {
        e ?: return null
        for ((id, v) in markerViews) if (v.owns(e)) return id
        return null
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        if (lastPlatePressMs > 0 && now - lastPlatePressMs < 400) return SpLog.i("TAP ignored reason=plate target=$name")
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        val markerId = markerOf(target)
        val s = SpController.state.value
        // the controller ray when there is one (D1); else the gaze: from the eyes through the pinched point
        val ray = controllers.ray() ?: stage?.let { p -> Ray(pose.position, (p - pose.position).normalized()) }
        val via = if (controllers.ray() != null) "ray" else "gaze"
        SpLog.i("TAP target=$name marker=$markerId via=$via stage=${SpLog.pos(stage)}")
        tap(s, ray, markerId)
    }

    private fun tap(s: SpUiState, ray: Ray?, markerId: String?) {
        val room = s.room
        val hit = if (ray != null && room != null) room.hit(ray.origin, ray.dir) else null
        when {
            s.marking != null -> SpController.onCornerTap(hit)
            s.placing -> if (markerId != null) SpController.onMarkerPicked(markerId) else SpController.onPlaceTap(hit)
            markerId != null -> SpController.onMarkerPicked(markerId)
        }
    }

    /** Debug `trigger`: the tap a trigger press would deliver along the current (stand-in) ray. */
    fun debugTrigger(): String {
        val ray = controllers.ray() ?: return "no ray (use: ray x,y,z)"
        val s = SpController.state.value
        val room = s.room
        // a marker in the way is what the system tap would have hit first
        val mk = s.markers.filter { m ->
            val t = (m.pos - ray.origin).dot(ray.dir)
            t > 0 && (ray.origin + ray.dir * t).distanceTo(m.pos + Vec3(0f, 0.03f, 0f)) < 0.06f
        }.minByOrNull { (it.pos - ray.origin).length }
        val hit = room?.hit(ray.origin, ray.dir)
        val roomFirst = hit != null && mk != null && hit.distance < (mk.pos - ray.origin).length - 0.05f
        SpLog.i("TAP target=debug-trigger marker=${mk?.id} via=ray hit=${hit?.role}@${SpLog.pos(hit?.point)}")
        tap(s, ray, if (roomFirst) null else mk?.id)
        return "trigger hit=${hit?.role} at ${SpLog.pos(hit?.point)} marker=${mk?.no}"
    }

    /** Debug `ray x,y,z[,ms]`: a stand-in controller ray from the head to that Stage point. */
    fun debugRay(at: Vec3, ms: Long): String {
        val o = pose.position
        controllers.debugAim(o, at - o, ms)
        return "ray from=${SpLog.pos(o)} to=${SpLog.pos(at)} ms=$ms"
    }

    fun headPosition(): Vec3? = if (hasHead) pose.position else null

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val s = SpController.state.value
        when (phase) {
            "start" -> {
                val id = markerOf(value.targetEntity) ?: return
                if (s.marking != null || s.placing) return
                beginDrag(id, start)
            }
            "move" -> {
                val d = drag ?: return
                if (d.debug) return
                val a = value.dragAmount
                d.total = Vec3(d.total.x + a.x, d.total.y + a.y, d.total.z + a.z)
                if (controllers.ray() != null) return // the frame follows the ray
                val sp = d.startPx ?: return
                val p0 = toStage(sp.x, sp.y, sp.z) ?: return
                val p1 = toStage(sp.x + d.total.x, sp.y + d.total.y, sp.z + d.total.z) ?: return
                val hand = p1 - p0
                setLive(d, Vec3(d.start.x + hand.x * HAND_GAIN, d.levelY, d.start.z + hand.z * HAND_GAIN))
            }
            "end" -> endDrag(false)
            "cancel" -> endDrag(true)
        }
    }

    private fun beginDrag(id: String, startPx: Offset3D?): Drag? {
        val mk = SpController.state.value.marker(id) ?: return null
        val d = Drag(id, mk.pos, mk.pos.y, startPx)
        drag = d
        SpController.onDragStart(id)
        SpLog.i("DRAG start id=$id at=${SpLog.pos(mk.pos)}")
        return d
    }

    private fun setLive(d: Drag, p: Vec3) {
        d.live = p
        val now = SystemClock.elapsedRealtime()
        if (now - d.lastLogMs > 500) {
            d.lastLogMs = now
            SpLog.i("DRAG move id=${d.id} live=${SpLog.pos(p)}")
        }
    }

    /** With a controller the ring follows ray x the level it was picked up at (light-planner pattern). */
    private fun stepDrag(s: SpUiState, now: Long) {
        val d = drag ?: return
        if (s.dragging == null && !d.debug) {
            drag = null
            return
        }
        if (d.debug) return
        val r = aim ?: return
        if (r.dir.y > -0.02f) return
        val t = (d.levelY - r.origin.y) / r.dir.y
        if (t <= 0f || t > 15f) return
        setLive(d, r.origin + r.dir * t)
    }

    private fun endDrag(cancelled: Boolean) {
        val d = drag ?: return
        drag = null
        if (cancelled) {
            SpController.onDragEnd(d.id, null)
            return
        }
        val room = SpController.state.value.room
        // let go: it lands on the highest floor / upward face under it (it can come off a table onto the floor)
        val under = room?.surfaceUnder(Vec3(d.live.x, room.floorY + 3f, d.live.z), room.floorY + 3f)
        val y = under?.height ?: d.levelY
        val p = Vec3(d.live.x, y, d.live.z)
        SpLog.i("DRAG end id=${d.id} at=${SpLog.pos(p)} on=${under?.role}")
        SpController.onDragEnd(d.id, p)
    }

    /** Debug `drag n dx dz [hold]`: the same start / move / end path, moved by (dx, dz) metres. */
    fun debugDrag(id: String, dx: Float, dz: Float, hold: Boolean): String {
        val d = beginDrag(id, null) ?: return "no marker"
        d.debug = true
        setLive(d, Vec3(d.start.x + dx, d.levelY, d.start.z + dz))
        if (!hold) endDrag(false)
        return "drag id=$id by ($dx, $dz) hold=$hold"
    }

    fun debugRelease(): String {
        if (drag == null) return "no drag"
        endDrag(false)
        return "released"
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val root = stageRoot ?: return null
        return runCatching {
            val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            val world = contentProbe?.convertPositionTo(local, null) ?: return null
            root.convertPositionFrom(world, null).domain()
        }.getOrNull()
    }

    fun where(): String =
        SpLog.f("head=%s yaw=%.1f pitch=%.1f hasHead=%s plate=%s platePos=%s ray=%s aim=%s drag=%s", SpLog.pos(pose.position), headYaw(pose), pitchDeg(pose), hasHead, plateMode, SpLog.pos(platePos), controllers.status(), aimHit?.let { "${it.role}@${SpLog.pos(it.point)}" }, drag?.id)

    companion object {
        const val PLATE_ID = "stagePlate"
        private const val LOG_EVERY_MS = 10_000L
        private const val HAND_GAIN = 3f

        @Suppress("unused")
        private val UNUSED_CELL = SunLight.LAYER_CELL_M
    }
}
