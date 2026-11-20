package com.childsplay.launcher.weather

import androidx.compose.ui.graphics.Color

/**
 * A colour skin for Hero Weather and its widget. Every skin is loud; the weather shifts the
 * palette (stormy, night) but never toward grey.
 */
data class Skin(
    val id: String,
    val name: String,
    val dayTop: Color,
    val dayBottom: Color,
    val nightTop: Color,
    val nightBottom: Color,
    val stormTop: Color,
    val stormBottom: Color,
    val hillFar: Color,
    val hillNear: Color,
    val sun: Color,
    val cloud: Color,
    val stormCloud: Color,
    val accent: Color,
    val card: Color,
    val onCard: Color,
    val heroText: Color,
)

object Skins {
    private val Ink = Color(0xFF1B0B3B)

    val Popsicle = Skin(
        id = "popsicle", name = "Popsicle",
        dayTop = Color(0xFF00A8FF), dayBottom = Color(0xFF7DF9FF),
        nightTop = Color(0xFF2B0A8F), nightBottom = Color(0xFF7A1FD1),
        stormTop = Color(0xFF3A1078), stormBottom = Color(0xFF00897B),
        hillFar = Color(0xFF00D26A), hillNear = Color(0xFF7BE000),
        sun = Color(0xFFFFD600), cloud = Color.White, stormCloud = Color(0xFF6A3DE8),
        accent = Color(0xFFFF2BB4), card = Color(0xFFFFFDF2), onCard = Ink, heroText = Color.White,
    )
    val Bubblegum = Skin(
        id = "bubblegum", name = "Bubblegum",
        dayTop = Color(0xFFFF4FC3), dayBottom = Color(0xFFFFB3E6),
        nightTop = Color(0xFF5B0E91), nightBottom = Color(0xFFC2185B),
        stormTop = Color(0xFF6A1B9A), stormBottom = Color(0xFFE91E63),
        hillFar = Color(0xFF00D6C3), hillNear = Color(0xFF00E5A0),
        sun = Color(0xFFFFEA00), cloud = Color(0xFFFFF0FA), stormCloud = Color(0xFF8E24AA),
        accent = Color(0xFF00D6C3), card = Color(0xFFFFF0FA), onCard = Ink, heroText = Color.White,
    )
    val Tangerine = Skin(
        id = "tangerine", name = "Tangerine",
        dayTop = Color(0xFFFF7A00), dayBottom = Color(0xFFFFD600),
        nightTop = Color(0xFF8E0038), nightBottom = Color(0xFFFF4D00),
        stormTop = Color(0xFF7B1FA2), stormBottom = Color(0xFFFF6D00),
        hillFar = Color(0xFFFF2E4D), hillNear = Color(0xFF8A2BFF),
        sun = Color(0xFFFFFF3D), cloud = Color(0xFFFFF8E1), stormCloud = Color(0xFFB0006D),
        accent = Color(0xFF8A2BFF), card = Color(0xFFFFF8E1), onCard = Ink, heroText = Color.White,
    )
    val Lagoon = Skin(
        id = "lagoon", name = "Lagoon",
        dayTop = Color(0xFF00BFA5), dayBottom = Color(0xFF64FFDA),
        nightTop = Color(0xFF00296B), nightBottom = Color(0xFF00838F),
        stormTop = Color(0xFF1A237E), stormBottom = Color(0xFF00695C),
        hillFar = Color(0xFFFFD600), hillNear = Color(0xFFFF9100),
        sun = Color(0xFFFFEA00), cloud = Color.White, stormCloud = Color(0xFF283593),
        accent = Color(0xFFFF2E4D), card = Color(0xFFE0FFF9), onCard = Ink, heroText = Color.White,
    )
    val GrapeSoda = Skin(
        id = "grape", name = "Grape Soda",
        dayTop = Color(0xFF8A2BFF), dayBottom = Color(0xFFD17CFF),
        nightTop = Color(0xFF1A0066), nightBottom = Color(0xFF6200EA),
        stormTop = Color(0xFF12005E), stormBottom = Color(0xFF4A148C),
        hillFar = Color(0xFF76FF03), hillNear = Color(0xFFC6FF00),
        sun = Color(0xFFFFD600), cloud = Color(0xFFF3E5FF), stormCloud = Color(0xFF311B92),
        accent = Color(0xFFC6FF00), card = Color(0xFFF3E5FF), onCard = Ink, heroText = Color.White,
    )
    val LemonLime = Skin(
        id = "lemonlime", name = "Lemon-Lime",
        dayTop = Color(0xFFC6FF00), dayBottom = Color(0xFFFFFF00),
        nightTop = Color(0xFF00695C), nightBottom = Color(0xFF2E7D32),
        stormTop = Color(0xFF1B5E20), stormBottom = Color(0xFF827717),
        hillFar = Color(0xFF00C853), hillNear = Color(0xFF00A8FF),
        sun = Color(0xFFFF7A00), cloud = Color.White, stormCloud = Color(0xFF2E7D32),
        accent = Color(0xFFFF2BB4), card = Color(0xFFFFFDE7), onCard = Ink, heroText = Ink,
    )

    val all = listOf(Popsicle, Bubblegum, Tangerine, Lagoon, GrapeSoda, LemonLime)

    fun byId(id: String?): Skin = all.firstOrNull { it.id == id } ?: Popsicle
}
