package com.tanveer.aiva.data.local

/** Bounds chosen so a 4 GB phone is not asked to decode several full-resolution photos. */
object ImageLimits {
    const val MAX_COUNT = 4
    const val MAX_EDGE_PX = 1280
    const val JPEG_QUALITY = 80
    const val MAX_STORED_BYTES = 1_500_000
    const val MAX_SOURCE_BYTES = 20_000_000
    const val MAX_TOTAL_BYTES = 4_000_000
}

/** Largest power-of-two sample size that still keeps the long edge near [maxEdge]. */
fun subsample(width: Int, height: Int, maxEdge: Int = ImageLimits.MAX_EDGE_PX): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    var w = width
    var h = height
    while (w > maxEdge * 2 || h > maxEdge * 2) {
        sample *= 2
        w /= 2
        h /= 2
        if (sample >= 64) break
    }
    return sample
}
