package com.something.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.something.launcher.R

/** The whole palette. Black, white, and one red. Nothing else. */
object Ink {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    /** Nothing's red. Used sparingly: "now", alerts, one accent per surface. */
    val Red = Color(0xFFD71921)
    /** White at reduced strength, for unlit dots and secondary text. */
    val Dim = Color(0x66FFFFFF)
    val Faint = Color(0x26FFFFFF)
}

/** Doto: a dot-matrix face. ROND=100 gives round dots, like an LED panel. */
@OptIn(ExperimentalTextApi::class)
private fun doto(w: Int) = Font(
    R.font.doto,
    FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("ROND", 100f)),
)

val Dot = FontFamily(doto(400), doto(600), doto(700), doto(900))

/** Monospace for small, dense text (long alert bodies, tiny captions) where dots stop being legible. */
val Mono = FontFamily(
    Font(R.font.space_mono, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

private val DotTypography = Typography(
    displayLarge = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 57.sp),
    headlineLarge = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 11.sp),
    labelLarge = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp),
)

@Composable
fun SomethingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.White,
            onPrimary = Ink.Black,
            secondary = Ink.Red,
            onSecondary = Ink.White,
            background = Ink.Black,
            onBackground = Ink.White,
            surface = Ink.Black,
            onSurface = Ink.White,
            surfaceContainer = Ink.Black,
            surfaceVariant = Ink.Black,
            onSurfaceVariant = Ink.White,
            error = Ink.Red,
        ),
        typography = DotTypography,
        content = content,
    )
}
