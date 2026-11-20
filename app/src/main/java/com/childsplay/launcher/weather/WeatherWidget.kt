package com.childsplay.launcher.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.ui.Bubbly
import com.childsplay.launcher.ui.Chunky
import com.childsplay.launcher.ui.Toy
import java.time.format.DateTimeFormatter
import java.util.Locale

private val wDay = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

/**
 * Hero Weather's widget: a small living scene in the current skin with the temperature front
 * and centre and the next few days on the right. The caller makes it tappable.
 */
@Composable
fun HeroWeatherWidget(
    state: WeatherState,
    skin: Skin,
    fahrenheit: Boolean,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val f = state.forecast
    val cond = f?.condition ?: Condition.PARTLY
    val isDay = f?.current?.isDay ?: true
    Box(modifier) {
        LivingScene(cond, isDay, skin, f?.current?.windKmh ?: 0.0, Modifier.fillMaxSize(), compact = true, animate = animate)
        if (f == null) {
            Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                OutlinedHeroText("Hero Weather", 30.sp, Color.White, outline = 8f)
                Text(
                    if (state.loading) "Checking the sky…" else "Tap to set up your weather",
                    fontFamily = Chunky, fontSize = 16.sp, color = Toy.Ink,
                    modifier = Modifier.background(Color.White, RoundedCornerShape(50)).border(2.dp, Toy.Ink, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            return@Box
        }
        Row(Modifier.fillMaxSize().padding(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    f.place.name, fontFamily = Chunky, fontSize = 16.sp, color = Toy.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Color.White, RoundedCornerShape(50)).border(2.dp, Toy.Ink, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
                Column {
                    OutlinedHeroText("${Units.temp(f.current.tempC, fahrenheit)}°", 62.sp, Color.White, outline = 10f)
                    OutlinedHeroText(WeatherCodes.label(f.current.code), 19.sp, Toy.Yellow, outline = 6f)
                }
            }
            Column(
                Modifier
                    .background(skin.card, RoundedCornerShape(20.dp))
                    .border(2.5.dp, Toy.Ink, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                f.days.take(3).forEachIndexed { i, d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (i == 0) "Today" else wDay.format(d.date),
                            fontFamily = Chunky, fontSize = 13.sp, color = skin.onCard, modifier = Modifier.width(42.dp),
                        )
                        WeatherGlyph(d.code, true, 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${Units.temp(d.hiC, fahrenheit)}° ${Units.temp(d.loC, fahrenheit)}°",
                            fontFamily = Bubbly, fontSize = 13.sp, color = skin.onCard,
                        )
                    }
                }
            }
        }
        f.topAlert?.takeIf { it.isSevere }?.let { a ->
            Text(
                "! ${a.event}", fontFamily = Chunky, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .background(Toy.Red, RoundedCornerShape(50)).border(2.dp, Toy.Ink, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}
