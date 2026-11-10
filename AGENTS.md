# Sun Patch (窗边日照)

MR sunlight tool for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space only, manifest stage
style 1), package `io.embodit.sunpatch`, version 0.1.1. Mark a real window with two corners (or drop a standard
window on a wall), set the window's compass facing and the city, drag the clock and the month: the window's patch of
direct sun is drawn at 1:1 on the floor, upward faces and lit walls. "扫一天" sweeps sunrise to sunset in 8 s and leaves
the hours map; markers get hours, first / last sun and a verdict.

Design package (binding): `../spatial-app-factory/apps/sun-patch/design-package.json` (states S00-S19, `sunModel`,
`virtualRoom`, `spatialLayout`, `panels`, `content.copy`, `palette`, `verification.debugHooks`, `acceptance`). Mockup:
`../spatial-app-factory/apps/sun-patch/html/sample-KgKJWi.html`. Skeleton D: window / LevelGate / Stage plate from
drone-sight v0.1.3, plane tracker / anchors / light layers / text plates / ray pattern from light-planner v0.1.1.

## Structure

```
app/src/main/java/io/embodit/sunpatch/
  Main.kt                 DefaultStage { SpScreen } + one Planar WindowContainer 560 x 480 dp { SpWindow }
  domain/                 pure Kotlin (JVM unit tests in app/src/test)
    Solar.kt              NOAA sun position, rise / set (-0.833 deg), Compass (north yaw, bearings, sun vector)
    SunLight.kt           LitSurface / SurfaceFrame, SunWindow (two corners, standard window), SunLight.frame (parallel
                          projection of windows + shadows of upward faces), raster (4 cm coverage), Lookup, Poly2
    Hours.kt              SunSetup, HoursMap (+ Accumulator for the sweep), HeatRamp, MarkerKind, Want, Verdict(s),
                          MarkerStats, NextEntry, SweepClock, HeadSampler, Upright
    Room.kt               Room: scanned FLOOR / WALL / upward faces + missing walls from the VirtualRoom (or the box
                          alone), <= 12 receivers; analytic ray x room hits (RoomHit, D24); WallSlide (a window follows
                          its virtual wall when the box is resized / turned / moved)
    Project.kt            SunProject (city, northYawDeg independent of the windows, windows, markers), AnchorPose, Dirs
    VirtualRoom.kt Geometry.kt Vec3.kt Billboard.kt LevelGate.kt Cities.kt
  data/Anchors.kt         AnchorGateway interface
  platform/               SpLog (tag SunPatch), Store (prefs + files/project.json), Sound (synth click / ding),
                          input/Controllers (ray, trigger state, B / Y, debug ray), tracking/HeadTracker,
                          sense/PlaneTracker (no colliders), anchor/WorldAnchorGateway
  ui/SpUiState.kt         SpUiState (+ designState S00..S19), Ev (every user / debug event), LightResult, HoursResult
  ui/SpController.kt      process-level store + state machine (§3.4); room finding (5 s -> virtual room), windows,
                          facing, markers, light worker (<= 10 Hz), hours worker (debounced), 8 s sweep loop, anchors,
                          restore (S17 / S18), save (500 ms debounce), quit, dump
  ui/SpScreen.kt          Stage composable: controller start, SunScene, frame system, window ask
  ui/scene/SunScene.kt    ECS: light layers, windows (frame, plate, sun ray + disc), markers (ring, dot, 12 cm sphere
                          collider, plate), reticle / ghost / preview, virtual room edges, tap catcher (S04 / S11 only),
                          Stage plate placement, head sampler, drags; per frame in onFrame only
  ui/scene/Views.kt       LightLayerView, WindowView, MarkerView, ReticleView, TapCatcher, Plates (bitmaps)
  ui/scene/SceneKit.kt    materials, meshes (programmatic indicators), Part / Strip / LinePool / TextPlate, WorkQueue
  ui/scene/PlateSlot.kt   the one Stage attachment slot `stagePlate` (360 x 160 dp)
  ui/window/              MainWindowTemplate (TabBar + Toolbar + settings last), SpWindow (WindowCtl: LevelGate-gated
                          openWindowContainer, recall, close for marking, reopen after ON_DESTROY)
  ui/components/          Panels (Window / Sun / Hours / Settings pages, Stage plate content), SpStage, Strings,
                          TextFit (OVERFLOW guard), GlassRefresh
  ui/theme/               SpTheme (PicoTheme + design roles + Noto Sans / Noto Sans SC subset), SpColors, SpType
app/src/debug/            DebugCommandReceiver + DebugCommands
tools/                    make_icon.py (3D icon layers), subset_cjk_font.py, vector_render.py
```

