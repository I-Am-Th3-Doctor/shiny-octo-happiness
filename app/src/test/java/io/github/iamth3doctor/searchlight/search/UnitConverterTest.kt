package io.github.iamth3doctor.searchlight.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitConverterTest {
    private fun text(q: String) = UnitConverter.convert(q)?.text

    @Test fun milesToKm() = assertEquals("16.0934 km", text("10 miles to km"))

    @Test fun temperatures() {
        assertEquals("22.2222 °C", text("72 F to C"))
        assertEquals("212 °F", text("100 celsius in fahrenheit"))
        assertEquals("273.15 K", text("0C to K"))
    }

    @Test fun everydayKinds() {
        assertEquals("2.20462 lb", text("1 kg to lbs"))
        assertEquals("3.78541 l", text("1 gallon to liters"))
        assertEquals("62.1371 mph", text("100 km/h to mph"))
        assertEquals("1,000 MB", text("1 GB in MB"))
        assertEquals("90 min", text("1.5 hours to minutes"))
        assertEquals("30.48 cm", text("1 foot to cm"))
        assertEquals("2.54 cm", text("1 in to cm")) // "in" the unit, then the connector
    }

    @Test fun notConversions() {
        assertNull(text("10 miles"))
        assertNull(text("10 kg to km")) // different kinds
        assertNull(text("youtube to music"))
        assertNull(text("42 x 18"))
        assertNull(text("5 km to km"))
    }
}
