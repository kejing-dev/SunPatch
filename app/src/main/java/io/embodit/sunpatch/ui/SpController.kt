package io.embodit.sunpatch.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.sunpatch.data.AnchorOutcome
import io.embodit.sunpatch.data.AnchorRecord
import io.embodit.sunpatch.domain.AnchorPose
import io.embodit.sunpatch.domain.Cities
import io.embodit.sunpatch.domain.Compass
import io.embodit.sunpatch.domain.HeadPose
import io.embodit.sunpatch.domain.HeadSampler
import io.embodit.sunpatch.domain.HitRole
import io.embodit.sunpatch.domain.HoursMap
import io.embodit.sunpatch.domain.MarkerKind
import io.embodit.sunpatch.domain.MarkerRec
import io.embodit.sunpatch.domain.MarkerStats
import io.embodit.sunpatch.domain.NextEntry
import io.embodit.sunpatch.domain.P2
import io.embodit.sunpatch.domain.Quat4
import io.embodit.sunpatch.domain.Room
import io.embodit.sunpatch.domain.RoomHit
import io.embodit.sunpatch.domain.RoomKind
import io.embodit.sunpatch.domain.Solar
import io.embodit.sunpatch.domain.SunLight
import io.embodit.sunpatch.domain.SunProject
import io.embodit.sunpatch.domain.SunSetup
import io.embodit.sunpatch.domain.SunWindow
import io.embodit.sunpatch.domain.SurfaceRole
import io.embodit.sunpatch.domain.SweepClock
import io.embodit.sunpatch.domain.Upright
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.domain.Verdict
import io.embodit.sunpatch.domain.Verdicts
import io.embodit.sunpatch.domain.VirtualRoom
import io.embodit.sunpatch.domain.WallSlide
import io.embodit.sunpatch.domain.Want
import io.embodit.sunpatch.domain.WindowRec
import io.embodit.sunpatch.platform.Prefs
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.platform.Sound
import io.embodit.sunpatch.platform.Store
import io.embodit.sunpatch.platform.anchor.WorldAnchorGateway
import io.embodit.sunpatch.platform.sense.PlaneTracker
import io.embodit.sunpatch.ui.window.WindowCtl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.system.exitProcess
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The process-level store and state machine (native-window-move §3.4: the Stage and the main window read the same
 * [state]). Panel controls, scene callbacks and the debug receiver all come in through [onEvent] / the `on*` callbacks.
 * The sun, the patches, the hours map and the marker stats are computed on two worker threads ([requestLight] <= 10 Hz,
 * [requestHours] debounced); the sweep runs its own loop. Main thread only.
 */
object SpController {
    private val _state = MutableStateFlow(SpUiState())
    val state: StateFlow<SpUiState> = _state

    var activity: Activity? = null
    private var appContext: Context? = null
    private var store: Store? = null
    val sound = Sound()
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val lightThread = Executors.newSingleThreadExecutor { r -> Thread(r, "sun-light") }.asCoroutineDispatcher()
    private val hoursThread = Executors.newSingleThreadExecutor { r -> Thread(r, "sun-hours") }.asCoroutineDispatcher()
    private val sweepThread = Executors.newSingleThreadExecutor { r -> Thread(r, "sun-sweep") }.asCoroutineDispatcher()
    private var started = false

    /** The scene's head pose (only for the virtual room's place, D9; never for aiming). */
    var headProvider: (() -> HeadPose?)? = null

    /** The scene's current ray x room (for "drop a standard window" on the wall you point at). */
    var aimProvider: (() -> RoomHit?)? = null

    /** The scene releases its trackers before the process ends. */
    var quitHook: (() -> Unit)? = null

    // ---------------------------------------------------------------------------------------- debug seams
    /** Debug `clock`: a fixed "now" (null = the real clock). */
    @Volatile var debugNow: Instant? = null

    /** Debug `bar long`: inline bars stay 60 s. */
    var barLong = false

    private var vroom: VirtualRoom? = null
    private var setupVersion = 0
    private var findStartMs = 0L
    private var lastPlanesMs = 0L
    private var lastStateLine = ""
    private var lastTrailCells4 = -1

    fun nowInstant(): Instant = debugNow ?: Instant.now()

    private fun zone(): ZoneId = runCatching { ZoneId.of(_state.value.project.city.zoneId) }.getOrDefault(ZoneId.systemDefault())

    private fun nowLocal(): ZonedDateTime = nowInstant().atZone(zone())

    // ============================================================================================ start

    fun start(context: Context) {
        activity = findActivity(context) ?: activity
        if (started) return
        started = true
        appContext = context.applicationContext
        val st = Store(context.applicationContext)
        store = st
        val prefs = st.prefs()
        val project = st.loadProject() ?: SunProject()
        sound.enabled = prefs.sound
        sound.start()
        anchors.start()
        val local = Instant.now().atZone(runCatching { ZoneId.of(project.city.zoneId) }.getOrDefault(ZoneId.systemDefault()))
        val hasItems = project.windows.isNotEmpty() || project.markers.isNotEmpty()
        _state.value = SpUiState(
            started = true,
            loading = false,
            prefs = prefs,
            project = project,
            welcome = !prefs.firstRunDone && !hasItems,
            date = local.toLocalDate(),
            minute = roundMinute(local.hour * 60 + local.minute),
            isToday = true,
            restoring = hasItems,
            page = Page.WINDOW,
        )
        SpLog.i("CONTROLLER start prefs=$prefs windows=${project.windows.size} markers=${project.markers.size} city=${project.city.key}")
        planes.start { onPlanesChanged() }
        startFinding("start")
        if (hasItems) scope.launch { restore(4000L) }
        main.postDelayed(ticker, 1000L)
        logState("start")
    }

