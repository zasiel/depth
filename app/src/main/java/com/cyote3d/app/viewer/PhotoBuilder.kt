package com.cyote3d.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.SystemClock
import android.util.Log
import com.cyote3d.app.PhotoItem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CPU-side preparation of one photo for the 3D window (port of makeLayer()/show() in viewer/app.js).
 *
 * The window is one mesh holding two flat grids, background first and foreground second:
 *  - `position.xy` = window-plane position in metres, `position.z` = baked closeness of the texel
 *    under the vertex (nearest sample of the 16-bit depth PNG, exactly what the web viewer's
 *    nearest-filtered vertex texture fetch returns). The vertex shader does the rest.
 *  - foreground: about one vertex per texel, at most [MAX_COLUMNS] columns; background: half density.
 *
 * Two small data textures are derived for the fragment shader. They carry their value in the ALPHA
 * channel only (RGB stays 0) so they are immune to sRGB decoding of colour channels:
 *  - [depthBitmap]: alpha = round(closeness * 255), sampled nearest (tear cut);
 *  - [alphaBitmap]: alpha = foreground matte alpha (B channel of fg_depth.png), sampled linearly.
 */
class BuiltPhoto(
    val item: PhotoItem,
    val windowW: Float,
    val aspect: Float,
    val widthPx: Int,
    var positions: FloatArray,
    var normals: FloatArray,
    var uvs: FloatArray,
    var colors: IntArray,
    var indices: IntArray,
    val materialRanges: IntArray,
    val depthBitmap: Bitmap,
    val alphaBitmap: Bitmap,
) {
  /** Frees the large JVM arrays once the native mesh owns a copy. */
  fun dropGeometry() {
    positions = FloatArray(0)
    normals = FloatArray(0)
    uvs = FloatArray(0)
    colors = IntArray(0)
    indices = IntArray(0)
  }

  fun recycleBitmaps() {
    if (!depthBitmap.isRecycled) depthBitmap.recycle()
    if (!alphaBitmap.isRecycled) alphaBitmap.recycle()
  }
}

object PhotoBuilder {
  private const val TAG = "Cyote3D"
  const val MAX_COLUMNS = 1280f

  fun build(context: Context, item: PhotoItem, windowW: Float): BuiltPhoto {
    val t0 = SystemClock.elapsedRealtime()
    val fg = readForeground(context, "${item.dir}/fg_depth.png")
    val bg = readBackground(context, "${item.dir}/bg_depth.png")

    val aspect = fg.w.toFloat() / fg.h
    val step = max(1f, max(fg.w, fg.h) / MAX_COLUMNS)
    val fgGw = max(2, (fg.w / step).roundToInt())
    val fgGh = max(2, (fg.h / step).roundToInt())
    val bgGw = max(2, (bg.w / (step * 2)).roundToInt())
    val bgGh = max(2, (bg.h / (step * 2)).roundToInt())

    val nVbg = (bgGw + 1) * (bgGh + 1)
    val nVfg = (fgGw + 1) * (fgGh + 1)
    val nIbg = bgGw * bgGh * 6
    val nIfg = fgGw * fgGh * 6
    val nV = nVbg + nVfg

    val positions = FloatArray(nV * 3)
    val normals = FloatArray(nV * 3)
    val uvs = FloatArray(nV * 2)
    val colors = IntArray(nV)
    val indices = IntArray(nIbg + nIfg)

    fillGrid(bg.closeness, bg.w, bg.h, bgGw, bgGh, windowW, aspect, 0, positions, normals, uvs, colors, indices, 0)
    fillGrid(fg.closeness, fg.w, fg.h, fgGw, fgGh, windowW, aspect, nVbg, positions, normals, uvs, colors, indices, nIbg)

    Log.i(TAG, "built ${item.name}: bg ${bgGw}x$bgGh, fg ${fgGw}x$fgGh, $nV vertices, ${(nIbg + nIfg) / 3} triangles in ${SystemClock.elapsedRealtime() - t0} ms")

    return BuiltPhoto(
        item = item,
        windowW = windowW,
        aspect = aspect,
        widthPx = fg.w,
        positions = positions,
        normals = normals,
        uvs = uvs,
        colors = colors,
        indices = indices,
        materialRanges = intArrayOf(0, nIbg, nIbg, nIfg), // material 0 = background, 1 = foreground
        depthBitmap = fg.depth8,
        alphaBitmap = fg.alpha,
    )
  }

