package app.wallcapture.core

/** Every capture-time threshold. Starting values are estimates; calibrate with field tests. */
data class CaptureConfig(
    val minDistanceM: Double = 0.6,
    val maxDistanceM: Double = 2.0,
    val maxAngularVelRadS: Double = 1.0,
    val maxLinearSpeedMS: Double = 0.6,
    /** Capture when overlap with the last kept still would fall below this fraction. */
    val overlapTrigger: Double = 0.6,
    /** Or when at least this fraction of the footprint is not yet captured. */
    val newAreaFraction: Double = 0.25,
    val hintTiltDeg: Double = 30.0,
    val rejectTiltDeg: Double = 50.0,
    val levelHintRollDeg: Double = 25.0,
    val burstSize: Int = 3,
    /** Variance-of-Laplacian floor on a 512 px copy. Calibrate per device. */
    val minSharpness: Double = 20.0,
    val cellMm: Double = 20.0,
    val goodMinPxPerMm: Double = 2.5,
    val goodMaxAngleDeg: Double = 30.0,
)

/** Every processing-time threshold. */
data class ProcessingConfig(
    /** Output resolution: 3, 4 or 6 px per mm by user setting. */
    val pxPerMm: Double = 4.0,
    val renderTilePx: Int = 2048,
    val pyramidTilePx: Int = 256,
    /** Low band of the two-band blend is a box blur of about this size on the wall. */
    val lowBandRadiusMm: Double = 6.0,
    val edgeFeatherFraction: Double = 0.08,
    /** Pixels at or above this are treated as saturated. */
    val glareSaturation: Float = 250f,
    /** A sample this much brighter than the median of its neighbours is glare. */
    val glareDelta: Float = 40f,
    val refineMinOverlap: Double = 0.3,
    val refineScalePxPerMm: Double = 1.0,
    val refineSearchMm: Double = 60.0,
    val refineMaxShiftMm: Double = 50.0,
    val refineMinScore: Double = 0.5,
    /** Note detection runs on a render at this resolution (1 px/mm per spec), reduced to fit [noteDetectMaxPixels]. */
    val noteDetectPxPerMm: Double = 1.0,
    val noteDetectMaxPixels: Double = 24_000_000.0,
    val noteMinMm: Double = 40.0,
    val noteMaxMm: Double = 200.0,
    val noteMinAspect: Double = 0.5,
    val noteMaxAspect: Double = 2.0,
    val noteMinChromaDistance: Double = 60.0,
    val noteMinSaturation: Double = 0.30,
    val clusterEpsNoteWidths: Double = 1.5,
    val ocrWindowPx: Int = 4000,
    val ocrWindowOverlap: Double = 0.10,
    val ocrReviewConfidence: Double = 0.3,
)