One direction of data: window controls, Stage plate keys, scene taps / drags and debug commands -> `SpController`
events -> `SpUiState` (+ worker results) -> `SunScene.render` / the window pages. The frame system writes no Compose
state except `StageUi` (plate mode / visibility) and the window gate hint.

## Rules (what the code does)

- v0.1.1 (QA #1): the main window never opens in front of a marked window. `domain/PanelCone` + `LevelGate.step(aside)`:
  while a window opened on the line of sight would cover a marked window (drawn half width 16.8 deg + 8 deg margin), the
  gate waits and the Stage plate (`PlateMode.ASIDE`) says "面板开在窗旁边，往左 / 右看一点"; a standard window dropped
  behind the open panel recalls it once (`SunScene.stepPanelAside`). Plain keys are opaque (`SpColors.KeyFill` slate +
  white ink on the glass, `KeyFillLight` + `OnFill` on dark bars / the plate via `LocalOnDark`); danger keys
  `DangerKey`; segment tracks `KeyFillLight`. Contrast scripts: `tools/contrast_fixed.py`, `tools/contrast_shots.py`.
  On the 6.1.0 emulator turn the head with the harness right-button drag (0.044 deg / px; `pico-cli camera move` needs
  `pvrtracking_inject`, missing on this image).

- Window model: one Planar `WindowContainer` (560 x 480 dp), native TabBar 窗 / 看光 / 日照图 / 设置 (settings last with
  退出应用 + in-page confirm; exit order: save, release, close window, closeStage, finishAndRemoveTask, exitProcess).
  The Sun page has the system Toolbar [现在] [▶ 扫一天 / ■ 停]. No self-made drag bar (D21), no SegmentControl page
  switching (D8). Every `openWindowContainer` goes through `WindowCtl` + `LevelGate` (|pitch| <= 10 deg for 300 ms; 5 s
  without head pose / 15 s without Stage frames: open anyway).
- Stage plate (`stagePlate`, the only attachment panel): S04 corner hint + [重来] [取消] (locked 0.75 m ahead, 20 deg
  under the line of sight, re-posed once when the head turned > 45 deg for 0.5 s); gate hint / head-sample hint / lost
  on the line of sight (pitch clamped +-40 deg); [打开面板] when the user closed the window; "面板马上回来" after S04.
- Input (D1 / D24): the controller ray (or the debug stand-in ray) x room is computed analytically in `Room.hit`; there
  are no colliders on planes. The trigger / pinch arrives as the system tap: on a marker's 12 cm sphere collider, or,
  only in S04 / S11, on the invisible 9 m tap catcher shell; with a controller the tap's point is recomputed from the
  ray, without one from the eyes through the pinched point. B / Y cancels S04. Head pose is used only for the level gate,
  the head sample (virtual room "移到我面前", marker "移到面前") and yaw-only plates / the Stage plate.
- Room (D7): plane detection starts with the Stage; a WALL within 5 s -> real room (missing walls from the virtual
  box, `room_real_part`), else the virtual room 4 x 4 x 2.7 m around the head (user 1 m behind its centre, 0.42 m table
  0.3 m ahead). Settings: W / D / H steppers, 左转 / 右转 5 deg, 移到我面前 (head sample), 改用虚拟房间 / 改回扫描. Windows
  on a virtual wall follow it (`WallSlide`). Plane changes rebuild the room at most every 2 s.
- Windows (<= 3): two corners on the same wall, sides >= 0.3 m (`SunWindow.fromCorners`), else the plate says why;
  [放一扇标准窗] = 1.5 x 1.5 m, sill 0.9 m on the wall the ray points at, else the room's front wall. While marking the
  main window is closed and reopened through the gate after ON_DESTROY.
- Facing: the facing row is bound to window 1 and sets `northYawDeg = Compass.northYawFor(w1.outward, bearing)`; north
  lives on the project, so deleting / re-marking window 1 keeps it. Patches and hours appear only once the facing is set.
- Light: `SunLight.frame` per (date, minute) on the `sun-light` thread (<= 10 Hz); per surface a 4 cm raster painted into
  one bitmap on the shared unit square (4 mm off the surface): patch #FFCF7A at the brightness alpha (淡 30 / 中 45 / 亮
  60 %), rim #FFE9B8, overlaps capped (60 % at 中); with heat on (or the sweep trail) the hours colours under it.
