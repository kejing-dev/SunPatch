package io.embodit.sunpatch.ui.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.sunpatch.domain.Billboard
import io.embodit.sunpatch.domain.LayerQuad
import io.embodit.sunpatch.domain.Quat4
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.ui.theme.SpColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. Every entity hangs below the stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Enables / disables only on change (a write is a runtime command even when the value is the same). */
internal fun Entity.on(v: Boolean) {
    if (enabled != v) enabled = v
}

/**
 * GPU-side resource creation (textures) paced over frames: each call blocks the main thread until the render thread
 * takes it (light-planner: a burst of thirty stalls the idle emulator past the input timeout). One task per owner: a
 * newer one replaces the pending one.
 */
internal class WorkQueue {
    private val tasks = LinkedHashMap<Any, () -> Unit>()

    fun enqueue(owner: Any, task: () -> Unit) {
        tasks.remove(owner)
        tasks[owner] = task
    }

    fun cancel(owner: Any) {
        tasks.remove(owner)
    }

    fun run(n: Int): Int {
        var left = n
        while (left > 0 && tasks.isNotEmpty()) {
            val key = tasks.keys.first()
            val task = tasks.remove(key) ?: continue
            runCatching(task).onFailure { SpLog.e("FRAME work task failed: $it", it) }
            left--
        }
        return tasks.size
    }

    val pending: Int
        get() = tasks.size

    fun clear() = tasks.clear()
}

/** An invisible box collider (the tap catcher's slabs, design `input.rule`: only the app's own colliders). */
internal fun boxCollision(size: Vec3) = CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))), physicsMaterial = PhysicsMaterialResource())

/** The marker's invisible 12 cm sphere collider (design `spatialLayout.marker.ring`). */
internal fun sphereCollision(radius: Float) = CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(radius)), physicsMaterial = PhysicsMaterialResource())

/** Resources shared by entities that come and go: made global and closed when the scene goes away. */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun drop(resource: Resource) {
        if (owned.remove(resource)) runCatching { resource.close() }
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Scene materials (design `palette.brandTokens`): unlit, tone mapping off, nothing re-lit (passthrough room). */
internal class SpMaterials(private val shared: SharedResources) {
    val frame: Material = unlit(SpColors.WindowFrame)
    val sunDisc: Material = unlit(SpColors.SunDisc)
    val sunRay: Material = sheer(SpColors.SunDisc, 0.9f)
    val reticle: Material = sheer(SpColors.White, 0.9f)
    val reticleDash: Material = sheer(SpColors.White, 0.75f)
    val bad: Material = sheer(SpColors.MarkerNo, 0.95f)
    val ghost: Material = sheer(SpColors.White, 0.8f)
    val ringOk: Material = unlit(SpColors.MarkerOk)
    val ringMeh: Material = unlit(SpColors.MarkerMeh)
    val ringNo: Material = unlit(SpColors.MarkerNo)
    val ringNeutral: Material = unlit(SpColors.MarkerNeutral)
    val dot: Material = unlit(SpColors.White)
    val breath: Material = sheer(SpColors.Interaction, 0.7f)
    val roomLine: Material = sheer(SpColors.RoomLine, 0.55f)

    private fun unlit(color: Color): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through: the colour is opaque, the material's opacity carries the share. */
    private fun sheer(color: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /** Bitmap-driven: TRANSPARENT for the light layers (per-texel alpha), MASKED for the crisp text plates. */
    fun textured(soft: Boolean): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(if (soft) BlendingMode.TRANSPARENT else BlendingMode.MASKED).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                if (!soft) runCatching { setOpacityThreshold(0.5f) }
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(!soft)
            }
        )

    fun release(m: Material) = shared.drop(m)
}

