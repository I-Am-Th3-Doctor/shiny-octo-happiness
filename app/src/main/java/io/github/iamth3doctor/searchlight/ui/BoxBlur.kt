package io.github.iamth3doctor.searchlight.ui

/**
 * Blurs ARGB pixels in place: three box-blur passes each way, which together look like a
 * Gaussian. Run on a small, downscaled image, so it's cheap. Pure; JVM-tested.
 */
object BoxBlur {
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius < 1 || width < 1 || height < 1) return
        val tmp = IntArray(pixels.size)
        repeat(3) {
            pass(pixels, tmp, width, height, radius, horizontal = true)
            pass(tmp, pixels, width, height, radius, horizontal = false)
        }
    }

    /** One running-sum box blur along rows (or columns), edges clamped. */
    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[at(i)]
                a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until len) {
                dst[at(i)] = ((a / window) shl 24) or ((red / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = src[at(i - r)]
                val inn = src[at(i + r + 1)]
                a += (inn ushr 24) - (out ushr 24)
                red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