    private fun findActivity(c: Context): Activity? {
        var x: Context? = c
        while (x != null) {
            if (x is Activity) return x
            x = (x as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun set(why: String, f: (SpUiState) -> SpUiState) {
        val before = _state.value
        val after = f(before)
        if (after == before) return
        _state.value = after
        if (after.project != before.project) scheduleSave()
        logState(why)
    }

    private fun logState(why: String) {
        val line = _state.value.stateLine()
        if (line != lastStateLine) {
            lastStateLine = line
            SpLog.i("$line why=$why")
        }
    }

    // ============================================================================================ events

    fun onEvent(e: Ev) {
        val s = _state.value
        when (e) {
            // QA #1 q1-1: marker placing belongs to the 日照图 page; leaving it ends placing (it used to follow into settings)
            is Ev.Tab -> set("tab ${e.page.id}") { it.copy(page = e.page, confirmExit = false, confirmReset = false, confirmDelete = false, cityOpen = false, vroomOpen = if (e.page == Page.SETTINGS) it.vroomOpen else false, placing = it.placing && e.page == Page.HOURS) }
            Ev.WelcomeOk -> {
                savePrefs(s.prefs.copy(firstRunDone = true))
                set("welcome ok") { it.copy(welcome = false) }
            }
            Ev.MarkWindow -> startMarking(null)
            is Ev.RedoWindow -> startMarking(e.id)
            is Ev.DeleteWindow -> deleteWindow(e.id)
            Ev.StdWindow -> stdWindow()
            Ev.CornerRedo -> set("corner redo") { it.copy(marking = it.marking?.copy(first = null, wallId = null, reject = null)) }
            Ev.CornerCancel -> endMarking("cancel")
            is Ev.SetFacing -> setFacing(e.deg)
            is Ev.NudgeFacing -> setFacing((s.project.facingDeg() ?: 180f) + e.delta)
            Ev.ToggleCity -> set("city toggle") { it.copy(cityOpen = !it.cityOpen, cityQuery = "", coordsOpen = false) }
            is Ev.CityQuery -> set("city query") { it.copy(cityQuery = e.q) }
            is Ev.PickCity -> {
                val c = Cities.byKey(e.key) ?: return SpLog.w("CMD city: unknown ${e.key}")
                setProject("city ${c.key}") { it.copy(city = c) }
                set("city picked") { it.copy(cityOpen = false) }
                SpLog.i("SUN city=${c.key} ${c.coords} zone=${c.zoneId}")
            }
            Ev.ToggleCoords -> set("coords") { it.copy(coordsOpen = !it.coordsOpen) }
            is Ev.NudgeCoords -> {
                val c = s.project.city
                val lat = ((c.lat + e.dLat) * 10).let { Math.round(it) / 10.0 }.coerceIn(-66.0, 66.0)
                val lon = ((c.lon + e.dLon) * 10).let { Math.round(it) / 10.0 }.let { if (it > 180) it - 360 else if (it < -180) it + 360 else it }
                setProject("coords") { it.copy(city = Cities.custom(lat, lon, ZoneId.systemDefault().id)) }
            }
            Ev.GoAlign -> set("align") { it.copy(page = Page.SETTINGS, vroomOpen = true) }
            Ev.Grant -> {
                SpLog.i("ROOM permission granted (debug path)")
                set("grant") { it.copy(noPermission = false) }
                startFinding("grant")
            }
            Ev.LookAgain -> scope.launch { restore(10_000L) }

            is Ev.Minute -> {
                stopSweepForSlider()
                set("minute") { it.copy(minute = e.minute.coerceIn(0, Solar.DAY_MIN).let(::roundMinute)) }
                requestLight()
            }
            is Ev.Month -> {
                stopSweepForSlider()
                val m = e.month.coerceIn(1, 12)
                if (s.date.monthValue != m || s.isToday) {
                    set("month") { it.copy(date = LocalDate.of(it.date.year, m, 15), isToday = false) }
                    requestLight()
                    requestHours()
                }
            }
            Ev.Today -> {
                val n = nowLocal()
                set("today") { it.copy(date = n.toLocalDate(), isToday = true, minute = roundMinute(n.hour * 60 + n.minute)) }
                requestLight()
                requestHours()
            }
            Ev.Now -> {
                val n = nowLocal()
                set("now") { it.copy(minute = roundMinute(n.hour * 60 + n.minute)) }
                requestLight()
            }
            is Ev.Follow -> {
                savePrefs(s.prefs.copy(followNow = e.on))
                if (e.on) onEvent(Ev.Today)
            }
            is Ev.SweepStart -> startSweep(e.holdMs)
            Ev.SweepStop -> stopSweep("button")

            is Ev.Heat -> set("heat ${e.on}") { it.copy(heat = e.on, sweep = if (it.sweep?.running == true) it.sweep else null) }.also { if (e.on) requestHours() }
            Ev.PlaceMarker -> {
                if (s.markers.size >= SunProject.MAX_MARKERS) {
                    SpLog.i("MARK refused reason=max")
                    showBar(BarKind.MAX_MARKERS)
                } else set("place") { it.copy(placing = true, page = Page.HOURS, selected = null, confirmDelete = false) }
            }
            Ev.PlaceCancel -> set("place cancel") { it.copy(placing = false) }
            is Ev.SelectMarker -> set("select ${e.id}") { it.copy(selected = e.id, page = if (e.id != null) Page.HOURS else it.page, confirmDelete = false, placing = false) }
            is Ev.MarkerKindSet -> updateMarker(e.id, "kind ${e.kind}") { it.copy(kind = e.kind, want = e.kind.want) }
            is Ev.MarkerWant -> updateMarker(e.id, "want ${e.want}") { it.copy(want = e.want) }
            is Ev.MarkerFront -> set("marker front") { it.copy(headSample = HeadTarget.Marker(e.id)) }
            is Ev.MarkerDeleteAsk -> set("delete ask") { it.copy(confirmDelete = true, selected = e.id) }
            Ev.MarkerDeleteCancel -> set("delete cancel") { it.copy(confirmDelete = false) }
            is Ev.MarkerDelete -> deleteMarker(e.id)

            is Ev.SetLang -> savePrefs(s.prefs.copy(lang = e.lang))
            is Ev.SetBrightness -> savePrefs(s.prefs.copy(brightness = e.b))
            is Ev.SetSound -> {
                sound.enabled = e.on
                savePrefs(s.prefs.copy(sound = e.on))
            }
            Ev.Recall -> WindowCtl.recall("settings")
            Ev.UseVirtual -> {
                SpLog.i("ROOM virtual reason=user")
                main.removeCallbacks(findCheck)
                set("use virtual") { it.copy(useVirtual = true, finding = false) }
                rebuildRoom("use virtual")
            }
            Ev.BackToScan -> {
                if (s.noPermission) return
                set("back to scan") { it.copy(useVirtual = false) }
                startFinding("scan")
            }
            is Ev.RoomSize -> {
                val p = s.prefs
                val v = (vroom ?: return).resized(p.roomW + e.dw, p.roomD + e.dd, p.roomH + e.dh)
                vroom = v
                savePrefs(p.copy(roomW = v.width, roomD = v.depth, roomH = v.height))
                SpLog.i(SpLog.f("ROOM size %.1f x %.1f x %.1f", v.width, v.depth, v.height))
                rebuildRoom("size")
            }
            is Ev.RoomTurn -> {
                val v = vroom ?: return
                vroom = v.copy(ahead = v.ahead.turned(e.deg).normalized())
                SpLog.i("ROOM turn ${e.deg}")
                rebuildRoom("turn")
            }
            Ev.RoomRecenter -> set("recenter ask") { it.copy(headSample = HeadTarget.Room) }
            Ev.ResetAsk -> set("reset ask") { it.copy(confirmReset = true) }
            Ev.ResetCancel -> set("reset cancel") { it.copy(confirmReset = false) }
            Ev.ResetYes -> resetAll()
            Ev.QuitAsk -> set("quit ask") { it.copy(confirmExit = true) }
            Ev.QuitCancel -> set("quit cancel") { it.copy(confirmExit = false) }
            Ev.QuitYes -> quit()
            is Ev.Background -> background(e.on)
        }
    }

    private fun savePrefs(p: Prefs) {
        store?.savePrefs(p)
        set("prefs") { it.copy(prefs = p) }
    }

    private fun setProject(why: String, f: (SunProject) -> SunProject) {
        val before = _state.value.project
        val after = f(before)
        if (after == before) return
        set(why) { it.copy(project = after) }
        setupVersion++
        requestLight()
        requestHours()
    }

    // ============================================================================================ room

    private fun startFinding(why: String) {
        findStartMs = SystemClock.elapsedRealtime()
        set("finding $why") { it.copy(finding = true) }
        main.removeCallbacks(findCheck)
        main.postDelayed(findCheck, 300)
        SpLog.i("ROOM finding why=$why")
    }

    private val findCheck: Runnable =
        object : Runnable {
            override fun run() {
                val s = _state.value
                if (!s.finding) return
                val walls = planes.all.count { it.semantic == "WALL" && it.isVertical }
                val floor = planes.all.any { it.semantic == "FLOOR" }
                if (walls != s.wallsSeen || floor != s.floorSeen) set("planes seen") { it.copy(wallsSeen = walls, floorSeen = floor) }
                val head = headProvider?.invoke()
                val elapsed = SystemClock.elapsedRealtime() - findStartMs
                if (s.noPermission && head != null) {
                    set("noPermission virtual") { it.copy(finding = false, useVirtual = true) }
                    rebuildRoom("noPermission")
                    return
                }
                if (walls > 0 && head != null) {
                    SpLog.i("ROOM real walls=$walls after ${elapsed} ms")
                    set("found") { it.copy(finding = false, useVirtual = false) }
                    rebuildRoom("scan")
                    return
                }
                if (elapsed >= FIND_TIMEOUT_MS && head != null) {
                    SpLog.i("ROOM virtual reason=timeout after ${elapsed} ms planes=${planes.all.size}")
                    set("timeout") { it.copy(finding = false, useVirtual = true) }
                    rebuildRoom("timeout")
                    return
                }
                main.postDelayed(this, 300)
            }
        }

    private fun onPlanesChanged() {
        val walls = planes.all.count { it.semantic == "WALL" && it.isVertical }
        val floor = planes.all.any { it.semantic == "FLOOR" }
        if (walls != _state.value.wallsSeen || floor != _state.value.floorSeen) set("planes seen") { it.copy(wallsSeen = walls, floorSeen = floor) }
        val s = _state.value
        if (s.finding || s.useVirtual) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlanesMs < PLANES_EVERY_MS) {
            main.removeCallbacks(planesLater)
            main.postDelayed(planesLater, PLANES_EVERY_MS - (now - lastPlanesMs))
            return
        }
        lastPlanesMs = now
        rebuildRoom("planes")
    }

    private val planesLater = Runnable { onPlanesChanged() }

    private fun realFloorY(): Float? = planes.all.filter { it.semantic == "FLOOR" && it.isHorizontal }.maxByOrNull { it.area }?.centroid?.y

    /** Builds the room from the scan and the virtual box; windows on a virtual wall that moved go with it. */
    fun rebuildRoom(why: String) {
        val s = _state.value
        val head = headProvider?.invoke()
        if (vroom == null) {
            head ?: return SpLog.w("ROOM rebuild why=$why: no head pose yet")
            vroom = VirtualRoom.around(head, realFloorY(), VirtualRoom(P2.ZERO, P2(0f, -1f), 0f, s.prefs.roomW, s.prefs.roomD, s.prefs.roomH))
        }
        val v = vroom!!
        val inside = v.center.at(v.floorY + 1.2f)
        val room = Room.build(planes.all, v, s.useVirtual || s.noPermission, inside) ?: return SpLog.w("ROOM rebuild why=$why failed")
        val old = s.room
        var windows = s.project.windows
        if (old != null) {
            windows = windows.map { w ->
                val id = w.wallId ?: return@map w
                if (!VirtualRoom.isVirtual(id)) return@map w
                val ow = old.surface(id) ?: return@map w
                val nw = room.surface(id) ?: return@map w
                if (ow.polygon == nw.polygon) return@map w
                val moved = WallSlide.follow(w.sun(), ow, nw)
                SpLog.i("WIN ${w.no} follows $id")
                w.copy(corners = moved.corners, outward = moved.outward)
            }
        }
        set("room $why") { it.copy(room = room, roomVersion = it.roomVersion + 1, project = it.project.copy(windows = windows)) }
        setupVersion++
        SpLog.i("ROOM built why=$why ${room.describe()} virtual=${SpLog.pos(v.center.at(v.floorY))} ahead=(${"%.2f".format(Locale.US, v.ahead.x)}, ${"%.2f".format(Locale.US, v.ahead.z)}) size=${v.width}x${v.depth}x${v.height}")
        requestLight()
        requestHours()
    }

    /** Head sample done (design `virtualRoom.headSample`): rebuild the room / put the marker 1 m ahead on the floor. */
    fun onHeadSample(target: HeadTarget, sample: HeadSampler.Sample) {
        SpLog.i(SpLog.f("ROOM headSample target=%s at=%s yaw=%.1f timedOut=%s", target, SpLog.pos(sample.position), sample.yawDeg, sample.timedOut))
        set("head sampled") { it.copy(headSample = null) }
        if (sample.timedOut) showBar(BarKind.HEAD_TIMEOUT)
        when (target) {
            HeadTarget.Room -> {
                val pose = HeadPose(sample.position, Quat4.axisAngle(Vec3.UP, Math.toRadians(sample.yawDeg.toDouble()).toFloat()))
                vroom = VirtualRoom.around(pose, realFloorY() ?: vroom?.floorY, vroom)
                rebuildRoom("recenter")
            }
            is HeadTarget.Marker -> {
                val room = _state.value.room ?: return
                val ahead = Upright.ahead(sample.position, sample.yawDeg, 1.0f)
                val probe = Vec3(ahead.x, room.floorY + 0.02f, ahead.z)
                val surf = room.surfaceUnder(probe, room.floorY + 0.05f)
                moveMarker(target.id, Vec3(ahead.x, surf?.height ?: room.floorY, ahead.z), "front")
            }
        }
    }

    // ============================================================================================ windows

    private fun startMarking(redo: String?) {
        val s = _state.value
        if (s.finding || s.room == null) return SpLog.i("WIN mark refused reason=finding")
        if (redo == null && s.windows.size >= SunProject.MAX_WINDOWS) {
            SpLog.i("WIN mark refused reason=max")
            return showBar(BarKind.MAX_WINDOWS)
        }
        set("mark start") { it.copy(marking = Marking(redoId = redo), placing = false, selected = null) }
        SpLog.i("WIN marking start redo=$redo")
        WindowCtl.hideForMarking()
    }

    private fun endMarking(why: String) {
        if (_state.value.marking == null) return
        set("mark end $why") { it.copy(marking = null, page = Page.WINDOW, panelBack = true) }
        SpLog.i("WIN marking end why=$why")
        WindowCtl.showAfterMarking()
    }

    /** The window came back after marking ("面板马上回来" goes). */
    fun onPanelCreated() {
        if (_state.value.panelBack) set("panel back") { it.copy(panelBack = false) }
    }

    /** A trigger / pinch during S04 (scene: ray x room, D24 analytic). */
    fun onCornerTap(hit: RoomHit?) {
        val s = _state.value
        val m = s.marking ?: return
        val room = s.room ?: return
        fun reject(r: Reject) {
            SpLog.i("WIN corner rejected reason=$r at=${SpLog.pos(hit?.point)} role=${hit?.role}")
            set("corner reject") { it.copy(marking = m.copy(reject = r, rejectSeq = m.rejectSeq + 1)) }
        }
        if (hit == null || hit.role != HitRole.WALL || hit.surface == null) return reject(Reject.NOT_WALL)
        if (m.first == null) {
            sound.click()
            SpLog.i("WIN corner 1 at=${SpLog.pos(hit.point)} wall=${hit.surface.id}")
            set("corner 1") { it.copy(marking = m.copy(first = hit.point, wallId = hit.surface.id, reject = null)) }
            return
        }
        if (hit.surface.id != m.wallId) return reject(Reject.OTHER_WALL)
        val wall = room.surface(m.wallId) ?: return reject(Reject.OTHER_WALL)
        val id = m.redoId ?: ("w-" + UUID.randomUUID().toString().take(8))
        val sw = SunWindow.fromCorners(id, wall.id, m.first, hit.point, -wall.normal) ?: return reject(Reject.TOO_SMALL)
        sound.click()
        addWindow(sw, m.redoId, "corners")
        endMarking("done")
    }

    private fun addWindow(sw: SunWindow, redo: String?, via: String) {
        val p = _state.value.project
        val old = p.windows.firstOrNull { it.id == redo }
        val no = old?.no ?: ((1..SunProject.MAX_WINDOWS).firstOrNull { n -> p.windows.none { it.no == n } } ?: (p.windows.size + 1))
        val rec = WindowRec(sw.id, no, sw.wallId, sw.corners, sw.outward)
        old?.anchorId?.let { removeAnchor(it) }
        setProject("window $via") { pr -> pr.copy(windows = if (old != null) pr.windows.map { if (it.id == old.id) rec else it } else pr.windows + rec) }
        SpLog.i(SpLog.f("WIN add no=%d id=%s via=%s wall=%s size=%.2fx%.2f corners=%s", no, sw.id, via, sw.wallId, sw.width, sw.height, sw.corners.joinToString(" ") { SpLog.pos(it) }))
        createAnchor(rec.id, rec.center)
    }

    private fun deleteWindow(id: String) {
        val w = _state.value.windows.firstOrNull { it.id == id } ?: return
        w.anchorId?.let { removeAnchor(it) }
        setProject("window delete") { pr -> pr.copy(windows = pr.windows.filter { it.id != id }.mapIndexed { i, x -> x.copy(no = i + 1) }) }
        SpLog.i("WIN delete no=${w.no} id=$id")
    }

    /** [放一扇标准窗] (design T3): on the wall the ray points at, else the room's front wall, centred on the hit. */
    private fun stdWindow() {
        val s = _state.value
        val room = s.room ?: return
        if (s.windows.size >= SunProject.MAX_WINDOWS) {
            SpLog.i("WIN std refused reason=max")
            return showBar(BarKind.MAX_WINDOWS)
        }
        val aim = aimProvider?.invoke()
        val wall = if (aim?.role == HitRole.WALL) aim.surface else null
        val target = wall ?: frontWall(room) ?: return SpLog.w("WIN std: no wall")
        val c = target.polygon.fold(Vec3.ZERO) { a, v -> a + v } / target.polygon.size.toFloat()
        val u = target.frame.u
        val half = target.frame.sizeU / 2f
        val along = (((aim?.point ?: c) - c).dot(u)).coerceIn(-(half - 0.8f).coerceAtLeast(0f), (half - 0.8f).coerceAtLeast(0f))
        val at = c + u * along
        val id = "w-" + UUID.randomUUID().toString().take(8)
        val sw = SunWindow.standard(id, target.id, at, room.floorY, -target.normal)
        addWindow(sw, null, if (wall != null) "std-ray" else "std-front")
        set("std window") { it.copy(page = Page.WINDOW) }
    }

    /** The wall straight ahead in the room's own frame (the virtual box's front: where the user looked when it was built). */
    fun frontWall(room: Room): io.embodit.sunpatch.domain.LitSurface? {
        room.surface(VirtualRoom.ID_WALL + 2)?.let { return it }
        val ahead = room.virtual?.ahead?.at(0f) ?: Vec3.AHEAD
        return room.walls.minByOrNull { it.normal.dot(ahead) }
    }

    /** Wall of the room for debug `stdwindow front|left|right|back` (virtual box order: back 0, right 1, front 2, left 3). */
    fun wallFor(name: String): io.embodit.sunpatch.domain.LitSurface? {
        val room = _state.value.room ?: return null
        val idx = when (name) { "back" -> 0; "right" -> 1; "left" -> 3; else -> 2 }
        room.surface(VirtualRoom.ID_WALL + idx)?.let { return it }
        val v = room.virtual ?: return frontWall(room)
        val dir = when (idx) { 0 -> -v.ahead; 1 -> v.ahead.right; 3 -> -v.ahead.right; else -> v.ahead }.at(0f)
        return room.walls.minByOrNull { it.normal.dot(dir) }
    }

    private fun setFacing(deg: Float) {
        val p = _state.value.project
        val w1 = p.windows.firstOrNull() ?: return SpLog.i("SUN facing refused: no window")
        val bearing = Compass.wrap360(Math.round(deg).toFloat())
        val north = Compass.northYawFor(w1.outward, bearing)
        setProject("facing $bearing") { it.copy(northYawDeg = north, northSet = true) }
        SpLog.i(SpLog.f("SUN facing window1=%.0f northYaw=%.1f", bearing, north))
    }

    // ============================================================================================ markers

    /** A trigger / pinch during S11: drop the marker on a floor / upward face, or select one within 12 cm. */
    fun onPlaceTap(hit: RoomHit?) {
        val s = _state.value
        if (!s.placing) return
        if (hit == null || hit.surface == null || (hit.role != HitRole.FLOOR && hit.role != HitRole.UP)) {
            SpLog.i("MARK rejected reason=${hit?.role ?: "nothing"} at=${SpLog.pos(hit?.point)}")
            return
        }
        near(hit.point)?.let {
            SpLog.i("MARK select-near no=${it.no}")
            return onEvent(Ev.SelectMarker(it.id))
        }
        if (s.markers.size >= SunProject.MAX_MARKERS) {
            set("place max") { it.copy(placing = false) }
            return showBar(BarKind.MAX_MARKERS)
        }
        val no = (1..SunProject.MAX_MARKERS).firstOrNull { n -> s.markers.none { it.no == n } } ?: (s.markers.size + 1)
        val m = MarkerRec("m-" + UUID.randomUUID().toString().take(8), no, MarkerKind.PLANT, MarkerKind.PLANT.want, hit.point)
        sound.click()
        setProject("marker add") { it.copy(markers = it.markers + m) }
        set("marker placed") { it.copy(placing = false, selected = m.id, page = Page.HOURS) }
        SpLog.i("MARK add no=$no id=${m.id} at=${SpLog.pos(m.pos)} on=${hit.role}:${hit.surface.id}")
        createAnchor(m.id, m.pos)
    }

    private fun near(p: Vec3): MarkerRec? = _state.value.markers.filter { abs(it.pos.y - p.y) < 0.2f && P2.of(it.pos - p).length < SELECT_M }.minByOrNull { P2.of(it.pos - p).length }

    /** A tap on a marker's collider. */
    fun onMarkerPicked(id: String) {
        val s = _state.value
        SpLog.i("MARK pick id=$id")
        // S10: the selected ring tapped again lets go of it (back to S09 / S07)
        if (s.selected == id && s.page == Page.HOURS && !s.placing) return onEvent(Ev.SelectMarker(null))
        onEvent(Ev.SelectMarker(id))
    }

    fun onDragStart(id: String) {
        set("drag start") { it.copy(dragging = id, selected = id, page = Page.HOURS) }
        SpLog.i("MARK drag start id=$id")
    }

    /** Drag released (D1: it stays where it was let go). [pos] null = cancelled (back to the start). */
    fun onDragEnd(id: String, pos: Vec3?) {
        set("drag end") { it.copy(dragging = null) }
        if (pos == null) return SpLog.i("MARK drag cancel id=$id")
        moveMarker(id, pos, "drag")
    }

    private fun moveMarker(id: String, pos: Vec3, via: String) {
        val m = _state.value.marker(id) ?: return
        m.anchorId?.let { removeAnchor(it) }
        setProject("marker move") { pr -> pr.copy(markers = pr.markers.map { if (it.id == id) it.copy(pos = pos, anchorId = null, anchorPose = null) else it }) }
        SpLog.i("MARK move no=${m.no} via=$via to=${SpLog.pos(pos)}")
        createAnchor(id, pos)
    }

    private fun updateMarker(id: String, why: String, f: (MarkerRec) -> MarkerRec) {
        val before = _state.value.marker(id) ?: return
        val after = f(before)
        set("marker $why") { it.copy(project = it.project.copy(markers = it.project.markers.map { m -> if (m.id == id) after else m })) }
        if (after.want != before.want) {
            val st = _state.value.hours?.stats?.get(id)
            if (st != null) set("verdict") { it.copy(verdicts = it.verdicts + (id to Verdicts.of(after.want, st.hours))) }
        }
        SpLog.i("MARK edit no=${after.no} $why")
    }

    private fun deleteMarker(id: String) {
        val m = _state.value.marker(id) ?: return
        m.anchorId?.let { removeAnchor(it) }
        setProject("marker delete") { pr -> pr.copy(markers = pr.markers.filter { it.id != id }) }
        set("marker deleted") { it.copy(selected = null, confirmDelete = false, verdicts = it.verdicts - id) }
        SpLog.i("MARK delete no=${m.no}")
    }

    // ============================================================================================ light (<= 10 Hz)

    fun setup(s: SpUiState = _state.value): SunSetup? {
        val room = s.room ?: return null
        if (s.windows.isEmpty()) return null
        return SunSetup(s.project.city, s.project.northYawDeg, room.surfaces, s.windows.map { it.sun() })
    }

    private var lightBusy = false
    private var lightDirty = false
    private var lightPosted = false
    private var lastLightStart = 0L

    fun requestLight() {
        if (lightBusy) {
            lightDirty = true
            return
        }
        if (lightPosted) return
        lightPosted = true
        val wait = max(0L, LIGHT_EVERY_MS - (SystemClock.elapsedRealtime() - lastLightStart))
        main.postDelayed(runLight, wait)
    }

    private val runLight = Runnable {
        lightPosted = false
        val s = _state.value
        val key = "$setupVersion|${s.project.northSet}|${s.date}|${s.minute}|" + s.markers.joinToString(",") { "${it.id}@${it.pos}" }
        if (s.light?.key == key) return@Runnable
        val setup = setup(s)
        val room = s.room
        lightBusy = true
        lastLightStart = SystemClock.elapsedRealtime()
        val inputs = LightInputs(key, setup, room, s.date, s.minute, s.project.northSet, s.project.city, s.markers)
        scope.launch {
            val r = runCatching { withContext(lightThread) { computeLight(inputs) } }.onFailure { SpLog.e("SUN compute failed: $it", it) }.getOrNull()
            lightBusy = false
            if (r != null) {
                set("light") { it.copy(light = r) }
                logLight(r)
            }
            if (lightDirty) {
                lightDirty = false
                requestLight()
            }
        }
    }

    private class LightInputs(val key: String, val setup: SunSetup?, val room: Room?, val date: LocalDate, val minute: Int, val northSet: Boolean, val city: io.embodit.sunpatch.domain.City, val markers: List<MarkerRec>)

    private fun computeLight(i: LightInputs): LightResult {
        val t0 = SystemClock.elapsedRealtime()
        val pos = Solar.position(i.city, i.date, i.minute)
        val (rise, set) = Solar.riseSet(i.city, i.date)
        val readout = Readout(pos.altDeg, pos.azDeg, rise, set)
        val setup = i.setup
        if (setup == null || !i.northSet) {
            val c = if (setup == null) Conclusion.NoWindow else Conclusion.NoFacing
            return LightResult(i.key, i.date, i.minute, io.embodit.sunpatch.domain.SunFrame.DARK, emptyMap(), emptyMap(), readout, c, null, SystemClock.elapsedRealtime() - t0)
        }
        val s = Compass.sunVector(pos, setup.northYawDeg)
        val frame = SunLight.frame(setup.surfaces, setup.windows, s)
        val rasters = HashMap<String, io.embodit.sunpatch.domain.Raster>()
        val areas = HashMap<String, Float>()
        for (surf in setup.surfaces) {
            if (frame.pieces.none { it.surfaceId == surf.id }) continue
            val r = SunLight.raster(surf, frame)
            rasters[surf.id] = r
            areas[surf.id] = r.cells.count { it > 0 } * r.cell * r.cell
        }
        val floor = setup.surfaces.filter { it.role == SurfaceRole.FLOOR }.sumOf { (areas[it.id] ?: 0f).toDouble() }.toFloat()
        val up = setup.surfaces.any { it.role == SurfaceRole.UP && (areas[it.id] ?: 0f) > 0.005f }
        val wall = setup.surfaces.any { it.role == SurfaceRole.WALL && (areas[it.id] ?: 0f) > 0.005f }
        var next: NextEntry.Result? = null
        val conclusion: Conclusion =
            if (pos.altDeg <= 0.0) {
                if (rise != null && i.minute < rise) Conclusion.BeforeRise(rise) else if (rise == null && i.minute < 720) Conclusion.BeforeRise(null) else Conclusion.AfterSet
            } else if (frame.anyLight) {
                val lit = i.markers.filter { m ->
                    val surf = i.room?.surfaceUnder(m.pos) ?: return@filter false
                    SunLight.Lookup(surf, frame).coverage(m.pos) > 0
                }.map { it.id }
                Conclusion.Lands(floor, up, wall, lit)
            } else {
                next = NextEntry.at(setup, i.date, i.minute)
                when (val n = next) {
                    is NextEntry.Result.After -> Conclusion.Behind(n.minute, setup.windows.indexOfFirst { it.id == n.windowId } + 1, setup.windows.size > 1)
                    NextEntry.Result.AfterSet -> Conclusion.AfterSet
                    NextEntry.Result.BeforeRise -> Conclusion.BeforeRise(rise)
                    else -> Conclusion.NotToday
                }
            }
        return LightResult(i.key, i.date, i.minute, frame, rasters, areas, readout, conclusion, next, SystemClock.elapsedRealtime() - t0)
    }

    private fun logLight(r: LightResult) {
        SpLog.i(SpLog.f("SUN %s %s alt=%.1f az=%.1f rise=%s set=%s concl=%s ms=%d", r.date, Solar.fmt(r.minute.toDouble()), r.readout.altDeg, r.readout.azDeg, Solar.fmt(r.readout.rise), Solar.fmt(r.readout.set), r.conclusion, r.ms))
        if (r.litArea.isNotEmpty()) SpLog.i("PATCH pieces=${r.frame.pieces.size} shadows=${r.frame.shadows.size} lit=" + r.litArea.entries.joinToString(",") { "${it.key.take(14)}:${"%.2f".format(Locale.US, it.value)}" })
    }

    // ============================================================================================ hours (debounced)

    private var hoursBusy = false
    private var hoursDirty = false

    fun requestHours() {
        main.removeCallbacks(runHours)
        main.postDelayed(runHours, HOURS_DEBOUNCE_MS)
    }

    private val runHours = Runnable {
        if (hoursBusy) {
            hoursDirty = true
            return@Runnable
        }
        val s = _state.value
        val setup = setup(s)
        if (setup == null || !s.project.northSet) {
            if (s.hours != null) set("hours none") { it.copy(hours = null) }
            return@Runnable
        }
        val key = "$setupVersion|${s.date}|" + s.markers.joinToString(",") { "${it.id}@${it.pos}" }
        if (s.hours?.key == key) return@Runnable
        hoursBusy = true
        val room = s.room
        val markers = s.markers
        val date = s.date
        scope.launch {
            val r = runCatching {
                withContext(hoursThread) {
                    val t0 = SystemClock.elapsedRealtime()
                    val map = HoursMap.compute(setup, date)
                    val stats = markers.associate { m ->
                        val surf = room?.surfaceUnder(m.pos)
                        m.id to (if (surf == null) MarkerStats.NONE else MarkerStats.withMonths(setup, surf.id, m.pos, date))
                    }
                    HoursResult(key, date, map, map.areaAtLeast(4f), map.cellsAtLeast(4f), stats, SystemClock.elapsedRealtime() - t0)
                }
            }.onFailure { SpLog.e("HOURS compute failed: $it", it) }.getOrNull()
            hoursBusy = false
            if (r != null) {
                set("hours") { st ->
                    val v = st.markers.associate { m -> m.id to Verdicts.stable(m.want, r.stats[m.id]?.hours ?: 0f, st.verdicts[m.id]) }
                    st.copy(hours = r, verdicts = v)
                }
                SpLog.i(SpLog.f("HOURS %s area4=%.2f m2 cells4=%d layers=%d ms=%d markers=%s", r.date, r.area4, r.cells4, r.map.layers.size, r.ms, r.stats.entries.joinToString(",") { "${it.key}:${"%.2f".format(Locale.US, it.value.hours)}" }))
            }
            if (hoursDirty) {
                hoursDirty = false
                requestHours()
            }
        }
    }

    // ============================================================================================ sweep (8 s)

    private var sweepJob: Job? = null
    private var sweepPausedAt = 0L
    private var sweepPausedTotal = 0L

    private fun startSweep(holdMs: Long?) {
        val s = _state.value
        val setup = setup(s)
        if (setup == null || !s.project.northSet) return SpLog.i("HOURS sweep refused: no window / facing")
        sweepJob?.cancel()
        val date = s.date
        val minutes = setup.dayMinutes(date).step(Solar.STEP_MIN).toList()
        if (minutes.isEmpty()) return
        val clock = SweepClock(minutes.size)
        val acc = HoursMap.Accumulator(setup.surfaces, SunLight.HOURS_CELL_M)
        val room = s.room
        val markers = s.markers
        val soFar = HashMap<String, Float>()
        sweepPausedAt = 0L
        sweepPausedTotal = 0L
        set("sweep start") { it.copy(heat = false, sweep = SweepView(true, minutes.first(), 0, minutes.size, null, emptyMap()), minute = minutes.first(), selected = if (it.page == Page.HOURS) it.selected else it.selected) }
        SpLog.i("HOURS sweep start date=$date steps=${minutes.size} from=${Solar.fmt(minutes.first().toDouble())} to=${Solar.fmt(minutes.last().toDouble())} hold=$holdMs")
        val t0 = SystemClock.elapsedRealtime()
        sweepJob = scope.launch {
            var lastPublish = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val paused = if (sweepPausedAt > 0) now - sweepPausedAt else 0L
                var elapsed = now - t0 - sweepPausedTotal - paused
                if (holdMs != null) elapsed = min(elapsed, holdMs)
                val from = clock.done
                val n = if (paused > 0) 0 else clock.due(elapsed)
                if (n > 0) {
                    withContext(sweepThread) {
                        for (k in from until from + n) {
                            val m = minutes[k]
                            val f = setup.frameAt(date, m)
                            acc.add(f, Solar.STEP_MIN.toFloat())
                            if (f.anyLight) for (mk in markers) {
                                val surf = room?.surfaceUnder(mk.pos) ?: continue
                                if (SunLight.Lookup(surf, f).coverage(mk.pos) > 0) soFar[mk.id] = (soFar[mk.id] ?: 0f) + Solar.STEP_MIN / 60f
                            }
                        }
                    }
                }
                val finished = clock.finished
                if (n > 0 && (now - lastPublish >= 100 || finished)) {
                    lastPublish = now
                    val trail = copyMap(acc.result(date))
                    val minute = minutes[(clock.done - 1).coerceAtLeast(0)]
                    set("sweep step") { it.copy(minute = minute, sweep = SweepView(true, minute, clock.done, clock.totalSteps, trail, HashMap(soFar))) }
                    requestLight()
                }
                if (finished) break
                delay(SWEEP_TICK_MS)
            }
            val trail = _state.value.sweep?.trail
            lastTrailCells4 = trail?.cellsAtLeast(4f) ?: -1
            SpLog.i(SpLog.f("HOURS sweep done in %d ms trailCells4=%d trailArea4=%.2f", SystemClock.elapsedRealtime() - t0 - sweepPausedTotal, lastTrailCells4, trail?.areaAtLeast(4f) ?: 0f))
            set("sweep done") { it.copy(sweep = null, heat = true) }
            sound.ding()
            requestHours()
        }
    }

    private fun copyMap(m: HoursMap): HoursMap = HoursMap(m.date, m.layers.mapValues { (_, l) -> HoursMap.Layer(l.surface, l.w, l.h, l.cell, l.minutes.copyOf()) })

    private fun stopSweep(why: String) {
        val sw = _state.value.sweep ?: return
        if (!sw.running) return
        sweepJob?.cancel()
        sweepJob = null
        lastTrailCells4 = sw.trail?.cellsAtLeast(4f) ?: -1
        SpLog.i("HOURS sweep stop why=$why at=${Solar.fmt(sw.minute.toDouble())} done=${sw.done}/${sw.total}")
        set("sweep stop") { it.copy(sweep = SweepView(false, sw.minute, sw.done, sw.total, sw.trail, sw.soFar)) }
    }

    /** Touching a slider stops a running sweep; the kept trail goes on the next slider move (design `sunModel.sweep`). */
    private fun stopSweepForSlider() {
        val sw = _state.value.sweep ?: return
        if (sw.running) stopSweep("slider") else set("trail cleared") { it.copy(sweep = null) }
    }

    // ============================================================================================ anchors / restore

    private fun createAnchor(itemId: String, at: Vec3) {
        if (_state.value.noPermission) return
        scope.launch {
            when (val r = anchors.create(at, "sunpatch:$itemId")) {
                is AnchorOutcome.Ok -> {
                    val rec = r.value
                    SpLog.i("ANCHOR created item=$itemId id=${rec.id} at=${SpLog.pos(rec.pose.position)}")
                    setProject("anchor") { pr ->
                        pr.copy(
                            windows = pr.windows.map { if (it.id == itemId) it.copy(anchorId = rec.id, anchorPose = rec.pose) else it },
                            markers = pr.markers.map { if (it.id == itemId) it.copy(anchorId = rec.id, anchorPose = rec.pose) else it },
                        )
                    }
                }
                is AnchorOutcome.Failed -> SpLog.w("ANCHOR create failed item=$itemId code=${r.code} ${r.message}")
            }
        }
    }

    private fun removeAnchor(id: String) {
        scope.launch {
            val r = anchors.remove(id)
            SpLog.i("ANCHOR removed id=$id ok=${r is AnchorOutcome.Ok}")
        }
    }

    /**
     * Restore (design `dataModel.restore`): every window / marker with a found anchor moves with it; the others follow the
     * shift of a found one (S18 partial bar); none found -> S17. The north turns with the found anchor's yaw.
     */
    private suspend fun restore(windowMs: Long) {
        val pr0 = _state.value.project
        val items = pr0.windows.mapNotNull { w -> w.anchorId?.let { it to w.anchorPose } } + pr0.markers.mapNotNull { m -> m.anchorId?.let { it to m.anchorPose } }
        set("restore") { it.copy(restoring = true, searching = true) }
        SpLog.i("ANCHOR restore start items=${items.size} window=${windowMs} ms")
        var found: Map<String, AnchorRecord> = emptyMap()
        val deadline = SystemClock.elapsedRealtime() + windowMs
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val r = anchors.loadAll()) {
                is AnchorOutcome.Ok -> found = r.value.associateBy { it.id }
                is AnchorOutcome.Failed -> SpLog.w("ANCHOR load failed code=${r.code} ${r.message}")
            }
            if (items.isNotEmpty() && items.all { it.first in found }) break
            delay(600)
        }
        val pr = _state.value.project
        val ref = (pr.windows.map { Triple(it.anchorId, it.anchorPose, it) } + pr.markers.map { Triple(it.anchorId, it.anchorPose, it) })
            .firstOrNull { (id, pose, _) -> id != null && pose != null && id in found }
        if (ref == null) {
            SpLog.i("ANCHOR restore none found (loaded=${found.size}) -> S17")
            set("mismatch") { it.copy(mismatch = true, restoring = false, searching = false, page = Page.WINDOW) }
            return
        }
        val refOld = ref.second!!
        val refNew = found[ref.first!!]!!.pose
        fun shift(old: AnchorPose, new: AnchorPose): Pair<(Vec3) -> Vec3, (Vec3) -> Vec3> {
            val dy = Math.toRadians((new.yawDeg - old.yawDeg).toDouble()).toFloat()
            val q = Quat4.axisAngle(Vec3.UP, dy)
            return Pair({ p: Vec3 -> new.position + q.rotate(p - old.position) }, { d: Vec3 -> q.rotate(d) })
        }
        val (refMove, refTurn) = shift(refOld, refNew)
        var exact = 0
        var guessed = 0
        val windows = pr.windows.map { w ->
            val a = w.anchorId?.let { found[it] }
            if (a != null && w.anchorPose != null) {
                exact++
                val (mv, tn) = shift(w.anchorPose, a.pose)
                w.moved(mv, tn).copy(anchorPose = a.pose)
            } else {
                guessed++
                w.moved(refMove, refTurn)
            }
        }
        val markers = pr.markers.map { m ->
            val a = m.anchorId?.let { found[it] }
            if (a != null && m.anchorPose != null) {
                exact++
                val (mv, _) = shift(m.anchorPose, a.pose)
                m.copy(pos = mv(m.pos), anchorPose = a.pose)
            } else {
                guessed++
                m.copy(pos = refMove(m.pos))
            }
        }
        val north = Compass.yawOf(refTurn(Compass.dirOfYaw(pr.northYawDeg)))
        setProject("restored") { it.copy(windows = windows, markers = markers, northYawDeg = Compass.wrap360(north)) }
        set("restored") { it.copy(mismatch = false, restoring = false, searching = false, page = Page.SUN, welcome = false) }
        SpLog.i("ANCHOR restore exact=$exact guessed=$guessed north=${"%.1f".format(Locale.US, north)}")
        if (guessed == 0) showBar(BarKind.RESTORED, windows.size, markers.size, 3000L) else showBar(BarKind.PARTIAL, guessed, 0, 5000L)
    }

    /** Debug `mismatch`: the stored anchors are not found this time (S17). */
    fun debugMismatch() {
        anchors.hideStored = true
        scope.launch { restore(1500L) }
    }

    /** Debug `goto S18`: run the restore again from the saved project. */
    fun debugRestore() {
        anchors.hideStored = false
        scope.launch { restore(4000L) }
    }

    fun debugNoPermission(on: Boolean) {
        SpLog.i("ROOM noPermission=$on")
        set("noPermission") { it.copy(noPermission = on, useVirtual = if (on) true else it.useVirtual) }
        if (on) {
            main.removeCallbacks(findCheck)
            set("noPermission room") { it.copy(finding = false) }
            rebuildRoom("noPermission")
        } else startFinding("permission")
    }

    fun debugFirstRun() = set("welcome") { it.copy(welcome = true, page = Page.WINDOW) }

    fun onLost(lost: Boolean) {
        val s = _state.value
        if (s.lost == lost) return
        SpLog.i("STATE tracking lost=$lost")
        if (lost) set("lost") { it.copy(lost = true) }
        else {
            set("found") { it.copy(lost = false, dragging = null) }
            if (s.marking != null) endMarking("lost")
        }
    }

    // ============================================================================================ bars, reset, quit, ticker

    private fun showBar(kind: BarKind, a: Int = 0, b: Int = 0, ms: Long = 4000L) {
        val until = SystemClock.elapsedRealtime() + if (barLong) 60_000L else ms
        set("bar $kind") { it.copy(bar = Bar(kind, a, b, until)) }
    }

    private fun resetAll() {
        val pr = _state.value.project
        (pr.windows.mapNotNull { it.anchorId } + pr.markers.mapNotNull { it.anchorId }).forEach { removeAnchor(it) }
        stopSweep("reset")
        setProject("reset") { it.copy(windows = emptyList(), markers = emptyList()) }
        set("reset done") { it.copy(confirmReset = false, mismatch = false, selected = null, heat = false, sweep = null, page = Page.WINDOW, verdicts = emptyMap()) }
        SpLog.i("WIN reset: windows and markers cleared")
        if (!_state.value.useVirtual) startFinding("reset")
    }

    private fun background(on: Boolean) {
        SpLog.i("STATE background=$on")
        if (on) {
            planes.stop()
            if (_state.value.sweep?.running == true && sweepPausedAt == 0L) sweepPausedAt = SystemClock.elapsedRealtime()
            store?.saveProject(_state.value.project)
        } else {
            planes.start { onPlanesChanged() }
            if (sweepPausedAt > 0L) {
                sweepPausedTotal += SystemClock.elapsedRealtime() - sweepPausedAt
                sweepPausedAt = 0L
            }
        }
    }

    private val ticker: Runnable =
        object : Runnable {
            override fun run() {
                val s = _state.value
                val now = SystemClock.elapsedRealtime()
                if (s.bar != null && now > s.bar.untilMs) set("bar end") { it.copy(bar = null) }
                if (s.prefs.followNow && s.sweep?.running != true) {
                    val n = nowLocal()
                    val m = roundMinute(n.hour * 60 + n.minute)
                    if (m != s.minute || n.toLocalDate() != s.date) {
                        set("follow") { it.copy(minute = m, date = n.toLocalDate(), isToday = true) }
                        requestLight()
                        requestHours()
                    }
                }
                main.postDelayed(this, 1000L)
            }
        }

    private fun saveNow() {
        store?.saveProject(_state.value.project)
        SpLog.i("SAVE project windows=${_state.value.windows.size} markers=${_state.value.markers.size}")
    }

    private val saveRunnable = Runnable { saveNow() }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 500L)
    }

    /** D26 exit, factory template order: release, close the window and the Stage, remove the tasks, end. */
    private fun quit() {
        SpLog.i("EXIT confirmed: release + close window + closeStage + finishAndRemoveTask + exit")
        saveNow()
        sweepJob?.cancel()
        sound.release()
        planes.stop()
        anchors.stop()
        quitHook?.invoke()
        WindowCtl.closeForQuit()
        val nav = WindowCtl.navigator
        val a = activity
        MainScope().launch {
            runCatching { nav?.closeStage() }.onFailure { SpLog.w("EXIT closeStage: $it") }
            runCatching {
                val am = a?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.appTasks?.forEach { runCatching { it.finishAndRemoveTask() } }
                a?.finishAndRemoveTask()
            }.onFailure { SpLog.w("EXIT finishAndRemoveTask: $it") }
            main.postDelayed({ exitProcess(0) }, 400)
        }
    }

    // ============================================================================================ dump

    fun dumpLines(): List<String> {
        val s = _state.value
        val out = ArrayList<String>()
        out += "DUMP ${s.stateLine()}"
        out += "DUMP window panel=${WindowCtl.panelState} page=${s.page.id} levelHint=${WindowCtl.levelHint}"
        out += "DUMP city=${s.project.city.key} ${s.project.city.coords} zone=${s.project.city.zoneId} north=${"%.1f".format(Locale.US, s.project.northYawDeg)} set=${s.project.northSet} facing1=${s.project.facingDeg()?.let { "%.0f".format(Locale.US, it) }}"
        s.room?.let { out += "DUMP room ${it.describe()}" }
        s.room?.virtual?.let { v -> out += SpLog.f("DUMP vroom center=(%.2f, %.2f) ahead=(%.3f, %.3f) floorY=%.2f size=%.1fx%.1fx%.1f", v.center.x, v.center.z, v.ahead.x, v.ahead.z, v.floorY, v.width, v.depth, v.height) }
        val l = s.light
        if (l != null) {
            out += SpLog.f("DUMP sun %s %s alt=%.2f az=%.2f rise=%s set=%s concl=%s compute=%d ms", l.date, Solar.fmt(l.minute.toDouble()), l.readout.altDeg, l.readout.azDeg, Solar.fmt(l.readout.rise), Solar.fmt(l.readout.set), l.conclusion, l.ms)
            out += "DUMP next=${l.next ?: s.setupOrNull()?.let { NextEntry.at(it, s.date, s.minute) }}"
        }
        for (w in s.windows) {
            out += SpLog.f("DUMP win no=%d id=%s wall=%s bearing=%.1f size=%.2fx%.2f corners=%s anchor=%s", w.no, w.id, w.wallId, s.project.bearingOf(w), w.width, w.height, w.corners.joinToString(" ") { SpLog.pos(it) }, w.anchorId)
        }
        val room = s.room
        if (l != null && room != null) {
            for (p in l.frame.pieces) {
                val surf = room.surface(p.surfaceId) ?: continue
                val w = s.windows.firstOrNull { it.id == p.windowId } ?: continue
                val one = io.embodit.sunpatch.domain.SunFrame(l.frame.sun, listOf(p), l.frame.shadows.filter { it.surfaceId == p.surfaceId }, l.frame.litWindows)
                val area = SunLight.litArea(surf, one)
                val inward = -w.outward
                val dists = p.quad.map { (it - w.center).dot(inward) }
                val extra = if (surf.role == SurfaceRole.WALL) SpLog.f(" top=%.3f bottom=%.3f (above floor)", p.quad.maxOf { it.y } - room.floorY, p.quad.minOf { it.y } - room.floorY) else ""
                out += SpLog.f("DUMP patch win=%d surface=%s:%s area=%.3f fromWall=%.3f..%.3f quad=%s%s", w.no, surf.role, surf.id.take(16), area, dists.min(), dists.max(), p.quad.joinToString(" ") { SpLog.pos(it) }, extra)
            }
            for (sh in l.frame.shadows) {
                val occ = room.surface(sh.occluderId) ?: continue
                val c = sh.polygon.fold(Vec3.ZERO) { a, v -> a + v } / sh.polygon.size.toFloat()
                val oc = occ.polygon.fold(Vec3.ZERO) { a, v -> a + v } / occ.polygon.size.toFloat()
                val off = P2.of(c - oc).length
                out += SpLog.f("DUMP shadow on=%s of=%s centroid=%s occluder=%s horizOffset=%.3f", sh.surfaceId.take(16), sh.occluderId.take(16), SpLog.pos(c), SpLog.pos(oc), off)
            }
            for ((id, a) in l.litArea) out += SpLog.f("DUMP lit surface=%s area=%.3f", id.take(16), a)
        }
        s.hours?.let { h -> out += SpLog.f("DUMP hours %s area4=%.3f cells4=%d trailCells4=%d compute=%d ms", h.date, h.area4, h.cells4, lastTrailCells4, h.ms) }
        s.sweep?.let { sw -> out += SpLog.f("DUMP sweep running=%s minute=%s done=%d/%d trailCells4=%d", sw.running, Solar.fmt(sw.minute.toDouble()), sw.done, sw.total, sw.trail?.cellsAtLeast(4f) ?: -1) }
        for (m in s.markers) {
            val st = s.hours?.stats?.get(m.id)
            out += SpLog.f("DUMP marker no=%d id=%s kind=%s want=%s at=%s hours=%.2f first=%s last=%s verdict=%s anchor=%s", m.no, m.id, m.kind, m.want, SpLog.pos(m.pos), st?.hours ?: -1f, Solar.fmt(st?.first?.toDouble()), Solar.fmt(st?.last?.toDouble()), s.verdicts[m.id], m.anchorId)
        }
        return out
    }

    private fun SpUiState.setupOrNull(): SunSetup? = setup(this)

    // ============================================================================================ helpers

    fun roundMinute(m: Int): Int = ((m + Solar.STEP_MIN / 2) / Solar.STEP_MIN * Solar.STEP_MIN).coerceIn(0, Solar.DAY_MIN)

    fun verdictOf(id: String): Verdict? = _state.value.verdicts[id]

    @Suppress("unused")
    private fun yawFromDir(d: Vec3): Float = Compass.yawOf(d)

    @Suppress("unused")
    private fun turnXZ(v: Vec3, deg: Float): Vec3 {
        val r = Math.toRadians(deg.toDouble())
        return Vec3((v.x * cos(r) - v.z * sin(r)).toFloat(), v.y, (v.x * sin(r) + v.z * cos(r)).toFloat())
    }

    const val SELECT_M = 0.12f
    private const val FIND_TIMEOUT_MS = 5000L
    private const val PLANES_EVERY_MS = 2000L
    private const val LIGHT_EVERY_MS = 100L
    private const val HOURS_DEBOUNCE_MS = 300L
    private const val SWEEP_TICK_MS = 30L
}