/** Programmatic meshes (design `budget.meshes`: abstract indicators only, no models). */
internal object MeshFactory {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** A unit cube centred on the origin (the window corner blocks, scaled flat). */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val faces = listOf(Vector3(1f, 0f, 0f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, -1f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 0f, -1f))
        for (n in faces) {
            val nn = Vec3(n.x, n.y, n.z)
            val u = (if (abs(n.y) > 0.5f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)).rejectFrom(nn).normalized()
            val v = nn.cross(u)
            val base = positions.size
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = (nn + u * a + v * b) * 0.5f
                positions += Vector3(p.x, p.y, p.z)
                normals += n
            }
            indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** Unit square in the local XZ plane facing +Y, corners / UVs from [LayerQuad] (the light layers). */
    fun unitSquare(name: String): MeshResource {
        val positions = LayerQuad.CORNERS.map { it.sdk() }
        val uv = LayerQuad.uv().map { (u, v) -> Vector2(u, v) }
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 2, 1, 0, 3, 2), normals = List(4) { UP }, uv0 = uv)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Unit quad in the local XY plane facing +Z (text plates), corners / UVs from [Billboard] (v = 1 on top). */
    fun unitQuad(name: String): MeshResource {
        val positions = Billboard.CORNERS.map { it.sdk() }
        val uv = Billboard.UV.map { (u, v) -> Vector2(u, v) }
        val model = MeshModel(positions = positions, triangleIndices = Billboard.TRIANGLES, normals = List(4) { FRONT }, uv0 = uv)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.001f)), name = name)
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z, cut into [dashes] (0 = solid): frame lines, rays, room edges. */
    fun unitBand(name: String, dashes: Int = 0, fill: Float = 0.55f): MeshResource {
        val rects = if (dashes <= 0) listOf(0f to 1f) else List(dashes) { i -> val p = 1f / dashes; (i * p + p * (1f - fill) / 2f) to (i * p + p * (1f + fill) / 2f) }
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for ((x0, x1) in rects) {
            val b = positions.size
            positions += listOf(Vector3(x0, -0.5f, 0f), Vector3(x1, -0.5f, 0f), Vector3(x1, 0.5f, 0f), Vector3(x0, 0.5f, 0f))
            indices += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { FRONT })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)), name = name)
    }

    /** Unit disc (radius 0.5) in the local XZ plane facing +Y: sun disc, centre dots. */
    fun unitDisc(name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * 0.5).toFloat(), 0f, (sin(a) * 0.5).toFloat())
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Flat ring (inner [inner], outer 0.5) in the local XZ plane facing +Y; [dashes] > 0 cuts it (dashed / broken rings). */
    fun unitRing(name: String, inner: Float, segments: Int = 48, dashes: Int = 0, on: Int = 1, period: Int = 2): MeshResource {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0 until segments) {
            if (dashes > 0 && ((i * dashes / segments) % period) >= on) continue
            val s = 2 * PI * i / segments
            val e = 2 * PI * (i + 1) / segments
            val b = positions.size
            positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
            positions += Vector3((cos(s) * 0.5).toFloat(), 0f, (sin(s) * 0.5).toFloat())
            positions += Vector3((cos(e) * 0.5).toFloat(), 0f, (sin(e) * 0.5).toFloat())
            positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
            indices += listOf(b, b + 2, b + 1, b, b + 3, b + 2)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }
}

/** The shared meshes, built one per frame by the scene (design `budget.meshes` ~14). */
internal class SpMeshes(private val shared: SharedResources) {
    lateinit var cube: MeshResource
    lateinit var square: MeshResource
    lateinit var quad: MeshResource
    lateinit var band: MeshResource
    lateinit var dashes: MeshResource
    lateinit var disc: MeshResource
    lateinit var ring: MeshResource
    lateinit var ringDashed: MeshResource
    lateinit var ringBroken: MeshResource
    lateinit var ringThin: MeshResource

