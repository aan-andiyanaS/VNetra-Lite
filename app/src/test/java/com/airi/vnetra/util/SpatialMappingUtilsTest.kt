package com.airi.vnetra.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpatialMappingUtilsTest {

    @Before
    fun setup() {
        SpatialMappingUtils.reset()
    }

    @Test
    fun `analyzeTerrain with invalid size returns null`() {
        val shortArray = IntArray(10)
        val result = SpatialMappingUtils.analyzeTerrain(shortArray)
        assertNull("Should return null for non-64 size array", result)
    }

    @Test
    fun `analyzeTerrain with safe distances returns null`() {
        val safeData = IntArray(64) { 3000 }
        val result = SpatialMappingUtils.analyzeTerrain(safeData)
        assertNull("Should return null when all obstacles are far away", result)
    }

    @Test
    fun `analyzeTerrain identifies close object and returns correct centroid`() {
        val data = IntArray(64) { 3000 }
        // Set a close object at center (row 3, col 4 -> index 3*8 + 4 = 28)
        data[28] = 500
        
        val result = SpatialMappingUtils.analyzeTerrain(data)
        
        assertNotNull("Should identify close obstacle", result)
        assertEquals("objek", result!!.type)
        assertEquals(500, result.nearestDistance)
        // Col 4 corresponds to clock direction 12 (from getColumnClockDirection)
        assertEquals(12, result.clockDirection)
    }

    @Test
    fun `analyzeTerrain identifies wall when obstacle spans 4 or more rows`() {
        val data = IntArray(64) { 3000 }
        // Create a wall on column 3 across 4 rows (rows 2, 3, 4, 5)
        val cols = 3
        data[2 * 8 + cols] = 400
        data[3 * 8 + cols] = 400
        data[4 * 8 + cols] = 400
        data[5 * 8 + cols] = 400

        val result = SpatialMappingUtils.analyzeTerrain(data)
        
        assertNotNull("Should identify wall obstacle", result)
        assertEquals("tembok", result!!.type)
        assertEquals(400, result.nearestDistance)
    }
}