- Hours: `HoursMap.compute` + `MarkerStats.withMonths` per marker on the `sun-hours` thread (300 ms debounce). Sweep:
  `SweepClock` (8 s, never skipping a step) + `HoursMap.Accumulator` on `sun-sweep`; start clears the trail and turns
  heat off, a slider touch stops it (trail kept until the next slider move), the end turns heat on (S09) + ding.
- Markers (<= 8): [放一个标记] -> ghost ring on floor / upward faces (red cross elsewhere) -> trigger places + selects
  (S10); within 12 cm selects the existing one. Ring by verdict (ok solid green, meh dashed amber, no broken coral,
  neutral grey), plate 20 cm above (yaw only). Drag with the ray at the picked-up level, release lands on the highest
  upward face under it. Plate, list row and detail read the same `HoursResult.stats`.
- Persistence: `files/project.json` (500 ms after a change) + one world anchor per window / marker. Restore: every item
  with a found anchor moves with it, the rest follow the yaw / shift of a found one (S18 partial bar), none -> S17.
  Prefs (SharedPreferences): language, brightness, sound, followNow, virtual room size, firstRunDone.
- Copy in `res/values` / `res/values-zh`, read through `rs()` (live language switch); 3D plates use the same resources.
  After changing zh copy run the subset tool (below).

## Build

```bash
export JAVA_HOME="C:/Users/Admin/.pico/primer-cli/jdk/jdk-21.0.12.1+1"; export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
cd /c/Users/Admin/Downloads/sun-patch && ./gradlew --no-daemon -q assembleDebug
```