    val steps: List<() -> Unit> =
        listOf(
            { cube = shared.keep(MeshFactory.unitCube("unitCube")) },
            { square = shared.keep(MeshFactory.unitSquare("unitSquare")) },
            { quad = shared.keep(MeshFactory.unitQuad("unitQuad")) },
            { band = shared.keep(MeshFactory.unitBand("unitBand")) },
            { dashes = shared.keep(MeshFactory.unitBand("unitDashes", dashes = 10)) },
            { disc = shared.keep(MeshFactory.unitDisc("unitDisc")) },
            // ring 16 cm wide 1.2 cm: inner = 0.5 - 0.012 / 0.16 = 0.425 (design `spatialLayout.marker.ring`)
            { ring = shared.keep(MeshFactory.unitRing("unitRing", inner = 0.425f)) },
            { ringDashed = shared.keep(MeshFactory.unitRing("unitRingDashed", inner = 0.425f, dashes = 16)) },
            { ringBroken = shared.keep(MeshFactory.unitRing("unitRingBroken", inner = 0.425f, dashes = 12, on = 3, period = 4)) },
            { ringThin = shared.keep(MeshFactory.unitRing("unitRingThin", inner = 0.45f)) },
        )
}

/** A mesh entity on a shared unit mesh: placed by centre, size and rotation. */
internal class Part(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
    }

    fun place(center: Vec3, size: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        entity.setLocal(position = center, rotation = rotation, scale = size)
        show(true)
    }

    /** A disc / ring / square turned so that its face normal (+Y) points along [normal]. */
    fun placeFacing(center: Vec3, size: Vec3, normal: Vec3) {
        place(center, size, Quat4.fromTo(Vec3.UP, normal.normalized()))
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }

    val shown: Boolean
        get() = on

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** A straight strip between two points with its face turned along a normal (lines, dashes, crosses). */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** A pool of strips (room edges, preview rectangle). */
internal class LinePool(private val root: Entity, private val mesh: MeshResource, private val material: Material, private val name: String) {
    private val strips = ArrayList<Strip>()
    private var used = 0

    fun begin() {
        used = 0
    }

    fun draw(from: Vec3, to: Vec3, width: Float, face: Vec3) {
        if (strips.size <= used) strips += Strip(root, mesh, material, "$name:$used")
        strips[used++].draw(from, to, width, face)
    }

    fun end() {
        for (i in used until strips.size) strips[i].hide()
    }

    fun hideAll() {
        begin()
        end()
    }

    fun destroy() = strips.forEach { it.destroy() }
}

/**
 * A bitmap-textured quad in the room (window label plates, marker plates, reticle words), [heightM] per line of
 * [Plates.LINE_PX] pixels, turned about the vertical only (D10, [Billboard]). The bitmap is re-made only when its key changes.
 */
internal class TextPlate(parent: Entity, meshes: SpMeshes, private val materials: SpMaterials, private val queue: WorkQueue, name: String) {
    private val material: UnlitMaterial = materials.textured(soft = false)
    private val part = Part(parent, meshes.quad, material, name)
    private var texture: TextureResource? = null
    private var key: String? = null
    private var aspect = 1f
    private var lines = 1f
    private var heightM = 0.03f
    private var center = Vec3.ZERO
    var ready = false
        private set

    init {
        part.show(false)
    }

    fun content(key: String, paint: () -> Bitmap) {
        if (key == this.key) return
        this.key = key
        queue.enqueue(this) {
            val bitmap = paint()
            aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            lines = bitmap.height.toFloat() / Plates.LINE_PX
            val old = texture
            val tex = runCatching { TextureResource.create(bitmap) }.onFailure { SpLog.e("FRAME plate texture failed: $it", it) }.getOrNull()
            if (tex != null) {
                material.setBaseColorTexture(tex)
                texture = tex
                ready = true
                old?.let { runCatching { it.close() } }
            }
            bitmap.recycle()
        }
    }

    fun place(center: Vec3, heightM: Float) {
        this.center = center
        this.heightM = heightM
    }

    /** Yaw towards [viewer], no pitch, no roll (a wall plate passes a point straight out of the wall). */
    fun face(viewer: Vec3) {
        if (!ready) return
        val h = heightM * lines
        part.place(center, Vec3(h * aspect, h, 1f), Billboard.yawTowards(center, viewer))
    }

    fun hide() = part.show(false)

    val shown: Boolean
        get() = part.shown

    fun destroy() {
        queue.cancel(this)
        part.destroy()
        texture?.let { runCatching { it.close() } }
        texture = null
        materials.release(material)
    }
}
