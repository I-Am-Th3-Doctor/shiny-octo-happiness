package io.github.iamth3doctor.searchlight.search

/**
 * "10 miles to km" -> "16.0934 km". Pure Kotlin; JVM-tested.
 *
 * A query is "<number> <unit> to|in|as|= <unit>" (the space between number and unit optional:
 * "72F to C"). Both units must be of one kind; the answer uses the target unit's own spelling.
 */
object UnitConverter {

    enum class Kind { LENGTH, MASS, VOLUME, TEMPERATURE, SPEED, DATA, TIME, AREA }

    /** A unit: its kind, and how many of the kind's base unit one of it is (temperature: see [toBase]). */
    private class Unit(val kind: Kind, val symbol: String, val factor: Double)

    private val units = HashMap<String, Unit>()

    private fun add(kind: Kind, symbol: String, factor: Double, vararg names: String) {
        val u = Unit(kind, symbol, factor)
        (names.toList() + symbol).forEach { units[it.lowercase()] = u }
    }

    init {
        // Length, in metres.
        add(Kind.LENGTH, "mm", 0.001, "millimeter", "millimeters", "millimetre", "millimetres")
        add(Kind.LENGTH, "cm", 0.01, "centimeter", "centimeters", "centimetre", "centimetres")
        add(Kind.LENGTH, "m", 1.0, "meter", "meters", "metre", "metres")
        add(Kind.LENGTH, "km", 1000.0, "kilometer", "kilometers", "kilometre", "kilometres", "kms")
        add(Kind.LENGTH, "in", 0.0254, "inch", "inches", "\"")
        add(Kind.LENGTH, "ft", 0.3048, "foot", "feet", "'")
        add(Kind.LENGTH, "yd", 0.9144, "yard", "yards")
        add(Kind.LENGTH, "mi", 1609.344, "mile", "miles")
        add(Kind.LENGTH, "nmi", 1852.0, "nautical mile", "nautical miles")
        // Mass, in grams.
        add(Kind.MASS, "mg", 0.001, "milligram", "milligrams")
        add(Kind.MASS, "g", 1.0, "gram", "grams")
        add(Kind.MASS, "kg", 1000.0, "kilogram", "kilograms", "kilo", "kilos")
        add(Kind.MASS, "t", 1_000_000.0, "tonne", "tonnes", "metric ton", "metric tons")
        add(Kind.MASS, "oz", 28.349523125, "ounce", "ounces")
        add(Kind.MASS, "lb", 453.59237, "lbs", "pound", "pounds")
        add(Kind.MASS, "st", 6350.29318, "stone", "stones")
        // Volume, in millilitres.
        add(Kind.VOLUME, "ml", 1.0, "milliliter", "milliliters", "millilitre", "millilitres")
        add(Kind.VOLUME, "l", 1000.0, "liter", "liters", "litre", "litres")
        add(Kind.VOLUME, "tsp", 4.92892159375, "teaspoon", "teaspoons")
        add(Kind.VOLUME, "tbsp", 14.78676478125, "tablespoon", "tablespoons")
        add(Kind.VOLUME, "fl oz", 29.5735295625, "floz", "fluid ounce", "fluid ounces")
        add(Kind.VOLUME, "cup", 236.5882365, "cups")
        add(Kind.VOLUME, "pt", 473.176473, "pint", "pints")
        add(Kind.VOLUME, "qt", 946.352946, "quart", "quarts")
        add(Kind.VOLUME, "gal", 3785.411784, "gallon", "gallons")
        // Temperature: handled by toBase/fromBase (kelvin).
        add(Kind.TEMPERATURE, "°C", 0.0, "c", "celsius", "°c", "degc", "centigrade")
        add(Kind.TEMPERATURE, "°F", 0.0, "f", "fahrenheit", "°f", "degf")
        add(Kind.TEMPERATURE, "K", 0.0, "k", "kelvin")
        // Speed, in metres per second.
        add(Kind.SPEED, "m/s", 1.0, "mps", "meters per second", "metres per second")
        add(Kind.SPEED, "km/h", 1000.0 / 3600, "kmh", "kph", "kmph", "kilometers per hour", "kilometres per hour")
        add(Kind.SPEED, "mph", 1609.344 / 3600, "miles per hour")
        add(Kind.SPEED, "kn", 1852.0 / 3600, "knot", "knots", "kt")
        // Data, in bytes (decimal, like the system's storage screens).
        add(Kind.DATA, "B", 1.0, "byte", "bytes")
        add(Kind.DATA, "KB", 1e3, "kilobyte", "kilobytes")
        add(Kind.DATA, "MB", 1e6, "megabyte", "megabytes")
        add(Kind.DATA, "GB", 1e9, "gigabyte", "gigabytes")
        add(Kind.DATA, "TB", 1e12, "terabyte", "terabytes")
        // Time, in seconds.
        add(Kind.TIME, "s", 1.0, "sec", "secs", "second", "seconds")
        add(Kind.TIME, "min", 60.0, "mins", "minute", "minutes")
        add(Kind.TIME, "h", 3600.0, "hr", "hrs", "hour", "hours")
        add(Kind.TIME, "days", 86_400.0, "d", "day")
        add(Kind.TIME, "weeks", 604_800.0, "wk", "week")
        // Area, in square metres.
        add(Kind.AREA, "m²", 1.0, "m2", "sq m", "square meter", "square meters", "square metre", "square metres")
        add(Kind.AREA, "km²", 1e6, "km2", "sq km", "square kilometer", "square kilometers")
        add(Kind.AREA, "ft²", 0.09290304, "ft2", "sq ft", "square foot", "square feet")
        add(Kind.AREA, "acres", 4046.8564224, "acre", "ac")
        add(Kind.AREA, "ha", 10_000.0, "hectare", "hectares")
    }

    class Answer(val value: Double, val text: String, val from: String, val to: String)

    private val pattern = Regex(
        """^\s*(-?[\d,]*\.?\d+)\s*([a-z°'"/² ]+?)\s+(?:to|in|into|as|=)\s+([a-z°'"/² ]+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** The conversion [query] asks for, or null if it isn't one (or mixes kinds). */
    fun convert(query: String): Answer? {
        val m = pattern.matchEntire(query) ?: return null
        val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val from = units[m.groupValues[2].trim().lowercase()] ?: return null
        val to = units[m.groupValues[3].trim().lowercase()] ?: return null
        if (from.kind != to.kind || from === to) return null
        val value = fromBase(toBase(amount, from), to)
        return Answer(value, "${Calculator.format(round(value))} ${to.symbol}", from.symbol, to.symbol)
    }

    private fun toBase(v: Double, u: Unit): Double = when (u.symbol) {
        "°C" -> v + 273.15
        "°F" -> (v - 32) * 5 / 9 + 273.15
        "K" -> v
        else -> v * u.factor
    }

    private fun fromBase(v: Double, u: Unit): Double = when (u.symbol) {
        "°C" -> v - 273.15
        "°F" -> (v - 273.15) * 9 / 5 + 32
        "K" -> v
        else -> v / u.factor
    }

    /** Six significant digits: enough for any everyday answer, no float noise ("22.2222222"). */
    private fun round(v: Double): Double =
        if (v == 0.0) 0.0 else java.math.BigDecimal(v).round(java.math.MathContext(6)).toDouble()
}
