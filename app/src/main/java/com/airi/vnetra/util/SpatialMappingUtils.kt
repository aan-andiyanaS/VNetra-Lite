package com.airi.vnetra.util

/**
 * SpatialMappingUtils
 *
 * ADR: Pemetaan spasial MPU6050 & VL53L5CX.
 * Menghitung zona horizontal dan vertikal berbasis ToF untuk sistem feedback TTS,
 * tanpa referensi ke sistem penglihatan komputer (vision/camera).
 * Beroperasi murni pada data array jarak (mm) dengan grid 8x8.
 */
object SpatialMappingUtils {

    const val WALL_TRACKING_ID = 999
    
    // Threshold jarak untuk dianggap sebagai ancaman dekat (mm)
    private const val CLOSE_DIST_MIN = 30
    private const val CLOSE_DIST_MAX = 2000

    private val emaDistances = FloatArray(64) { -1f }
    private const val EMA_ALPHA = 0.3f // ponytail: simple, fast smoothing

    data class ObstacleAnalysis(
        val type: String,       // "tembok" atau "halangan"
        val clockDirection: Int, // Arah jam (10, 11, 12, 1, 2)
        val averageDistance: Int // Jarak rata-rata (mm)
    )

    /** Mengonversi indeks kolom ToF (0..7) ke arah jam referensi spasial (10, 11, 12, 1, 2). */
    fun getColumnClockDirection(column: Int): Int = when (column) {
        0 -> 10
        in 1..2 -> 11
        in 3..4 -> 12
        in 5..6 -> 1
        7 -> 2
        else -> 12
    }

    /** Mengonversi kode arah jam menjadi string bahasa Indonesia untuk Text-to-Speech. */
    fun clockDirectionToTts(clockDirection: Int): String = when (clockDirection) {
        10 -> "arah 10"
        11 -> "arah 11"
        12 -> "arah 12"
         1 -> "arah 1"
         2 -> "arah 2"
        else -> "arah 12"
    }

    /**
     * Menganalisis grid ToF (64 elemen) untuk mencari "tembok" di tepi atau "halangan" berkelompok.
     */
    fun analyzeTerrain(tofData: IntArray, thetaDeg: Float): ObstacleAnalysis? {
        if (tofData.size != 64) return null

        val closeCells = extractCloseCells(tofData, thetaDeg)
        if (closeCells.isEmpty()) return null

        val leftWallAnalysis = analyzeWallOnSide(closeCells, isLeftSide = true)
        if (leftWallAnalysis != null) return leftWallAnalysis

        val rightWallAnalysis = analyzeWallOnSide(closeCells, isLeftSide = false)
        if (rightWallAnalysis != null) return rightWallAnalysis

        // Jika bukan tembok tepi, anggap sebagai objek (blob)
        return analyzeAsObject(closeCells)
    }

    private data class Cell(val row: Int, val col: Int, val dist: Int)

    private fun extractCloseCells(tofData: IntArray, thetaDeg: Float): List<Cell> {
        val cells = mutableListOf<Cell>()
        for (i in 0..63) {
            val rawDist = tofData[i]
            
            val dist = if (rawDist in CLOSE_DIST_MIN..CLOSE_DIST_MAX) {
                if (emaDistances[i] < 0f) {
                    emaDistances[i] = rawDist.toFloat()
                } else {
                    emaDistances[i] = (EMA_ALPHA * rawDist) + ((1f - EMA_ALPHA) * emaDistances[i])
                }
                emaDistances[i].toInt()
            } else {
                emaDistances[i] = -1f
                rawDist
            }

            if (dist in CLOSE_DIST_MIN..CLOSE_DIST_MAX) {
                cells.add(Cell(row = i / 8, col = i % 8, dist = dist))
            }
        }
        return cells
    }

    private fun analyzeWallOnSide(closeCells: List<Cell>, isLeftSide: Boolean): ObstacleAnalysis? {
        // Tentukan batasan kolom untuk tepi
        val edgeCols = if (isLeftSide) 0..2 else 5..7
        
        // Saring sel yang berada di area tepi
        val edgeCells = closeCells.filter { it.col in edgeCols }
        if (edgeCells.isEmpty()) return null

        // Syarat tembok: membentang secara vertikal minimal 4 baris di sisi ini
        val distinctRows = edgeCells.map { it.row }.distinct()
        if (distinctRows.size < 4) return null

        // Tentukan kolom terluar sebagai referensi arah
        val outermostCol = if (isLeftSide) {
            edgeCells.minOf { it.col }
        } else {
            edgeCells.maxOf { it.col }
        }

        val avgDist = edgeCells.map { it.dist }.average().toInt()
        val clockDir = getColumnClockDirection(outermostCol)

        return ObstacleAnalysis(
            type = "tembok",
            clockDirection = clockDir,
            averageDistance = avgDist
        )
    }

    private fun analyzeAsObject(closeCells: List<Cell>): ObstacleAnalysis {
        // Hitung centroid (titik pusat) berdasarkan rata-rata kolom
        val sumCol = closeCells.sumOf { it.col }
        val centerCol = (sumCol.toFloat() / closeCells.size).toInt()
        
        val avgDist = closeCells.map { it.dist }.average().toInt()
        val clockDir = getColumnClockDirection(centerCol)

        return ObstacleAnalysis(
            type = "halangan",
            clockDirection = clockDir,
            averageDistance = avgDist
        )
    }
}
