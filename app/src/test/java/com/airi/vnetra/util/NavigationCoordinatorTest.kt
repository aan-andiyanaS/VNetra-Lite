package com.airi.vnetra.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NavigationCoordinatorTest {

    private lateinit var coordinator: NavigationCoordinator

    @Before
    fun setup() {
        coordinator = NavigationCoordinator()
    }

    @Test
    fun `getDistanceZone returns correct zone based on adaptive threshold`() {
        val adaptiveThreshold = 1000 // mm

        // ZONE_DEKAT is < 0.5 * adaptiveThreshold (if ZONE_NEAR_MULT is 0.5)
        // ZONE_SEDANG is < 1.5 * adaptiveThreshold (if ZONE_MID_MULT is 1.5)
        // ZONE_JAUH is otherwise
        
        // Given defaults VNetraConfig.ZONE_NEAR_MULT = 0.5, VNetraConfig.ZONE_MID_MULT = 1.5 (assuming these values based on TtsAlertManager)
        
        val zoneDekat = coordinator.getDistanceZone(400, adaptiveThreshold)
        val zoneSedang = coordinator.getDistanceZone(1200, adaptiveThreshold)
        val zoneJauh = coordinator.getDistanceZone(2000, adaptiveThreshold)

        assertEquals(NavigationCoordinator.ZONE_DEKAT, zoneDekat)
        assertEquals(NavigationCoordinator.ZONE_SEDANG, zoneSedang)
        assertEquals(NavigationCoordinator.ZONE_JAUH, zoneJauh)
    }

    @Test
    fun `calculateDynamicThreshold skips adaptive calculation when isConverged is false`() {
        // This test proves the bug with the 24-byte payload (where isConverged defaults to 0f)
        val imuData = FloatArray(9) { 0f } 
        // imuData[8] is isConverged = 0f (false)
        
        val baseWarningDist = 1000
        val result = coordinator.calculateDynamicThreshold(
            obstacleDistanceMm = 800,
            objectLabel = "objek",
            imuData = imuData,
            baseWarningDistanceMm = baseWarningDist
        )

        // It should fallback to base warning distance
        assertEquals(baseWarningDist, result.adaptiveThresholdMm)
        assertEquals(0f, result.emaApproachVelocityMmps, 0.001f)
    }
    
    @Test
    fun `calculateDynamicThreshold calculates adaptive threshold when isConverged is true`() {
        val imuData = FloatArray(9) { 0f }
        imuData[5] = 0f // aLin (accelerometer Z/linear)
        imuData[6] = 100f // timestamp 1
        imuData[7] = -500f // vHeadBase
        imuData[8] = 1f // isConverged = true

        val baseWarningDist = 1000

        // First call sets up the previous state
        coordinator.calculateDynamicThreshold(1500, "objek", imuData, baseWarningDist)

        // Second call with different timestamp to simulate approach
        val imuData2 = imuData.copyOf()
        imuData2[6] = 200f // timestamp 2 (+100ms)
        
        val result = coordinator.calculateDynamicThreshold(
            obstacleDistanceMm = 1000, // d_obj reduced by 500 in 100ms
            objectLabel = "objek",
            imuData = imuData2,
            baseWarningDistanceMm = baseWarningDist
        )

        // Adaptive threshold should increase due to approach velocity
        assertTrue("Threshold should be greater than base when approached fast", result.adaptiveThresholdMm >= baseWarningDist)
        assertTrue("Velocity should be > 0", result.emaApproachVelocityMmps > 0f)
    }
}
