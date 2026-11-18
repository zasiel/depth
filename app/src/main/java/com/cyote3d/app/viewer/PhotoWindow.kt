package com.cyote3d.app.viewer

import android.net.Uri
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.AddressMode
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.Filter
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.SortOrder
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Transform
import kotlin.math.max
import kotlin.math.sin

/**
 * The 3D window: one entity whose mesh is the two-layer photo (background + foreground) rendered
 * by the custom shaders in app/src/shaders. See [PhotoBuilder] for the geometry layout and
 * app.js for the projection maths the shaders port.
 */
class PhotoWindow(private val activity: AppSystemActivity) {
  companion object {
    private const val TAG = "Cyote3D"
    private const val MESH_BASE = "mesh://cyote3d/photo"

    /** Window pose: centred 1.6 m in front of the floor origin at roughly eye height. */
    private val WINDOW_POSITION = Vector3(0f, 1.35f, -1.6f)
    private const val MAX_HEIGHT_M = 1.0f
    private const val MAX_WIDTH_M = 1.5f

    /** Nominal eye distance in window widths (WIN_D in app.js). */
    private const val WIN_D = 1.15f

    /** Sway amplitude in window widths at amount 0.5 (SWAY_MAX * 0.5 in app.js). */
    private const val SWAY_AMP = 0.07f * 0.5f
    private const val SWAY_PERIOD_S = 6f

    /** Background is pushed fractionally deeper instead of using polygon offset. */
    private const val BG_BIAS = 1.002f

    private val dataSampler =
        SamplerConfig(
            minFilter = Filter.NEAREST,
            magFilter = Filter.NEAREST,
            mipmapMode = Filter.NEAREST,
            addressModeU = AddressMode.CLAMP_TO_EDGE,
            addressModeV = AddressMode.CLAMP_TO_EDGE,
        )
    private val linearSampler =
        SamplerConfig(
            addressModeU = AddressMode.CLAMP_TO_EDGE,
            addressModeV = AddressMode.CLAMP_TO_EDGE,
        )
  }

  /** Everything that belongs to the photo currently on screen. Destroyed as a unit. */
  private class Live(
      val entity: Entity,
      val mesh: SceneMesh,
      val fgMat: SceneMaterial,
      val bgMat: SceneMaterial,
      val textures: List<SceneTexture>,
      val built: BuiltPhoto,
  )

  private var live: Live? = null
  private var meshSeq = 0
  private val pendingMeshes = HashMap<Int, SceneMesh>()
  private var registered = false

  var swayOn: Boolean = true
  var strength: Float = 0.8f

  /** Wall-clock seconds, advanced by [tick]. */
  private var time = 0f

  private fun ensureMeshCreator() {
    if (registered) return
    registered = true
    activity.registerMeshCreator(MESH_BASE) { _, uri ->
      val id = uri.getQueryParameter("id")?.toIntOrNull() ?: -1
      pendingMeshes.remove(id) ?: error("no pending photo mesh for $id")
    }
  }

