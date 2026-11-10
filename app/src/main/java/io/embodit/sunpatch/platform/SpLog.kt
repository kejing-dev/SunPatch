package io.embodit.sunpatch.platform

import android.util.Log
import io.embodit.sunpatch.domain.Quat4
import io.embodit.sunpatch.domain.Vec3
import java.util.Locale

/**
 * One line per event under the tag `SunPatch`, with the stable prefixes of design `qaHooks`:
 * `STATE SUN PATCH HOURS MARK WIN ROOM ANCHOR WINDOW GATE PLANE FRAME CMD DUMP` (plus `TAP DRAG SOUND OVERFLOW`).
 */
object SpLog {
    const val TAG = "SunPatch"

    fun i(line: String) {
        Log.i(TAG, line)
    }

    fun w(line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
    }

    fun e(line: String, error: Throwable? = null) {
        Log.e(TAG, line, error)
    }

    fun pos(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z)

    fun quat(q: Quat4?): String = if (q == null) "null" else String.format(Locale.US, "q(%.4f, %.4f, %.4f, %.4f)", q.x, q.y, q.z, q.w)

    fun f(format: String, vararg args: Any?): String = String.format(Locale.US, format, *args)
}
