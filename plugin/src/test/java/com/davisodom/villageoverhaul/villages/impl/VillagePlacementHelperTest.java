package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VillagePlacementHelper (R011b).
 * Tests rotation-aware AABB computation and collision detection.
 */
public class VillagePlacementHelperTest {
    
    @Test
    @DisplayName("computeRotatedAABB - 0° rotation should match base dimensions")
    public void testComputeRotatedAABB_NoRotation() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 10;
        int depth = 15;
        int height = 8;
        
        int[] bounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 0);
        
        assertEquals(100, bounds[0], "minX should equal origin X");
        assertEquals(109, bounds[1], "maxX should be origin X + width - 1");
        assertEquals(64, bounds[2], "minY should equal origin Y");
        assertEquals(71, bounds[3], "maxY should be origin Y + height - 1");
        assertEquals(200, bounds[4], "minZ should equal origin Z");
        assertEquals(214, bounds[5], "maxZ should be origin Z + depth - 1");
    }
    
    @Test
    @DisplayName("computeRotatedAABB - 90° rotation should swap X and Z dimensions")
    public void testComputeRotatedAABB_90Degrees() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 10; // Will become Z extent after rotation
        int depth = 15; // Will become X extent after rotation
        int height = 8;
        
        int[] bounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 90);
        
        // After 90° rotation: X extent = -depth to 0, Z extent = 0 to width
        assertEquals(85, bounds[0], "minX should be origin X - depth");
        assertEquals(99, bounds[1], "maxX should be origin X - 1");
        assertEquals(64, bounds[2], "minY unchanged");
        assertEquals(71, bounds[3], "maxY unchanged");
        assertEquals(200, bounds[4], "minZ should equal origin Z");
        assertEquals(209, bounds[5], "maxZ should be origin Z + width - 1");
    }
    
    @Test
    @DisplayName("computeRotatedAABB - 180° rotation should maintain dimensions but invert signs")
    public void testComputeRotatedAABB_180Degrees() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 10;
        int depth = 15;
        int height = 8;
        
        int[] bounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 180);
        
        // After 180° rotation: X extent = -width to 0, Z extent = -depth to 0
        assertEquals(90, bounds[0], "minX should be origin X - width");
        assertEquals(99, bounds[1], "maxX should be origin X - 1");
        assertEquals(64, bounds[2], "minY unchanged");
        assertEquals(71, bounds[3], "maxY unchanged");
        assertEquals(185, bounds[4], "minZ should be origin Z - depth");
        assertEquals(199, bounds[5], "maxZ should be origin Z - 1");
    }
    
    @Test
    @DisplayName("computeRotatedAABB - 270° rotation should swap dimensions")
    public void testComputeRotatedAABB_270Degrees() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 10;
        int depth = 15;
        int height = 8;
        
        int[] bounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 270);
        
        // After 270° rotation: X extent = 0 to depth, Z extent = -width to 0
        assertEquals(100, bounds[0], "minX should equal origin X");
        assertEquals(114, bounds[1], "maxX should be origin X + depth - 1");
        assertEquals(64, bounds[2], "minY unchanged");
        assertEquals(71, bounds[3], "maxY unchanged");
        assertEquals(190, bounds[4], "minZ should be origin Z - width");
        assertEquals(199, bounds[5], "maxZ should be origin Z - 1");
    }
    
    @Test
    @DisplayName("checkRotatedAABBCollision - no collision when structures are far apart")
    public void testCheckRotatedAABBCollision_NoCollision() {
        int[] candidateAABB = {100, 109, 64, 71, 200, 214}; // 10x8x15 structure at (100,64,200)
        
        // Existing structure at (150, 64, 250) - far away
        VolumeMask existingMask = createMask(150, 159, 64, 71, 250, 264);
        List<VolumeMask> masks = List.of(existingMask);
        
        boolean collision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 8);
        
        assertFalse(collision, "No collision should be detected when structures are far apart");
    }
    
    @Test
    @DisplayName("checkRotatedAABBCollision - collision detected when structures overlap")
    public void testCheckRotatedAABBCollision_DirectOverlap() {
        int[] candidateAABB = {100, 109, 64, 71, 200, 214}; // 10x8x15 structure at (100,64,200)
        
        // Existing structure overlapping candidate
        VolumeMask existingMask = createMask(105, 114, 64, 71, 205, 219);
        List<VolumeMask> masks = List.of(existingMask);
        
        boolean collision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 0);
        
        assertTrue(collision, "Collision should be detected when structures overlap");
    }
    
    @Test
    @DisplayName("checkRotatedAABBCollision - collision detected within spacing buffer")
    public void testCheckRotatedAABBCollision_WithinBuffer() {
        int[] candidateAABB = {100, 109, 64, 71, 200, 214}; // 10x8x15 structure at (100,64,200)
        
        // Existing structure adjacent (no direct overlap)
        // maxX=109, existing minX=110 → direct distance = 0, but with buffer=8 they should collide
        VolumeMask existingMask = createMask(110, 119, 64, 71, 200, 214);
        List<VolumeMask> masks = List.of(existingMask);
        
        boolean collision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 8);
        
        assertTrue(collision, "Collision should be detected when structures are within spacing buffer");
    }
    
    @Test
    @DisplayName("checkRotatedAABBCollision - no collision outside spacing buffer")
    public void testCheckRotatedAABBCollision_OutsideBuffer() {
        int[] candidateAABB = {100, 109, 64, 71, 200, 214}; // 10x8x15 structure at (100,64,200)
        
        // Existing structure far enough apart (respects buffer)
        // maxX=109, existing minX=120 → distance = 10, buffer=8 → should NOT collide
        VolumeMask existingMask = createMask(120, 129, 64, 71, 200, 214);
        List<VolumeMask> masks = List.of(existingMask);
        
        boolean collision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 8);
        
        assertFalse(collision, "No collision should be detected when structures are outside spacing buffer");
    }
    
    @Test
    @DisplayName("checkRotatedAABBCollision - handles multiple existing masks correctly")
    public void testCheckRotatedAABBCollision_MultipleMasks() {
        int[] candidateAABB = {100, 109, 64, 71, 200, 214}; // 10x8x15 structure at (100,64,200)
        
        // Multiple existing structures: one far, one close
        VolumeMask farMask = createMask(200, 209, 64, 71, 300, 314);
        VolumeMask closeMask = createMask(110, 119, 64, 71, 200, 214);
        List<VolumeMask> masks = List.of(farMask, closeMask);
        
        boolean collision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 8);
        
        assertTrue(collision, "Collision should be detected when at least one mask is within buffer");
    }
    
    @Test
    @DisplayName("checkRotatedAABBCollision - edge case: candidate at village edge with no existing masks")
    public void testCheckRotatedAABBCollision_NoExistingMasks() {
        int[] candidateAABB = {100, 109, 64, 71, 200, 214};
        List<VolumeMask> masks = new ArrayList<>();
        
        boolean collision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 8);
        
        assertFalse(collision, "No collision should be detected when no existing masks exist");
    }
    
    /**
     * Helper to create a VolumeMask for testing.
     */
    private VolumeMask createMask(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return new VolumeMask.Builder()
                .structureId("test-structure")
                .villageId(UUID.randomUUID())
                .bounds(minX, maxX, minY, maxY, minZ, maxZ)
                .build();
    }
}