  private class Foreground(val w: Int, val h: Int, val closeness: FloatArray, val depth8: Bitmap, val alpha: Bitmap)

  private class Background(val w: Int, val h: Int, val closeness: FloatArray)

  /** Decodes a data PNG byte-for-byte: no scaling, no premultiplication, no colour management. */
  private fun decodeRaw(context: Context, path: String): Bitmap {
    val opts =
        BitmapFactory.Options().apply {
          inPreferredConfig = Bitmap.Config.ARGB_8888
          inScaled = false
          inPremultiplied = false
          inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
    context.assets.open(path).use { stream ->
      return BitmapFactory.decodeStream(stream, null, opts) ?: error("could not decode $path")
    }
  }

  /** fg_depth.png: R,G = 16-bit closeness (hi, lo byte); B = foreground alpha. */
  private fun readForeground(context: Context, path: String): Foreground {
    val bmp = decodeRaw(context, path)
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    bmp.recycle()

    val closeness = FloatArray(w * h)
    val depth8 = IntArray(w * h)
    for (i in px.indices) {
      val p = px[i]
      val r = (p ushr 16) and 0xFF
      val g = (p ushr 8) and 0xFF
      val b = p and 0xFF
      val c = (r * 256 + g) / 65535f
      closeness[i] = c
      depth8[i] = (c * 255f + 0.5f).toInt() shl 24
      px[i] = b shl 24 // reuse the buffer for the alpha texture
    }
    val depthBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    depthBmp.setPixels(depth8, 0, w, 0, 0, w, h)
    val alphaBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    alphaBmp.setPixels(px, 0, w, 0, 0, w, h)
    return Foreground(w, h, closeness, depthBmp, alphaBmp)
  }

  /** bg_depth.png: R,G = 16-bit closeness. */
  private fun readBackground(context: Context, path: String): Background {
    val bmp = decodeRaw(context, path)
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    bmp.recycle()
    val closeness = FloatArray(w * h) { i -> (((px[i] ushr 16) and 0xFF) * 256 + ((px[i] ushr 8) and 0xFF)) / 65535f }
    return Background(w, h, closeness)
  }

  /**
   * Writes one (gw+1) x (gh+1) vertex grid and its triangles. Vertex (i, j): u = i/gw across, v = j/gh
   * down the image; closeness is the nearest texel to (u, v), as the web viewer's vertex fetch does.
   */
  private fun fillGrid(
      closeness: FloatArray,
      tw: Int,
      th: Int,
      gw: Int,
      gh: Int,
      widthM: Float,
      aspect: Float,
      vBase: Int,
      pos: FloatArray,
      nrm: FloatArray,
      uv: FloatArray,
      col: IntArray,
      idx: IntArray,
      iBase: Int,
  ) {
    val heightM = widthM / aspect
    val cols = gw + 1
    for (j in 0..gh) {
      val v = j.toFloat() / gh
      val row = min(th - 1, (v * th).toInt()) * tw
      val y = (0.5f - v) * heightM
      for (i in 0..gw) {
        val u = i.toFloat() / gw
        val vi = vBase + j * cols + i
        val o3 = vi * 3
        pos[o3] = (u - 0.5f) * widthM
        pos[o3 + 1] = y
        pos[o3 + 2] = closeness[row + min(tw - 1, (u * tw).toInt())]
        nrm[o3 + 2] = 1f
        uv[vi * 2] = u
        uv[vi * 2 + 1] = v
        col[vi] = -1 // opaque white (0xFFFFFFFF)
      }
    }
    var k = iBase
    for (j in 0 until gh) {
      for (i in 0 until gw) {
        val a = vBase + j * cols + i
        val c = a + cols
        idx[k++] = a // two counter-clockwise triangles (seen from +z)
        idx[k++] = c
        idx[k++] = a + 1
        idx[k++] = a + 1
        idx[k++] = c
        idx[k++] = c + 1
      }
    }
  }
}