`local.properties`: `sdk.dir=C:/Users/Admin/Android/Sdk`. APK: `app/build/outputs/apk/debug/app-debug.apk`.
Gates: `python -I ../spatial-app-factory/bin/ui_gates.py --dir .` (must be 0 FAIL).
CJK font subset (this machine has no `NotoSansSC-VF.ttf`; the memory-palace subset holds 3921 characters with the wght
axis): `python -I tools/subset_cjk_font.py --source ../memory-palace/app/src/main/res/font/noto_sans_sc_subset.ttf`.
Install / launch: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`,
`adb -s <serial> shell monkey -p io.embodit.sunpatch -c android.intent.category.LAUNCHER 1`.
Log: `adb -s <serial> logcat -s SunPatch` (prefixes `STATE SUN PATCH HOURS MARK WIN ROOM ANCHOR WINDOW GATE PLANE(S)
FRAME CMD DUMP TAP DRAG SOUND SAVE OVERFLOW`).

## Debug broadcast (debug builds only)

`adb -s <serial> shell "am broadcast -a io.embodit.sunpatch.DEBUG -p io.embodit.sunpatch --es cmd 'NAME ARGS'"`
(the argument may also come as `--es s ARGS`). Every command goes through the same events / inputs as real use.

| cmd | arguments | effect |
|---|---|---|
| `goto` | `S00`..`S19`, `S07b`, `S11b` | walks there with user events (virtual room, standard front window, facing 180, a marker 1.2 m in front of window 1, …) |
| `room` | `virtual` \| `scan` \| `size w,d,h` \| `yaw deg` \| `recenter` | 改用虚拟房间 / 改回扫描 / steppers to that size / turn / 移到我面前 (head sample) |
| `ray` | `x,y,z[,ms]` | stand-in controller ray from the head to that Stage point (default 60 s): reticle, ghost, drags follow it |
| `trigger` | | the tap a trigger press delivers along that ray (corner / marker / select) |
| `corner` | `x,y,z` | `ray` to that point + `trigger` |
| `stdwindow` | `front` \| `left` \| `right` \| `back` | ray at that wall's middle, then [放一扇标准窗] |
| `facing` | `deg` | window 1 faces that bearing (sets north) |
| `city` | `key` (e.g. `beijing`, `london`, `newyork`) or a name | picks the city |
| `time` / `month` / `today` / `now` | `hh:mm` / `m` | the sliders, [今天], Toolbar [现在] |
| `clock` | `yyyy-mm-ddThh:mm[Z]` \| `off` | fixes "now" (city local, or UTC with Z) and presses [今天] |
| `follow` | `on` \| `off` | 跟着现在走 |
| `sweep` | `[hold s]` \| `stop` | ▶ 扫一天 (hold: the clock stops at s seconds) / ■ 停 |
| `heat` | `on` \| `off` | 画在地上 |
| `marker` | `add x,z [kind]` \| `n select` \| `n want more/less/look` \| `n kind k` \| `n delete` \| `n front` | place via ray + trigger / list actions |
| `drag` / `release` | `n dx dz [hold]` | drag marker n by (dx, dz) through the drag path; `hold` keeps S11b until `release` |
| `lang` | `zh` \| `en` \| `system` | language |
| `lost` | `on` \| `off` | tracking lost (S14) |
| `mismatch` | | anchors not found -> restore -> S17 |
| `noPermission` | `[off]` | S16 (virtual room only, no anchors) |
| `anchor` | `failAll` \| `ok` | the next restore finds no anchors |
| `pitch` / `yawRate` | `deg` / `deg_s` \| `off` | head pitch / turn rate the gate and the head sampler read |
| `bar` | `long` \| `normal` | inline bars stay 60 s |
| `tab` | `window` \| `sun` \| `hours` \| `settings` | TabBar |
| `recall` / `fit [reset]` / `where` | | 叫回面前 / OVERFLOW summary / head, plate, ray |
| `dump` | | DUMP lines + `files/dump.txt`: state, window, city / north, room, sun alt / az / rise / set / conclusion, next entry, window corners, every (window, surface) patch quad + area + distance from the window wall (+ top / bottom above floor on walls), shadow centroids + horizontal offset from the occluder, lit area per surface, hours area4 / cells4 (+ trail cells4), sweep, markers (hours, first, last, verdict, anchor) |

## Screenshot list (QA, design states)

Setup for most: `goto S07` (virtual room, standard front window, Beijing, facing 180, June 12:00).

- S00 cold start within 0.5 s (plate spinner) · S01 `goto S01` · S02 `goto S02` within 1 s · S03 `room virtual`, then
  `room size 6,4,2.8`
- S04 `goto S04`, `ray` at the front wall (cross), `corner` 1 (preview rectangle), `ray` at the floor (red cross)
- S05 `stdwindow front` · S06 `facing 180`, `city beijing` (+ one with the city list open)
- S07 `time 12:00`, `month 6`; `month 12` (patch climbs the back wall); `month 12` table shadow shifted
- S07b `time 03:00`; north window: `facing 0`, `time 12:00`
- S08 `sweep hold 4`; then `sweep` to the end (S09 opens by itself)
- S09 `heat on`, `month 6`; `month 12`
- S10 `marker add 0,-1.5`, `marker 1 select`; `marker 1 want less`
- S11 `goto S11`, `ray` at the floor; `ray` at a wall (red cross) · S11b `drag 1 0.5 0 hold`
- S12 settings; `lang en`; virtual room section · S13 `goto S13` · S14 `lost on` · S15 `goto S15`
- S16 `noPermission` · S17 `mismatch` · S18 standard window + marker, force-stop, relaunch
- S19 `pitch -30`, `goto S19` (hint); × on the window (open panel chip)

mustPass dump checks: `room virtual`, `stdwindow front`, `city beijing`, `facing 180`, `time 12:00`, `month 6` -> floor
patch `fromWall=0.265..0.707`; `month 12` -> a patch on `virtual-wall-0` (the back wall) with `top` 0.40; `room size
4,5,2.7` -> none there. `city london` + `clock 2026-06-21T13:00` -> alt 61.9; `city newyork` + `clock 2026-12-21T12:00`
-> alt 25.9.

## Known limits (v0.1.0)

- Not run on an emulator or headset yet (all emulators were busy at build time): every runtime claim above is from code
  and the design, not from screenshots.
- R2-2 (re-projecting a window from a virtual wall onto a real wall found later) is not implemented: a window stays on
  its virtual wall; settings offers 改回扫描.
- The north follows the yaw of any restored anchor (no separate base anchor, design R2-5).
- The ring verdict has the +-10 min hysteresis but not the 1 s text hold; pinch long-press to cancel S04 is not wired
  (B / Y and the plate's [取消] are).
