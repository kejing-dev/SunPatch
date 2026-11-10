package io.embodit.sunpatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.sunpatch.platform.SpLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.sunpatch.DEBUG`); see
 * [DebugCommands] and AGENTS.md for the command list.
 *
 *   adb -s emulator-5556 shell "am broadcast -a io.embodit.sunpatch.DEBUG -p io.embodit.sunpatch --es cmd 'time 12:00'"
 *
 * Extras: `cmd` = command name; its argument may follow the name inside `cmd` (`--es cmd 'fly gate 2'`) or come as `s`.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0]
        val s = intent.getStringExtra("s") ?: parts.getOrNull(1)
        val a = intent.getStringExtra("a")
        SpLog.i("CMD recv '$cmd' s='$s'")
        // broadcasts arrive on the main thread, which is where the controller and the scene live
        // a malformed command (e.g. `lesson` with no id) logs instead of taking the app down
        runCatching { DebugCommands.run(context.applicationContext, cmd, s, a) }.onFailure { SpLog.w("CMD $cmd failed: $it") }
    }
}
