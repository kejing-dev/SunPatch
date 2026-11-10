package io.embodit.sunpatch.platform

import android.content.Context
import io.embodit.sunpatch.domain.AnchorPose
import io.embodit.sunpatch.domain.Cities
import io.embodit.sunpatch.domain.City
import io.embodit.sunpatch.domain.MarkerKind
import io.embodit.sunpatch.domain.MarkerRec
import io.embodit.sunpatch.domain.Quat4
import io.embodit.sunpatch.domain.SunProject
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.domain.VirtualRoom
import io.embodit.sunpatch.domain.Want
import io.embodit.sunpatch.domain.WindowRec
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class Lang { SYSTEM, ZH, EN }

/** Patch brightness 淡 / 中 / 亮 (design settings): the patch alpha for one window. */
enum class Brightness(val alpha: Float) { SOFT(0.30f), MEDIUM(0.45f), BRIGHT(0.60f) }

/** Device settings (design `dataModel.settings`). The virtual room's size is kept, its place is not. */
data class Prefs(
    val lang: Lang = Lang.SYSTEM,
    val brightness: Brightness = Brightness.MEDIUM,
    val sound: Boolean = true,
    val followNow: Boolean = false,
    val roomW: Float = VirtualRoom.DEFAULT_WIDTH,
    val roomD: Float = VirtualRoom.DEFAULT_DEPTH,
    val roomH: Float = VirtualRoom.DEFAULT_HEIGHT,
    val firstRunDone: Boolean = false,
)

/** SharedPreferences for [Prefs] and `files/project.json` for the [SunProject] (design `dataModel.storage`). */
class Store(context: Context) {
    private val p = context.getSharedPreferences("sun_patch", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "project.json")

    fun prefs(): Prefs {
        val d = Prefs()
        fun <T : Enum<T>> en(key: String, def: T, values: Array<T>): T = values.firstOrNull { it.name == p.getString(key, def.name) } ?: def
        return Prefs(
            lang = en("lang", d.lang, Lang.values()),
            brightness = en("brightness", d.brightness, Brightness.values()),
            sound = p.getBoolean("sound", d.sound),
            followNow = p.getBoolean("followNow", d.followNow),
            roomW = p.getFloat("roomW", d.roomW),
            roomD = p.getFloat("roomD", d.roomD),
            roomH = p.getFloat("roomH", d.roomH),
            firstRunDone = p.getBoolean("firstRunDone", d.firstRunDone),
        )
    }

    fun savePrefs(v: Prefs) {
        p.edit()
            .putString("lang", v.lang.name).putString("brightness", v.brightness.name).putBoolean("sound", v.sound)
            .putBoolean("followNow", v.followNow).putFloat("roomW", v.roomW).putFloat("roomD", v.roomD).putFloat("roomH", v.roomH)
            .putBoolean("firstRunDone", v.firstRunDone)
            .apply()
    }

    fun loadProject(): SunProject? = runCatching { if (file.exists()) decode(file.readText()) else null }.onFailure { SpLog.w("SAVE load failed: $it") }.getOrNull()

    fun saveProject(project: SunProject?) {
        if (project == null) {
            file.delete()
            return
        }
        runCatching {
            val tmp = File(file.parentFile, "project.json.tmp")
            tmp.writeText(encode(project))
            if (!tmp.renameTo(file)) {
                file.writeText(encode(project))
                tmp.delete()
            }
        }.onFailure { SpLog.w("SAVE failed: $it") }
    }

    companion object {
        private fun v3(v: Vec3) = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())

        private fun v3(a: JSONArray) = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

        private fun pose(p: AnchorPose) = JSONObject().put("p", v3(p.position)).put("q", JSONArray().put(p.rotation.x.toDouble()).put(p.rotation.y.toDouble()).put(p.rotation.z.toDouble()).put(p.rotation.w.toDouble()))

        private fun pose(o: JSONObject?): AnchorPose? {
            o ?: return null
            val q = o.getJSONArray("q")
            return AnchorPose(v3(o.getJSONArray("p")), Quat4(q.getDouble(0).toFloat(), q.getDouble(1).toFloat(), q.getDouble(2).toFloat(), q.getDouble(3).toFloat()))
        }

        fun encode(pr: SunProject): String {
            val o = JSONObject()
            o.put("version", 1)
            o.put("city", JSONObject().put("key", pr.city.key).put("lat", pr.city.lat).put("lon", pr.city.lon).put("zoneId", pr.city.zoneId))
            o.put("northYawDeg", pr.northYawDeg.toDouble())
            o.put("northSet", pr.northSet)
            o.put("windows", JSONArray().apply {
                pr.windows.forEach { w ->
                    put(JSONObject().put("id", w.id).put("no", w.no).put("wallId", w.wallId ?: "").put("corners", JSONArray().apply { w.corners.forEach { put(v3(it)) } })
                        .put("outward", v3(w.outward)).put("anchorId", w.anchorId ?: "").apply { w.anchorPose?.let { put("anchorPose", pose(it)) } })
                }
            })
            o.put("markers", JSONArray().apply {
                pr.markers.forEach { m ->
                    put(JSONObject().put("id", m.id).put("no", m.no).put("kind", m.kind.name).put("want", m.want.name).put("pos", v3(m.pos))
                        .put("anchorId", m.anchorId ?: "").apply { m.anchorPose?.let { put("anchorPose", pose(it)) } })
                }
            })
            o.put("updatedAt", System.currentTimeMillis())
            return o.toString(1)
        }

        fun decode(text: String): SunProject {
            val o = JSONObject(text)
            val c = o.optJSONObject("city")
            val city: City = c?.let { Cities.byKey(it.optString("key")) ?: Cities.custom(it.getDouble("lat"), it.getDouble("lon"), it.getString("zoneId")) } ?: Cities.BEIJING
            val ws = o.optJSONArray("windows") ?: JSONArray()
            val windows = (0 until ws.length()).map { i ->
                val w = ws.getJSONObject(i)
                val cs = w.getJSONArray("corners")
                WindowRec(
                    id = w.getString("id"),
                    no = w.optInt("no", i + 1),
                    wallId = w.optString("wallId").ifEmpty { null },
                    corners = (0 until cs.length()).map { v3(cs.getJSONArray(it)) },
                    outward = v3(w.getJSONArray("outward")),
                    anchorId = w.optString("anchorId").ifEmpty { null },
                    anchorPose = pose(w.optJSONObject("anchorPose")),
                )
            }
            val ms = o.optJSONArray("markers") ?: JSONArray()
            val markers = (0 until ms.length()).map { i ->
                val m = ms.getJSONObject(i)
                MarkerRec(
                    id = m.getString("id"),
                    no = m.optInt("no", i + 1),
                    kind = MarkerKind.values().firstOrNull { it.name == m.optString("kind") } ?: MarkerKind.OTHER,
                    want = Want.values().firstOrNull { it.name == m.optString("want") } ?: Want.LOOK,
                    pos = v3(m.getJSONArray("pos")),
                    anchorId = m.optString("anchorId").ifEmpty { null },
                    anchorPose = pose(m.optJSONObject("anchorPose")),
                )
            }
            return SunProject(city, o.optDouble("northYawDeg", 0.0).toFloat(), o.optBoolean("northSet", false), windows, markers)
        }
    }
}
