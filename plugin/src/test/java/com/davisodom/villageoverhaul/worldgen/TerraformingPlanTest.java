package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.World;
import com.davisodom.villageoverhaul.test.FakeWorld;
import org.bukkit.block.Block;
import org.mockito.Mockito;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TerraformingPlan.
 * T051: Tests deferred commit semantics and rollback behavior.
 */
@DisplayName("TerraformingPlan Tests")
class TerraformingPlanTest {
    
    private World world;
    private com.davisodom.villageoverhaul.test.FakeWorld fake;
    
    @BeforeAll
    static void setUpAll() { }
    
    @AfterAll
    static void tearDownAll() { }
    
    @BeforeEach
    void setUp() {
        fake = new FakeWorld();
        world = fake.getWorld();
    }

    // Using FakeWorld for block semantics
    
    @Test
    @DisplayName("Plan creation with origin and dimensions")
    void testPlanCreationWithOrigin() {
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertNotNull(plan);
        assertFalse(plan.isSuccessful());
        assertFalse(plan.isCommitted());
    }
    
    @Test
    @DisplayName("Plan creation with explicit bounds")
    void testPlanCreationWithBounds() {
        int[] bounds = {100, 109, 64, 68, 200, 209};
        TerraformingPlan plan = TerraformingPlan.forBounds(world, bounds);
        
        assertNotNull(plan);
        int[] returnedBounds = plan.getBounds();
        assertArrayEquals(bounds, returnedBounds);
    }
    
    @Test
    @DisplayName("Plan with invalid bounds throws exception")
    void testInvalidBoundsThrowsException() {
        int[] invalidBounds = {100, 109, 64}; // Only 3 elements
        
        assertThrows(IllegalArgumentException.class, () -> {
            TerraformingPlan.forBounds(world, invalidBounds);
        });
    }
    
    @Test
    @DisplayName("Planning on flat terrain succeeds")
    void testPlanOnFlatTerrain() {
        // Set up flat dirt terrain
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.DIRT);
                world.getBlockAt(x, 64, z).setType(Material.AIR);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        boolean result = plan.plan();
        
        assertTrue(result);
        assertTrue(plan.isSuccessful());
        assertNull(plan.getRejectionReason());
    }
    
    @Test
    @DisplayName("Planning on water terrain fails without modifying world")
    void testPlanOnWaterFails() {
        // Set up terrain with water
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.DIRT);
                world.getBlockAt(x, 64, z).setType(Material.WATER);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        boolean result = plan.plan();
        
        assertFalse(result);
        assertFalse(plan.isSuccessful());
        assertNotNull(plan.getRejectionReason());
        assertTrue(plan.getRejectionReason().contains("fluid"));
        
        // Verify world was not modified
        assertEquals(Material.WATER, world.getBlockAt(100, 64, 200).getType());
    }
    
    @Test
    @DisplayName("Planning on lava terrain fails")
    void testPlanOnLavaFails() {
        // Set up terrain with lava
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.STONE);
                world.getBlockAt(x, 64, z).setType(Material.LAVA);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        boolean result = plan.plan();
        
        assertFalse(result);
        assertTrue(plan.getRejectionReason().contains("fluid") || plan.getRejectionReason().contains("LAVA"));
    }
    
    @Test
    @DisplayName("Plan records vegetation trimming operations")
    void testPlanRecordsVegetationTrimming() {
        // Set up terrain with grass and vegetation
        // Use TALL_GRASS which is available in MockBukkit (SHORT_GRASS may not be)
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.TALL_GRASS);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertTrue(plan.plan());
        
        List<TerraformingPlan.BlockOperation> operations = plan.getPlannedOperations();
        assertFalse(operations.isEmpty());
        
        // Should have trim operations for vegetation
        boolean hasTrimOps = operations.stream()
                .anyMatch(op -> op.type == TerraformingPlan.BlockOperation.OperationType.TRIM);
        assertTrue(hasTrimOps);
        
        // World should NOT be modified yet (deferred commit)
        assertEquals(Material.TALL_GRASS, world.getBlockAt(100, 64, 200).getType());
    }
    
    @Test
    @DisplayName("Commit applies planned operations to world")
    void testCommitAppliesOperations() {
        // Set up terrain with vegetation
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.TALL_GRASS);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertTrue(plan.plan());
        
        // Before commit - world unchanged
        assertEquals(Material.TALL_GRASS, world.getBlockAt(100, 64, 200).getType());
        
        // Commit
        assertTrue(plan.commit());
        assertTrue(plan.isCommitted());
        
        // After commit - vegetation should be trimmed
        assertEquals(Material.AIR, world.getBlockAt(100, 64, 200).getType());
    }
    
    @Test
    @DisplayName("Abandoned plan leaves world unchanged")
    void testAbandonedPlanLeavesWorldUnchanged() {
        // Set up terrain with vegetation
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.TALL_GRASS);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertTrue(plan.plan());
        assertFalse(plan.getPlannedOperations().isEmpty());
        
        // Simply discard the plan without calling commit()
        // Simulate this by just not calling commit and checking world state
        
        // World should remain unchanged
        assertEquals(Material.TALL_GRASS, world.getBlockAt(100, 64, 200).getType());
        assertFalse(plan.isCommitted());
    }
    
    @Test
    @DisplayName("Cannot commit failed plan")
    void testCannotCommitFailedPlan() {
        // Set up terrain with water (will fail)
        world.getBlockAt(100, 64, 200).setType(Material.WATER);
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertFalse(plan.plan());
        
        assertThrows(IllegalStateException.class, plan::commit);
    }
    
    @Test
    @DisplayName("Cannot call plan() twice")
    void testCannotPlanTwice() {
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        plan.plan(); // First call
        
        assertThrows(IllegalStateException.class, plan::plan);
    }
    
    @Test
    @DisplayName("Cannot commit before plan()")
    void testCannotCommitBeforePlan() {
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertThrows(IllegalStateException.class, plan::commit);
    }
    
    @Test
    @DisplayName("Cannot commit twice")
    void testCannotCommitTwice() {
        // Set up flat terrain
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.DIRT);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertTrue(plan.plan());
        assertTrue(plan.commit());
        
        assertThrows(IllegalStateException.class, plan::commit);
    }
    
    @Test
    @DisplayName("Diagnostics summary contains useful information")
    void testDiagnosticsSummary() {
        // Set up terrain with vegetation
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.TALL_GRASS);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        plan.plan();
        
        String diagnostics = plan.getDiagnosticsSummary();
        assertNotNull(diagnostics);
        assertTrue(diagnostics.contains("bounds="));
        assertTrue(diagnostics.contains("ops="));
        assertTrue(diagnostics.contains("success="));
    }
    
    @Test
    @DisplayName("Plan fills gaps in foundation")
    void testPlanFillsFoundationGaps() {
        // Set up terrain with shallow gap at foundation level (surface at y=63, foundation target 64)
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 62, z).setType(Material.STONE);
                world.getBlockAt(x, 63, z).setType(Material.STONE); // surface at y=63
                world.getBlockAt(x, 64, z).setType(Material.AIR);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertTrue(plan.plan());
        
        // Should have fill operations
        List<TerraformingPlan.BlockOperation> operations = plan.getPlannedOperations();
        boolean hasFillOps = operations.stream()
                .anyMatch(op -> op.type == TerraformingPlan.BlockOperation.OperationType.FILL);
        assertTrue(hasFillOps);
        
        // Commit and verify
        plan.commit();
        
        // Foundation level should now be solid (DIRT)
        assertEquals(Material.DIRT, world.getBlockAt(100, 64, 200).getType());
    }
}
