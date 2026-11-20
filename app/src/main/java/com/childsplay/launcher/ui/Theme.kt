package com.childsplay.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.R

/** Chunky display face for big, loud headings and the weather hero. */
val Chunky = FontFamily(Font(R.font.lilita_one))

/** Rounded, friendly body face. Variable font, so pin the weights we use. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Bubbly = FontFamily(
    Font(R.font.fredoka, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.fredoka, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.fredoka, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.fredoka, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Handwriting for the sticky note. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Handwriting = FontFamily(
    Font(R.font.caveat, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.caveat, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** The toy box: saturated, primary-forward colours. Nothing muted. */
object Toy {
    val Red = Color(0xFFFF2E4D)
    val Orange = Color(0xFFFF7A00)
    val Yellow = Color(0xFFFFD600)
    val Lime = Color(0xFF7BE000)
    val Green = Color(0xFF00C853)
    val Teal = Color(0xFF00D6C3)
    val Sky = Color(0xFF00A8FF)
    val Blue = Color(0xFF2962FF)
    val Purple = Color(0xFF8A2BFF)
    val Pink = Color(0xFFFF2BB4)
    val Ink = Color(0xFF1B0B3B)
    val Paper = Color(0xFFFFFDF2)
    val NoteYellow = Color(0xFFFFE94D)

    /** Colours a user can paint a thing with. */
    val thingColors = listOf(Red, Orange, Yellow, Lime, Green, Teal, Sky, Blue, Purple, Pink)

    /** Readable ink on top of a given toy colour. */
    fun onColor(c: Color): Color {
        val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        return if (lum > 0.62f) Ink else Color.White
    }
}

private val ToyTypography = Typography(
    displayLarge = TextStyle(fontFamily = Chunky, fontSize = 57.sp),
    headlineLarge = TextStyle(fontFamily = Chunky, fontSize = 34.sp),
    headlineMedium = TextStyle(fontFamily = Chunky, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = Chunky, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Chunky, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Bubbly, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
)

@Composable
fun ToyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Toy.Purple,
            onPrimary = Color.White,
            secondary = Toy.Pink,
            tertiary = Toy.Yellow,
            background = Toy.Paper,
            surface = Color.White,
            onSurface = Toy.Ink,
            onBackground = Toy.Ink,
        ),
        typography = ToyTypography,
        content = content,
    )
}
