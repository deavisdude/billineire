package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.bukkit.World;
import org.bukkit.Location;

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
        
        // After 90° rotation (WorldEdit rotateY): (x,z) -> (z, -x)
        // Original corners (0,0,0) to (10,8,15) become X=0..15, Z=-10..0
        assertEquals(100, bounds[0], "minX should be origin X");
        assertEquals(114, bounds[1], "maxX should be origin X + depth - 1");
        assertEquals(64, bounds[2], "minY unchanged");
        assertEquals(71, bounds[3], "maxY unchanged");
        assertEquals(190, bounds[4], "minZ should be origin Z - width");
        assertEquals(199, bounds[5], "maxZ should be origin Z - 1");
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
        
        // After 270° rotation (WorldEdit rotateY): (x,z) -> (-z, x)
        // Original corners (0,0,0) to (10,8,15) become X=-15..0, Z=0..10
        assertEquals(85, bounds[0], "minX should be origin X - depth");
        assertEquals(99, bounds[1], "maxX should be origin X - 1");
        assertEquals(64, bounds[2], "minY unchanged");
        assertEquals(71, bounds[3], "maxY unchanged");
        assertEquals(200, bounds[4], "minZ should equal origin Z");
        assertEquals(209, bounds[5], "maxZ should be origin Z + width - 1");
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
    @DisplayName("checkRotatedAABBCollision - edge-touching respects inclusive bounds")
    public void testCheckRotatedAABBCollision_EdgeTouching() {
        int[] candidateAABB = {0, 4, 64, 70, 0, 4}; // 5x7x5 structure at origin

        // Mask starts exactly after candidate on X (no overlap at buffer=0)
        VolumeMask edgeMask = createMask(5, 9, 64, 70, 0, 4);
        List<VolumeMask> masks = List.of(edgeMask);

        boolean noBufferCollision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 0);
        assertFalse(noBufferCollision, "Edge-touching without buffer should not collide");

        boolean withBufferCollision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 1);
        assertTrue(withBufferCollision, "Spacing buffer should turn edge-touching into collision");
    }

    @Test
    @DisplayName("checkRotatedAABBCollision - buffer expands symmetrically")
    public void testCheckRotatedAABBCollision_BufferSymmetry() {
        int[] candidateAABB = {10, 14, 64, 70, 10, 14};

        VolumeMask mask = createMask(20, 24, 64, 70, 10, 14);
        List<VolumeMask> masks = List.of(mask);

        boolean noBufferCollision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 0);
        assertFalse(noBufferCollision, "No collision expected without buffer");

        boolean withBufferCollision = VillagePlacementHelper.checkRotatedAABBCollision(candidateAABB, masks, 6);
        assertTrue(withBufferCollision, "Symmetric buffer expansion should detect collision");
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

    @Test
    @DisplayName("T026d11 - zero-placement should record diagnostic summary in metadata store")
    public void testZeroPlacementRecordsSummary() {
        // Use Mockito mocks and forced zero-placement flag to avoid MockBukkit
        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);

        // Avoid loading WorldEdit/FAWE in tests by injecting a noop/mock StructureService
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(com.davisodom.villageoverhaul.worldgen.StructureService.class);
        Mockito.when(mockStructure.getStructureDimensions(Mockito.anyString())).thenReturn(Optional.empty());

        com.davisodom.villageoverhaul.cultures.CultureService mockCulture = Mockito.mock(com.davisodom.villageoverhaul.cultures.CultureService.class);
        Mockito.when(mockCulture.get(Mockito.anyString())).thenReturn(Optional.empty());

        VillagePlacementServiceImpl service = new VillagePlacementServiceImpl(mockStructure, store, mockCulture);

        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 0, 64, 0);
        long seed = 9999L;

        try {
            // Force the zero-placement branch in placeVillage
            System.setProperty("vo.test.forceZeroPlacement", "true");

            Optional<java.util.UUID> result = service.placeVillage(world, origin, "nonexistent-culture", seed);
            assertFalse(result.isPresent(), "Expected no village to be successfully placed");

            // Find the registered village (should be created even on zero-placement)
            java.util.UUID foundId = null;
            for (VillageMetadataStore.VillageMetadata meta : store.getAllVillages()) {
                if (meta.getOrigin().getBlockX() == origin.getBlockX() && meta.getSeed() == seed) {
                    foundId = meta.getVillageId();
                    break;
                }
            }

            assertNotNull(foundId, "Village should have been registered in metadata store");

            Optional<VillageMetadataStore.PlacementFailureSummary> summary = store.getLastPlacementFailureSummary(foundId);
            assertTrue(summary.isPresent(), "Placement failure summary should be recorded for zero-placement runs");
            VillageMetadataStore.PlacementFailureSummary s = summary.get();
            assertEquals(0, s.attempts, "Attempts should be zero when no candidates exist");
            assertEquals(0, s.fluid, "Fluid rejections should be zero");
            assertEquals(0, s.steep, "Steep rejections should be zero");
            assertEquals(0, s.blocked, "Blocked rejections should be zero");
            assertEquals(0, s.spacing, "Spacing rejections should be zero");
            assertEquals(0, s.overlap, "Overlap rejections should be zero");

        } finally {
            System.clearProperty("vo.test.forceZeroPlacement");
        }
    }
    
    @Test
    @DisplayName("T087b - Candidate AABB at 0° rotation matches expected placement bounds")
    public void testCandidateVsPlacementAABB_0Degrees() {
        // Test parameters: origin (100, 64, 200), width=20, depth=30, height=10
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 20;
        int depth = 30;
        int height = 10;
        
        // Candidate AABB from VillagePlacementHelper
        int[] candidateBounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 0);
        
        // Expected bounds (0° rotation: origin is min corner)
        // X: 100 to 100+20-1=119
        // Y: 64 to 64+10-1=73
        // Z: 200 to 200+30-1=229
        int[] expectedBounds = {100, 119, 64, 73, 200, 229};
        
        assertArrayEquals(expectedBounds, candidateBounds, 
            "Candidate AABB at 0° should match expected bounds");
    }
    
    @Test
    @DisplayName("T087b - Candidate AABB at 90° rotation matches expected placement bounds")
    public void testCandidateVsPlacementAABB_90Degrees() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 20;
        int depth = 30;
        int height = 10;
        
        int[] candidateBounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 90);
        
        // After 90° rotation: (x,z) -> (z, -x)
        // Corners (0,0,0), (20,0,0), (0,0,30), (20,0,30)
        // Become (0,0), (0,-20), (30,0), (30,-20)
        // X range: [0, 30], Z range: [-20, 0]
        // World coords: X [100, 129], Z [180, 199]
        int[] expectedBounds = {100, 129, 64, 73, 180, 199};
        
        assertArrayEquals(expectedBounds, candidateBounds,
            "Candidate AABB at 90° should match expected bounds");
    }
    
    @Test
    @DisplayName("T087b - Candidate AABB at 180° rotation matches expected placement bounds")
    public void testCandidateVsPlacementAABB_180Degrees() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 20;
        int depth = 30;
        int height = 10;
        
        int[] candidateBounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 180);
        
        // After 180° rotation: (x,z) -> (-x, -z)
        // Corners (0,0,0), (20,0,0), (0,0,30), (20,0,30)
        // Become (0,0), (-20,0), (0,-30), (-20,-30)
        // X range: [-20, 0], Z range: [-30, 0]
        // World coords: X [80, 99], Z [170, 199]
        int[] expectedBounds = {80, 99, 64, 73, 170, 199};
        
        assertArrayEquals(expectedBounds, candidateBounds,
            "Candidate AABB at 180° should match expected bounds");
    }
    
    @Test
    @DisplayName("T087b - Candidate AABB at 270° rotation matches expected placement bounds")
    public void testCandidateVsPlacementAABB_270Degrees() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 100, 64, 200);
        int width = 20;
        int depth = 30;
        int height = 10;
        
        int[] candidateBounds = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 270);
        
        // After 270° rotation: (x,z) -> (-z, x)
        // Corners (0,0,0), (20,0,0), (0,0,30), (20,0,30)
        // Become (0,0), (0,20), (-30,0), (-30,20)
        // X range: [-30, 0], Z range: [0, 20]
        // World coords: X [70, 99], Z [200, 219]
        int[] expectedBounds = {70, 99, 64, 73, 200, 219};
        
        assertArrayEquals(expectedBounds, candidateBounds,
            "Candidate AABB at 270° should match expected bounds");
    }
    
    @Test
    @DisplayName("T087b - Rotation consistency: dimensions swap correctly for 90° and 270°")
    public void testRotationConsistency_DimensionSwapping() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 0, 0, 0);
        int width = 15;
        int depth = 25;
        int height = 10;
        
        // At 0°: bounds should be width x depth
        int[] bounds0 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 0);
        int extent0X = bounds0[1] - bounds0[0] + 1;
        int extent0Z = bounds0[5] - bounds0[4] + 1;
        assertEquals(width, extent0X, "0° rotation X extent should equal width");
        assertEquals(depth, extent0Z, "0° rotation Z extent should equal depth");
        
        // At 90°: bounds should be depth x width (swapped)
        int[] bounds90 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 90);
        int extent90X = bounds90[1] - bounds90[0] + 1;
        int extent90Z = bounds90[5] - bounds90[4] + 1;
        assertEquals(depth, extent90X, "90° rotation X extent should equal depth");
        assertEquals(width, extent90Z, "90° rotation Z extent should equal width");
        
        // At 180°: bounds should be width x depth (same as 0°)
        int[] bounds180 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 180);
        int extent180X = bounds180[1] - bounds180[0] + 1;
        int extent180Z = bounds180[5] - bounds180[4] + 1;
        assertEquals(width, extent180X, "180° rotation X extent should equal width");
        assertEquals(depth, extent180Z, "180° rotation Z extent should equal depth");
        
        // At 270°: bounds should be depth x width (swapped)
        int[] bounds270 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 270);
        int extent270X = bounds270[1] - bounds270[0] + 1;
        int extent270Z = bounds270[5] - bounds270[4] + 1;
        assertEquals(depth, extent270X, "270° rotation X extent should equal depth");
        assertEquals(width, extent270Z, "270° rotation Z extent should equal width");
    }
    
    @Test
    @DisplayName("T087b - Y bounds unchanged across all rotations")
    public void testRotationConsistency_YUnchanged() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 50, 100, 50);
        int width = 20;
        int depth = 30;
        int height = 15;
        
        int[] bounds0 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 0);
        int[] bounds90 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 90);
        int[] bounds180 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 180);
        int[] bounds270 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 270);
        
        // Y bounds should be identical across all rotations
        assertEquals(bounds0[2], bounds90[2], "0° and 90° minY should match");
        assertEquals(bounds0[3], bounds90[3], "0° and 90° maxY should match");
        assertEquals(bounds0[2], bounds180[2], "0° and 180° minY should match");
        assertEquals(bounds0[3], bounds180[3], "0° and 180° maxY should match");
        assertEquals(bounds0[2], bounds270[2], "0° and 270° minY should match");
        assertEquals(bounds0[3], bounds270[3], "0° and 270° maxY should match");
        
        int expectedMinY = origin.getBlockY();
        int expectedMaxY = origin.getBlockY() + height - 1;
        assertEquals(expectedMinY, bounds0[2], "minY should equal origin Y");
        assertEquals(expectedMaxY, bounds0[3], "maxY should equal origin Y + height - 1");
    }
    
    @Test
    @DisplayName("T087b - Rotation determinism: multiple calls produce identical results")
    public void testRotationDeterminism() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 123, 456, 789);
        int width = 17;
        int depth = 28;
        int height = 12;
        
        for (int rotation : new int[]{0, 90, 180, 270}) {
            int[] bounds1 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, rotation);
            int[] bounds2 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, rotation);
            int[] bounds3 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, rotation);
            
            assertArrayEquals(bounds1, bounds2, "Rotation " + rotation + "° call 1 and 2 should match");
            assertArrayEquals(bounds2, bounds3, "Rotation " + rotation + "° call 2 and 3 should match");
        }
    }
    
    @Test
    @DisplayName("T087b - Four rotations form a cycle: 0→90→180→270→0")
    public void testRotationCycle() {
        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 0, 0, 0);
        int width = 10;
        int depth = 20;
        int height = 8;
        
        int[] bounds0 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 0);
        int[] bounds90 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 90);
        int[] bounds180 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 180);
        int[] bounds270 = VillagePlacementHelper.computeRotatedAABB(origin, width, depth, height, 270);
        
        // Create a second rotation of bounds90 (should approach 180)
        // This validates that the rotation is composable
        // X extent: 0→90 swaps (10,20)→(20,10), 90→180 should negate both → (-20,-10) in relative coords
        
        // Verify extents form expected pattern
        int ext0X = bounds0[1] - bounds0[0] + 1;
        int ext0Z = bounds0[5] - bounds0[4] + 1;
        int ext90X = bounds90[1] - bounds90[0] + 1;
        int ext90Z = bounds90[5] - bounds90[4] + 1;
        int ext180X = bounds180[1] - bounds180[0] + 1;
        int ext180Z = bounds180[5] - bounds180[4] + 1;
        int ext270X = bounds270[1] - bounds270[0] + 1;
        int ext270Z = bounds270[5] - bounds270[4] + 1;
        
        // Pattern: 0 and 180 have same extents; 90 and 270 have swapped extents
        assertEquals(ext0X, ext180X, "0° and 180° X extents should match");
        assertEquals(ext0Z, ext180Z, "0° and 180° Z extents should match");
        assertEquals(ext90X, ext270X, "90° and 270° X extents should match");
        assertEquals(ext90Z, ext270Z, "90° and 270° Z extents should match");
        
        // 0° and 90° should have swapped extents
        assertEquals(ext0X, ext90Z, "0° X extent should equal 90° Z extent");
        assertEquals(ext0Z, ext90X, "0° Z extent should equal 90° X extent");
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
