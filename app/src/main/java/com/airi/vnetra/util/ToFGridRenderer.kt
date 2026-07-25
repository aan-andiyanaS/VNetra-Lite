package com.airi.vnetra.util

/**
 * ToFGridRenderer
 *
 * Menggambar representasi visual matriks ToF di layar perangkat (UI).
 * Menggunakan warna untuk membedakan zona aman dan bahaya berdasarkan jarak objek.
 * Bertujuan sebagai alat debugging visual dan feedback bagi pendamping.
 */

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
class ToFGridRenderer(
    private val context: Context,
    private val gridLayout: GridLayout
) {
    private var tofViews: Array<TextView> = emptyArray()

    private val hsvTemp = floatArrayOf(0f, 0.80f, 0.85f)
    private val colorInvalidCell = Color.parseColor("#66444444")
    private var currentTexts: Array<String> = emptyArray()
    private var currentColors: IntArray = IntArray(0)

    private val HOLDOVER_FRAMES = 5
    private val TOF_FOV_V = 45f
    private val FOV_V = 41f

    /** Mendapatkan jumlah keseluruhan sel yang membentuk matriks visual ToF. */
    fun getGridSize(): Int = tofViews.size

    /** Menginisialisasi pembuatan kotak-kotak sel visual pada layar UI. */
    fun initializeGrid() {
        rebuildGrid()
    }

    /** Menyusun ulang kotak-kotak sel visual UI berdasarkan resolusi terbaru. */
    fun rebuildGrid() {
        val numCells = 64
        val textSizeSp = 7.5f
        currentTexts = Array(numCells) { "—" }
        currentColors = IntArray(numCells) { colorInvalidCell }

        gridLayout.removeAllViews()
        gridLayout.columnCount = 8
        gridLayout.rowCount = 8

        gridLayout.setBackgroundColor(Color.parseColor("#20000000"))

        tofViews = Array(numCells) { i ->
            val row = i / 8
            val col = i % 8
            TextView(context).apply {
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(col, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(1, 1, 1, 1)
                }
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = textSizeSp
                text = "—"
                setBackgroundColor(colorInvalidCell)
            }.also { gridLayout.addView(it) }
        }

    }

    /** Memperbarui warna dan teks pada setiap sel UI berdasarkan data jarak ToF terbaru. */
    fun updateGrid(
        tofData: IntArray,
        smoothed: FloatArray,
        holdover: IntArray,
        alpha: Float = 0.3f
    ) {
        if (tofViews.isEmpty() || tofData.size != tofViews.size) return

        for (i in tofData.indices) {
            var newText = "—"
            var newColor = colorInvalidCell

            val rawDistance = tofData[i]
            if (rawDistance <= 0) {
                val remaining = holdover[i]
                if (remaining > 0) {
                    holdover[i] = remaining - 1
                    val held = smoothed[i].toInt()
                    if (held > 0) {
                        newText = "$held"
                        newColor = getColorForDistance(held, dimmed = true)
                    }
                } else {
                    smoothed[i] = 0f
                }
            } else {
                holdover[i] = HOLDOVER_FRAMES
                smoothed[i] = if (smoothed[i] <= 0f) rawDistance.toFloat()
                              else alpha * rawDistance + (1f - alpha) * smoothed[i]
                val d = smoothed[i].toInt()
                newText = "$d"
                newColor = getColorForDistance(d)
            }

            if (currentTexts[i] != newText) {
                currentTexts[i] = newText
                tofViews[i].text = newText
            }
            if (currentColors[i] != newColor) {
                currentColors[i] = newColor
                tofViews[i].setBackgroundColor(newColor)
            }
        }
    }

    /** Mengosongkan tampilan sel visual UI (mengembalikan ke warna abu-abu netral). */
    fun clearGrid() {
        tofViews.forEach {
            it.text = "—"
            it.setBackgroundColor(colorInvalidCell)
        }
    }

    /** Menentukan warna visual sel (aman/peringatan/bahaya) berdasarkan jarak (mm). */
    private fun getColorForDistance(distance: Int, dimmed: Boolean = false): Int {
        if (distance <= 0) return colorInvalidCell
        val minDistance = 200f
        val maxDistance = 2000f
        val clampedDistance = distance.coerceIn(minDistance.toInt(), maxDistance.toInt()).toFloat()
        val ratio = (clampedDistance - minDistance) / (maxDistance - minDistance)
        hsvTemp[0] = ratio * 120f

        val alphaChannel = if (dimmed) 65 else 145
        return Color.HSVToColor(alphaChannel, hsvTemp)
    }
}
