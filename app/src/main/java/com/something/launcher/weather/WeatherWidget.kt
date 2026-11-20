package com.something.launcher.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.launcher.ui.DotText
import com.something.launcher.ui.Glyphs
import com.something.launcher.ui.Ink
import com.something.launcher.ui.PixelGlyph
import com.something.launcher.ui.dotFrame

/**
 * The 2×2 weather widget: place, pixel condition glyph, big dot-matrix temperature, condition
 * and high/low. A severe NWS alert takes the bottom line in red. The caller makes it tappable.
 */
@Composable
fun WeatherWidget(state: WeatherState, fahrenheit: Boolean, modifier: Modifier = Modifier) {
    val f = state.forecast
    Box(modifier.dotFrame(corner = 26.dp).padding(16.dp)) {
        if (f == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                PixelGlyph(Glyphs.PartlyDay, 46.dp, unlit = Ink.Faint)
                Column {
                    DotText("Weather", 20.sp)
                    DotText(if (state.loading) "Loading" else "Tap to set up", 13.sp, color = Ink.Dim)
                }
            }
            return@Box
        }
        val alert = f.topAlert?.takeIf { it.isSevere }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (alert != null) {
                            Box(Modifier.size(7.dp).background(Ink.Red, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        DotText(f.place.name, 12.sp, color = Ink.Dim)
                    }
                }
                PixelGlyph(glyphFor(f.current.code, f.current.isDay), 44.dp)
            }
            Column {
                Row {
                    DotText("${Units.temp(f.current.tempC, fahrenheit)}", 52.sp, letterSpacing = 0.sp)
                    DotText("°", 28.sp, color = Ink.Red, modifier = Modifier.padding(top = 4.dp))
                }
                if (alert != null) {
                    DotText(alert.event, 12.sp, color = Ink.Red)
                } else {
                    DotText(WeatherCodes.label(f.current.code), 13.sp)
                }
                f.today?.let {
                    DotText("H ${Units.temp(it.hiC, fahrenheit)}°  L ${Units.temp(it.loC, fahrenheit)}°", 12.sp, color = Ink.Dim)
                }
            }
        }
    }
}
