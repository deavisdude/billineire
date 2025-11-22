package com.davisodom.villageoverhaul.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for VolumeMask spatial queries and operations.
 * Part of R002 validation.
 */
public class VolumeMaskTest {

    @Test
    void testBasicContains_InsideVolume() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("test-hut-1")
                .villageId(villageId)
                .bounds(0, 2, 64, 66, 0, 2) // 3x3x3 box from (0,64,0) to (2,66,2)
                .build();

        // Point inside the volume
        assertTrue(vm.contains(1, 65, 1), "Center point should be inside");
        assertTrue(vm.contains(0, 64, 0), "Min corner should be inside");
        assertTrue(vm.contains(2, 66, 2), "Max corner should be inside");
    }

    @Test
    void testBasicContains_OutsideVolume() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("test-hut-2")
                .villageId(villageId)
                .bounds(0, 2, 64, 66, 0, 2)
                .build();

        // Points outside the volume
        assertFalse(vm.contains(3, 65, 1), "Point beyond maxX should be outside");
        assertFalse(vm.contains(-1, 65, 1), "Point before minX should be outside");
        assertFalse(vm.contains(1, 67, 1), "Point above maxY should be outside");
        assertFalse(vm.contains(1, 63, 1), "Point below minY should be outside");
        assertFalse(vm.contains(1, 65, 3), "Point beyond maxZ should be outside");
        assertFalse(vm.contains(1, 65, -1), "Point before minZ should be outside");
    }

    @Test
    void testContains2D_OverlappingYRange() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("test-building")
                .villageId(villageId)
                .bounds(10, 15, 64, 70, 20, 25) // Building from (10,64,20) to (15,70,25)
                .build();

        // Column inside X/Z, Y range overlaps
        assertTrue(vm.contains2D(12, 22, 60, 75), 
                "Column at (12,22) should intersect volume (Y range 60-75 overlaps 64-70)");
        
        assertTrue(vm.contains2D(12, 22, 65, 68), 
                "Column at (12,22) with Y range fully inside should intersect");
        
        assertTrue(vm.contains2D(10, 20, 64, 70), 
                "Column at min corner (10,20) should intersect");
    }

    @Test
    void testContains2D_NoOverlap() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("test-building-2")
                .villageId(villageId)
                .bounds(10, 15, 64, 70, 20, 25)
                .build();

        // Column outside X/Z bounds
        assertFalse(vm.contains2D(5, 22, 60, 75), 
                "Column at X=5 (before minX=10) should not intersect");
        
        assertFalse(vm.contains2D(12, 30, 60, 75), 
                "Column at Z=30 (after maxZ=25) should not intersect");

        // Column inside X/Z but Y range doesn't overlap
        assertFalse(vm.contains2D(12, 22, 10, 20), 
                "Column at (12,22) with Y range 10-20 (below minY=64) should not intersect");
        
        assertFalse(vm.contains2D(12, 22, 80, 90), 
                "Column at (12,22) with Y range 80-90 (above maxY=70) should not intersect");
    }

    @Test
    void testExpand_WithBuffer() {
        UUID villageId = UUID.randomUUID();
        VolumeMask original = new VolumeMask.Builder()
                .structureId("original-structure")
                .villageId(villageId)
                .bounds(0, 2, 64, 66, 0, 2)
                .build();

        // Expand by 2 blocks in all directions
        VolumeMask expanded = original.expand(2);

        // Check expanded bounds
        assertEquals(-2, expanded.getMinX(), "MinX should be reduced by buffer");
        assertEquals(4, expanded.getMaxX(), "MaxX should be increased by buffer");
        assertEquals(62, expanded.getMinY(), "MinY should be reduced by buffer");
        assertEquals(68, expanded.getMaxY(), "MaxY should be increased by buffer");
        assertEquals(-2, expanded.getMinZ(), "MinZ should be reduced by buffer");
        assertEquals(4, expanded.getMaxZ(), "MaxZ should be increased by buffer");

        // Check dimensions updated
        assertEquals(7, expanded.getWidth(), "Width should be 7 (original 3 + 2*2)");
        assertEquals(7, expanded.getHeight(), "Height should be 7 (original 3 + 2*2)");
        assertEquals(7, expanded.getDepth(), "Depth should be 7 (original 3 + 2*2)");

        // Original identifiers preserved
        assertEquals("original-structure", expanded.getStructureId());
        assertEquals(villageId, expanded.getVillageId());
    }

    @Test
    void testExpand_ZeroBuffer() {
        UUID villageId = UUID.randomUUID();
        VolumeMask original = new VolumeMask.Builder()
                .structureId("test-structure")
                .villageId(villageId)
                .bounds(0, 2, 64, 66, 0, 2)
                .build();

        // Expand by 0 should return same instance
        VolumeMask expanded = original.expand(0);
        assertSame(original, expanded, "Expand(0) should return the same instance");
    }

    @Test
    void testExpand_NegativeBuffer_ThrowsException() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("test-structure")
                .villageId(villageId)
                .bounds(0, 2, 64, 66, 0, 2)
                .build();

        assertThrows(IllegalArgumentException.class, () -> vm.expand(-1),
                "Negative buffer should throw IllegalArgumentException");
    }

    @Test
    void testOccupancyBitmap_NullMeansFullOccupancy() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("full-occupancy")
                .villageId(villageId)
                .bounds(0, 1, 0, 1, 0, 1) // 2x2x2 = 8 blocks
                .occupancy(null) // Explicit null for full occupancy
                .build();

        // All points in bounds should be contained when bitmap is null
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    assertTrue(vm.contains(x, y, z), 
                            String.format("Point (%d,%d,%d) should be contained with null bitmap", x, y, z));
                }
            }
        }
    }

    @Test
    void testOccupancyBitmap_PartialOccupancy() {
        UUID villageId = UUID.randomUUID();
        
        // Create a 2x2x2 box with only specific blocks occupied
        BitSet occupancy = new BitSet(8);
        occupancy.set(0); // (0,0,0) -> index 0
        occupancy.set(7); // (1,1,1) -> index 7
        
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("partial-occupancy")
                .villageId(villageId)
                .bounds(0, 1, 0, 1, 0, 1)
                .occupancy(occupancy)
                .build();

        // Points with bits set should be contained
        assertTrue(vm.contains(0, 0, 0), "Point (0,0,0) should be occupied");
        assertTrue(vm.contains(1, 1, 1), "Point (1,1,1) should be occupied");

        // Points without bits set should not be contained
        assertFalse(vm.contains(1, 0, 0), "Point (1,0,0) should not be occupied");
        assertFalse(vm.contains(0, 1, 0), "Point (0,1,0) should not be occupied");
    }

    @Test
    void testFromReceipt_MatchesBounds() {
        UUID villageId = UUID.randomUUID();
        
        // Create a mock PlacementReceipt
        PlacementReceipt receipt = new PlacementReceipt.Builder()
                .structureId("test-building")
                .villageId(villageId)
                .worldName("world")
                .origin(100, 64, 200)
                .rotation(90)
                .bounds(100, 110, 64, 70, 200, 205)
                .dimensions(11, 7, 6)
                .foundationCorners(new PlacementReceipt.CornerSample[] {
                    new PlacementReceipt.CornerSample(100, 64, 200, org.bukkit.Material.STONE),
                    new PlacementReceipt.CornerSample(110, 64, 200, org.bukkit.Material.STONE),
                    new PlacementReceipt.CornerSample(110, 64, 205, org.bukkit.Material.STONE),
                    new PlacementReceipt.CornerSample(100, 64, 205, org.bukkit.Material.STONE)
                })
                .build();

        // Create VolumeMask from receipt
        VolumeMask mask = VolumeMask.fromReceipt(receipt);

        // Verify bounds match exactly
        assertEquals(receipt.getMinX(), mask.getMinX(), "MinX should match receipt");
        assertEquals(receipt.getMaxX(), mask.getMaxX(), "MaxX should match receipt");
        assertEquals(receipt.getMinY(), mask.getMinY(), "MinY should match receipt");
        assertEquals(receipt.getMaxY(), mask.getMaxY(), "MaxY should match receipt");
        assertEquals(receipt.getMinZ(), mask.getMinZ(), "MinZ should match receipt");
        assertEquals(receipt.getMaxZ(), mask.getMaxZ(), "MaxZ should match receipt");

        // Verify identifiers
        assertEquals(receipt.getStructureId(), mask.getStructureId(), "Structure ID should match");
        assertEquals(receipt.getVillageId(), mask.getVillageId(), "Village ID should match");
        assertEquals(receipt.getTimestamp(), mask.getTimestamp(), "Timestamp should match");

        // Verify dimensions calculated correctly
        assertEquals(11, mask.getWidth(), "Width should be 11");
        assertEquals(7, mask.getHeight(), "Height should be 7");
        assertEquals(6, mask.getDepth(), "Depth should be 6");
    }

    @Test
    void testDimensions_CalculatedCorrectly() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("dimension-test")
                .villageId(villageId)
                .bounds(10, 20, 64, 70, 30, 35)
                .build();

        assertEquals(11, vm.getWidth(), "Width should be maxX - minX + 1 = 20 - 10 + 1 = 11");
        assertEquals(7, vm.getHeight(), "Height should be maxY - minY + 1 = 70 - 64 + 1 = 7");
        assertEquals(6, vm.getDepth(), "Depth should be maxZ - minZ + 1 = 35 - 30 + 1 = 6");
    }

    @Test
    void testInvalidBounds_ThrowsException() {
        UUID villageId = UUID.randomUUID();

        // maxX < minX
        assertThrows(IllegalArgumentException.class, () -> {
            new VolumeMask.Builder()
                    .structureId("invalid-x")
                    .villageId(villageId)
                    .bounds(10, 5, 64, 70, 0, 5)
                    .build();
        }, "Should throw when maxX < minX");

        // maxY < minY
        assertThrows(IllegalArgumentException.class, () -> {
            new VolumeMask.Builder()
                    .structureId("invalid-y")
                    .villageId(villageId)
                    .bounds(0, 5, 70, 64, 0, 5)
                    .build();
        }, "Should throw when maxY < minY");

        // maxZ < minZ
        assertThrows(IllegalArgumentException.class, () -> {
            new VolumeMask.Builder()
                    .structureId("invalid-z")
                    .villageId(villageId)
                    .bounds(0, 5, 64, 70, 10, 5)
                    .build();
        }, "Should throw when maxZ < minZ");
    }

    @Test
    void testGetSummary_ContainsKeyInfo() {
        UUID villageId = UUID.randomUUID();
        VolumeMask vm = new VolumeMask.Builder()
                .structureId("summary-test")
                .villageId(villageId)
                .bounds(0, 10, 64, 70, 0, 10)
                .build();

        String summary = vm.getSummary();
        
        assertTrue(summary.contains("summary-test"), "Summary should contain structure ID");
        assertTrue(summary.contains("bounds="), "Summary should contain bounds");
        assertTrue(summary.contains("dims="), "Summary should contain dimensions");
        assertTrue(summary.contains("11x7x11"), "Summary should show correct dimensions");
    }
}