  /** Must run on the main (render) thread. Replaces whatever photo is showing. */
  fun show(built: BuiltPhoto) {
    ensureMeshCreator()
    val old = live
    live = null
    if (old != null) release(old)

    val item = built.item
    val fgColor = SceneTexture.loadFile("${item.dir}/fg.jpg", linearSampler)
    val bgColor = SceneTexture.loadFile("${item.dir}/bg.jpg", linearSampler)
    if (fgColor == null || bgColor == null) {
      Log.e(TAG, "missing colour textures for ${item.name}")
      return
    }
    val depthTex = SceneTexture(built.depthBitmap, dataSampler)
    val alphaTex = SceneTexture(built.alphaBitmap, linearSampler)
    // The bitmaps are only needed for the upload.
    built.recycleBitmaps()

    val bgMat =
        SceneMaterial.custom(
                "photo_bg",
                arrayOf(
                    SceneMaterialAttribute("geom", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("view", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("tear", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("colorMap", SceneMaterialDataType.Texture2D),
                ),
            )
            .apply {
              setBlendMode(BlendMode.OPAQUE)
              setSortOrder(SortOrder.OPAQUE)
              setDepthTest(DepthTest.LESS_OR_EQUAL)
              setDepthWrite(DepthWrite.ENABLE)
              setSidedness(MaterialSidedness.DOUBLE_SIDED)
              setStereoMode(StereoMode.None)
              setAttribute("colorMap", bgColor)
            }

    val fgMat =
        SceneMaterial.custom(
                "photo_fg",
                arrayOf(
                    SceneMaterialAttribute("geom", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("view", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("tear", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("colorMap", SceneMaterialDataType.Texture2D),
                    SceneMaterialAttribute("depthMap", SceneMaterialDataType.Texture2D),
                    SceneMaterialAttribute("alphaMap", SceneMaterialDataType.Texture2D),
                ),
            )
            .apply {
              setBlendMode(BlendMode.TRANSLUCENT)
              setSortOrder(SortOrder.TRANSLUCENT)
              setDepthTest(DepthTest.LESS_OR_EQUAL)
              setDepthWrite(DepthWrite.ENABLE)
              setSidedness(MaterialSidedness.DOUBLE_SIDED)
              setStereoMode(StereoMode.None)
              setAttribute("colorMap", fgColor)
              setAttribute("depthMap", depthTex)
              setAttribute("alphaMap", alphaTex)
            }

    val mesh =
        SceneMesh.meshWithMaterials(
            built.positions,
            built.normals,
            built.uvs,
            built.colors,
            built.indices,
            built.materialRanges,
            arrayOf(bgMat, fgMat),
            false,
        )
    built.dropGeometry()

    val id = ++meshSeq
    pendingMeshes[id] = mesh
    val entity =
        Entity.create(
            Mesh(Uri.parse("$MESH_BASE?id=$id"), hittable = MeshCollision.NoCollision),
            Transform(Pose(WINDOW_POSITION)),
        )

    live = Live(entity, mesh, fgMat, bgMat, listOf(fgColor, bgColor, depthTex, alphaTex), built)
    // Fix the strength-independent bits once; the rest is refreshed every tick.
    applyUniforms(0f, 0f)
  }

  fun hide() {
    live?.let { release(it) }
    live = null
  }

  private fun release(l: Live) {
    l.entity.destroy()
    // The native renderer may still hold the mesh for the current frame; frees are deferred by
    // the SDK's cleaner, so destroying here is safe on the render thread.
    l.fgMat.destroy()
    l.bgMat.destroy()
    l.textures.forEach { it.destroy() }
    l.mesh.destroy()
  }

  /** Called once per frame by [PhotoSystem]. */
  fun tick(dt: Float) {
    time += dt
    var ex = 0f
    var ey = 0f
    if (swayOn) {
      val phase = (time / SWAY_PERIOD_S) * (2f * Math.PI.toFloat())
      ex = SWAY_AMP * sin(phase)
      ey = 0.4f * SWAY_AMP * sin(2f * phase)
    }
    applyUniforms(ex, ey)
  }

  private fun applyUniforms(ex: Float, ey: Float) {
    val l = live ?: return
    val item = l.built.item
    val subj = item.subject.coerceIn(0.02f, 0.98f)
    val p = strength
    val winW = l.built.windowW
    val winD = WIN_D * winW
    val aspect = l.built.aspect
    val tearThr = max(0.04f, 1.5f / (0.1f * p * l.built.widthPx))
    val geom = Vector4(p, subj, aspect, winW)
    l.fgMat.setAttribute("geom", geom)
    l.fgMat.setAttribute("view", Vector4(winD, ex, ey, 1f))
    l.fgMat.setAttribute("tear", Vector4(tearThr, 1f, 0f, 0f))
    l.bgMat.setAttribute("geom", geom)
    l.bgMat.setAttribute("view", Vector4(winD, ex, ey, BG_BIAS))
    l.bgMat.setAttribute("tear", Vector4(tearThr, 0f, 0f, 0f))
  }

  /** Window size in metres for a photo of the given aspect (width / height). */
  fun windowWidthFor(aspect: Float): Float = minOf(MAX_HEIGHT_M * aspect, MAX_WIDTH_M)
}

/** Drives [PhotoWindow.tick] every frame. */
class PhotoSystem(private val window: PhotoWindow) : SystemBase() {
  private var last = System.nanoTime()

  override fun execute() {
    val now = System.nanoTime()
    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
    last = now
    window.tick(dt)
  }
}
