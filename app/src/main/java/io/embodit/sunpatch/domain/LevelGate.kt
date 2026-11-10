package io.embodit.sunpatch.domain

import kotlin.math.abs

/**
 * D9 for the system main window (QA #4 q4-3, factory native-window-move §7.9): the system opens a Planar window on the
 * line of sight and square to it, head pitch included, and SDK 6.1 has no call to read or set a window's pose
 * (`volumeAlignment` is Volumetric only, `windowConstraints` is size only). So the app asks for the window only while
 * the head is level: |pitch| <= [maxPitchDeg] held for [dwellMs]. Until then the Stage shows "平视前方". When the
 * frames keep coming without a valid head pose for [noHeadMs] the window is opened anyway (a window is better than
 * none); that time counts from the first such frame, not from the ask (the Stage's frames start ~6 s after launch on
 * the headset, the ask comes at once).
 *
 * QA #1 q1-3: the window also waits while it would open in front of a marked window ([aside] false, see [PanelCone]);
 * the Stage then says which way to turn. A head that is not tracked skips that wait like the pitch one.
 *
 * Pure: the caller feeds the time and the head pitch every frame; [step] says when to open.
 */
class LevelGate(
    val maxPitchDeg: Float = 10f,
    val dwellMs: Long = 300L,
    val noHeadMs: Long = 5000L,
) {
    /** Why the window was asked for, while it waits for a level head; null when nothing waits. */
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L

    val waiting: Boolean
        get() = pending != null

    /** Ask for the window (first open, chip, recall). A second ask while waiting keeps the first wait time. */
    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() {
        pending = null
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    /** How long the current ask has waited (0 when nothing waits). */
    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /**
     * One frame: [pitchDeg] is the head pitch (+ up), null while there is no valid head pose; [aside] false while a
     * window opened now would cover a marked window. Returns the reason to open the window now, or null to keep waiting.
     */
    fun step(now: Long, pitchDeg: Float?, aside: Boolean = true): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg || !aside) {
            levelSinceMs = -1L
            return null
        }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel()
        return why
    }

    /** The "平视前方" hint: something waits and the head is tracked but not level. */
    fun hintWanted(pitchDeg: Float?): Boolean = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
}
