package com.cyote3d.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from BUILD_PLAN.md §4. Everything in the panels uses these. */
object Cyote {
  // Colour
  val PanelBackground = Color(0xE00E0F13) // #0E0F13 @ 88 %
  val Surface = Color(0xFF17181E)
  val Elevated = Color(0xFF1F2028)
  val Stroke = Color(0xFF2A2B35)
  val TextPrimary = Color(0xFFF2F1F6)
  val TextSecondary = Color(0xFFA3A1AF)
  val TextMuted = Color(0xFF6E6C7A)
  val Accent = Color(0xFF7C6CF2)
  val AccentPressed = Color(0xFF6656E0)
  val AccentSoft = Color(0x297C6CF2) // accent @ 16 %
  val Success = Color(0xFF3CC48A)
  val Danger = Color(0xFFE5596A)

  // Radius
  val RadiusPanel = 24.dp
  val RadiusButton = 14.dp
  val RadiusThumb = 12.dp
  val RadiusPill = 999.dp

  // Spacing scale
  val S4 = 4.dp
  val S8 = 8.dp
  val S12 = 12.dp
  val S16 = 16.dp
  val S24 = 24.dp
  val S32 = 32.dp

  /** Minimum hit target. */
  val HitTarget = 48.dp

  // Type (Inter is bundled in a later milestone; the system sans stands in until then).
  val Title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
  val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = TextPrimary)
  val Label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
  val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = TextMuted)

  // Motion
  const val PressMs = 160 // hover / press, ease-out
  const val PanelMs = 240 // panel transitions
}
