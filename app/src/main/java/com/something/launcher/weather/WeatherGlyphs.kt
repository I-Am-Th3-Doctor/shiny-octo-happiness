package com.something.launcher.weather

import com.something.launcher.ui.Glyph
import com.something.launcher.ui.Glyphs

/** The pixel glyph for a WMO weather code. */
fun glyphFor(code: Int, isDay: Boolean): Glyph = when (WeatherCodes.condition(code)) {
    Condition.CLEAR -> if (isDay) Glyphs.Sun else Glyphs.Moon
    Condition.PARTLY -> if (isDay) Glyphs.PartlyDay else Glyphs.PartlyNight
    Condition.CLOUDY -> Glyphs.Cloudy
    Condition.FOG -> Glyphs.Fog
    Condition.DRIZZLE -> Glyphs.Drizzle
    Condition.RAIN -> Glyphs.Rain
    Condition.SLEET -> Glyphs.Sleet
    Condition.SNOW -> Glyphs.Snow
    Condition.STORM -> Glyphs.Storm
}
