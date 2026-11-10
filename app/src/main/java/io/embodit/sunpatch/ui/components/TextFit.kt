package io.embodit.sunpatch.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.text.TextLayoutResult
import io.embodit.sunpatch.platform.SpLog

/**
 * Fixed-line text guard (prompts/build.md pre-QA rule, profile D13): every Text on a panel reports its layout here. A
 * text that still does not fit its lines / box [SETTLE_MS] after it was laid out logs `OVERFLOW <panel>/<control>
 * text="…"` once per text; one that fits again by then (a single re-layout frame while the language switches) logs
 * `OVERFLOW_TRANSIENT` instead, so it is visible but not a truncation the user can read. The build station and QA run every
 * state in the longest language with the widest dynamic chips and expect zero OVERFLOW lines.
 */
object TextFit {
    const val SETTLE_MS = 1500L

    @Volatile
    var checked = 0L
        private set

    private class Last(var text: String, var over: Boolean, var w: Int, var h: Int, var lines: Int, var widthOver: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val last = HashMap<String, Last>()
    private val pending = HashSet<String>()
    private val seen = HashSet<String>()
    val overflowed = LinkedHashSet<String>()
    var transients = 0
        private set

    fun report(name: String, r: TextLayoutResult) {
        checked++
        val text = r.layoutInput.text.text
        val over = r.hasVisualOverflow
        val l = last.getOrPut(name) { Last(text, over, 0, 0, 0, false) }
        l.text = text
        l.over = over
        l.w = r.size.width
        l.h = r.size.height
        l.lines = r.lineCount
        l.widthOver = r.didOverflowWidth
        if (!over || !pending.add(name)) return
        main.postDelayed({ settle(name, text) }, SETTLE_MS)
    }

    private fun settle(name: String, text: String) {
        pending.remove(name)
        val l = last[name] ?: return
        if (l.over && l.text == text) {
            val key = "$name|$text"
            if (!seen.add(key)) return
            overflowed += key
            SpLog.w("OVERFLOW $name text=\"$text\" lines=${l.lines} w=${l.w} h=${l.h} widthOver=${l.widthOver}")
        } else {
            transients++
            SpLog.i("OVERFLOW_TRANSIENT $name text=\"$text\" (fits after re-layout)")
        }
    }

    /** Debug `fit` / `fit reset`: how many layouts were checked and which texts stayed cut. */
    fun summary(): String = "FIT checked=$checked overflow=${overflowed.size} transient=$transients" + if (overflowed.isEmpty()) "" else " " + overflowed.joinToString(" ; ")

    fun reset() {
        seen.clear()
        overflowed.clear()
        transients = 0
        checked = 0
    }
}

internal fun fits(name: String): (TextLayoutResult) -> Unit = { r -> TextFit.report(name, r) }
