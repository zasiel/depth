package com.cyote3d.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class IconKind {
  Back,
  Prev,
  Next,
  Minus,
  Plus,
  Sway,
  Import,
  Phone,
}

/** 20 dp stroke-style icons drawn in code (no icon font to bundle yet). Coordinates are on a 20x20 grid. */
@Composable
fun LineIcon(icon: IconKind, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
  Canvas(modifier.size(size)) {
    val k = this.size.width / 20f
    val stroke = Stroke(width = 1.75f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
    fun poly(vararg pts: Pair<Float, Float>) {
      val path = Path()
      pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * k, y * k) else path.lineTo(x * k, y * k) }
      drawPath(path, tint, style = stroke)
    }
    when (icon) {
      IconKind.Back -> {
        line(16f, 10f, 4.5f, 10f)
        poly(9.5f to 5f, 4.5f to 10f, 9.5f to 15f)
      }
      IconKind.Prev -> poly(12.5f to 4f, 6.5f to 10f, 12.5f to 16f)
      IconKind.Next -> poly(7.5f to 4f, 13.5f to 10f, 7.5f to 16f)
      IconKind.Minus -> line(4f, 10f, 16f, 10f)
      IconKind.Plus -> {
        line(4f, 10f, 16f, 10f)
        line(10f, 4f, 10f, 16f)
      }
      IconKind.Sway -> {
        val path = Path().apply {
          moveTo(2f * k, 10f * k)
          quadraticTo(6f * k, 2.5f * k, 10f * k, 10f * k)
          quadraticTo(14f * k, 17.5f * k, 18f * k, 10f * k)
        }
        drawPath(path, tint, style = stroke)
      }
      IconKind.Import -> {
        line(10f, 3f, 10f, 12.5f)
        poly(6f to 9f, 10f to 13f, 14f to 9f)
        poly(4f to 14f, 4f to 17f, 16f to 17f, 16f to 14f)
      }
      IconKind.Phone -> phone(tint, k, stroke)
    }
  }
}

private fun DrawScope.phone(tint: Color, k: Float, stroke: Stroke) {
  drawRoundRect(
      tint,
      topLeft = Offset(5.5f * k, 2f * k),
      size = Size(9f * k, 16f * k),
      cornerRadius = CornerRadius(2.5f * k),
      style = stroke,
  )
  drawLine(tint, Offset(9f * k, 15.5f * k), Offset(11f * k, 15.5f * k), strokeWidth = stroke.width, cap = StrokeCap.Round)
}
