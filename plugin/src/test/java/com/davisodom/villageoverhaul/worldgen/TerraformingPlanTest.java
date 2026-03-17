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
    @DisplayName("Planning allows small water patch and plans fill")
    void testPlanAllowsSmallWaterPatch() {
        // Set up flat terrain
        for (int x = 100; x < 110; x++) {
            for (int z = 200; z < 210; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.AIR);
            }
        }

        // Add a small 2x2 water patch at surface (<= 3x3x3)
        for (int x = 103; x < 105; x++) {
            for (int z = 203; z < 205; z++) {
                world.getBlockAt(x, 64, z).setType(Material.WATER);
            }
        }

        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);

        boolean result = plan.plan();

        assertTrue(result);
        assertTrue(plan.isSuccessful());

        boolean hasWaterFill = plan.getPlannedOperations().stream()
                .anyMatch(op -> op.originalMaterial == Material.WATER && op.type == TerraformingPlan.BlockOperation.OperationType.FILL);
        assertTrue(hasWaterFill, "Expected water fill operations for small patch");
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
        // Set up terrain with very large water patch (10x10) that exceeds small water tolerance (3x3x3 = 27 blocks max)
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                world.getBlockAt(100 + x, 64, 200 + z).setType(Material.WATER);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        assertFalse(plan.plan());
        
        assertThrows(IllegalStateException.class, plan::commit);
    }

    @Test
    @DisplayName("Plan preserves sand fills when canopy sits above the natural surface")
    void testPlanPreservesSandBelowCanopy() {
        for (int x = 200; x < 203; x++) {
            for (int z = 300; z < 303; z++) {
                world.getBlockAt(x, 62, z).setType(Material.SAND);
                world.getBlockAt(x, 63, z).setType(Material.AIR);
                world.getBlockAt(x, 64, z).setType(Material.OAK_LEAVES);
            }
        }

        Location origin = new Location(world, 200, 64, 300);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 3, 3, 5);

        assertTrue(plan.plan());

        assertTrue(plan.getDiagnosticsSummary().contains("skippedCanopyColumns=9"),
            "Expected canopy-aware diagnostics to record skipped canopy columns");
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
        
        // Should have grade operations to fill the gap from surface (y=63) to foundation (y=64)
        // GRADE operations fill gaps upward to reach the target foundation level
        List<TerraformingPlan.BlockOperation> operations = plan.getPlannedOperations();
        boolean hasGradeOps = operations.stream()
                .anyMatch(op -> op.type == TerraformingPlan.BlockOperation.OperationType.GRADE);
        assertTrue(hasGradeOps);
        
        // Commit and verify
        plan.commit();
        
        // Foundation level should now be solid (DIRT)
        assertEquals(Material.STONE, world.getBlockAt(100, 64, 200).getType());
    }
    
    // ---- T058: Rollback and atomicity tests ----
    
    @Test
    @DisplayName("T058: Rollback reverts committed vegetation trimming")
    void testRollbackRevertsVegetationTrimming() {
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
        
        // Commit
        assertTrue(plan.commit());
        assertEquals(Material.AIR, world.getBlockAt(100, 64, 200).getType());
        
        // Rollback
        assertTrue(plan.rollback());
        assertTrue(plan.isRolledBack());
        
        // Vegetation should be restored
        assertEquals(Material.TALL_GRASS, world.getBlockAt(100, 64, 200).getType());
    }
    
    @Test
    @DisplayName("T058: Rollback cannot be called before commit")
    void testCannotRollbackBeforeCommit() {
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 10, 10, 5);
        
        plan.plan();
        
        assertThrows(IllegalStateException.class, plan::rollback);
    }
    
    @Test
    @DisplayName("T058: Cannot rollback twice")
    void testCannotRollbackTwice() {
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
        assertTrue(plan.commit());
        assertTrue(plan.rollback());
        
        assertThrows(IllegalStateException.class, plan::rollback);
    }
    
    @Test
    @DisplayName("T058: Applied and skipped ops counts are tracked")
    void testAppliedAndSkippedOpsCounts() {
        // Set up terrain with vegetation
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.TALL_GRASS);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        
        int totalPlanned = plan.getPlannedOperations().size();
        assertTrue(totalPlanned > 0);
        
        // Before commit, counts should be 0
        assertEquals(0, plan.getAppliedOpsCount());
        assertEquals(0, plan.getSkippedOpsCount());
        
        // Commit
        assertTrue(plan.commit());
        
        // All ops should be applied, none skipped (no concurrent modifications in test)
        assertEquals(totalPlanned, plan.getAppliedOpsCount());
        assertEquals(0, plan.getSkippedOpsCount());
    }
    
    @Test
    @DisplayName("T058: Applied operations are tracked for rollback")
    void testAppliedOperationsTrackedForRollback() {
        // Set up terrain with vegetation
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.TALL_GRASS);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        assertTrue(plan.commit());
        
        // Applied operations should be tracked
        List<TerraformingPlan.AppliedOperation> appliedOps = plan.getAppliedOperations();
        assertFalse(appliedOps.isEmpty());
        assertEquals(plan.getAppliedOpsCount(), appliedOps.size());
        
        // Each applied operation should record the actual original material
        TerraformingPlan.AppliedOperation firstOp = appliedOps.get(0);
        assertNotNull(firstOp.actualOriginalMaterial);
        assertNotNull(firstOp.appliedMaterial);
    }
    
    @Test
    @DisplayName("T058: Diagnostics summary includes applied/skipped counts")
    void testDiagnosticsSummaryIncludesAppliedSkipped() {
        // Set up terrain with vegetation
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 64, z).setType(Material.TALL_GRASS);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        plan.plan();
        plan.commit();
        
        String diagnostics = plan.getDiagnosticsSummary();
        assertTrue(diagnostics.contains("applied="));
        assertTrue(diagnostics.contains("skipped="));
        assertTrue(diagnostics.contains("committed=true"));
        assertTrue(diagnostics.contains("rolledBack=false"));
    }
    
    @Test
    @DisplayName("T058: Empty plan commit and rollback succeeds")
    void testEmptyPlanCommitAndRollback() {
        // Set up flat terrain at foundation level - no gaps, no vegetation to trim
        // Surface at y=64 (same as foundation) means no grading needed
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 63, z).setType(Material.STONE);
                world.getBlockAt(x, 64, z).setType(Material.STONE); // Surface at foundation level
                world.getBlockAt(x, 65, z).setType(Material.AIR);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        
        // Empty plan (nothing to do since surface is already at foundation level)
        assertEquals(0, plan.getPlannedOperations().size());
        
        // Commit and rollback should succeed for empty plan
        assertTrue(plan.commit());
        assertEquals(0, plan.getAppliedOpsCount());
        
        assertTrue(plan.rollback());
        assertTrue(plan.isRolledBack());
    }
    
    // ---- T065: Surface material preservation tests ----
    
    @Test
    @DisplayName("T065: Grading preserves GRASS_BLOCK surface material on top layer")
    void testGradingPreservesGrassBlockSurface() {
        // Set up terrain: grass surface at y=62, need to fill gap to foundation at y=64
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 61, z).setType(Material.DIRT);
                world.getBlockAt(x, 62, z).setType(Material.GRASS_BLOCK); // Surface is grass
                world.getBlockAt(x, 63, z).setType(Material.AIR); // Gap
                world.getBlockAt(x, 64, z).setType(Material.AIR); // Gap - foundation target
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        
        // Check that GRADE operations preserve grass for top layer
        List<TerraformingPlan.BlockOperation> gradeOps = plan.getPlannedOperations().stream()
                .filter(op -> op.type == TerraformingPlan.BlockOperation.OperationType.GRADE)
                .toList();
        assertFalse(gradeOps.isEmpty(), "Should have grading operations");
        
        // The top layer (y=64) should use GRASS_BLOCK, lower layers should use DIRT
        boolean hasGrassAtTop = gradeOps.stream()
                .anyMatch(op -> op.y == 64 && op.targetMaterial == Material.GRASS_BLOCK);
        boolean hasDirtBelow = gradeOps.stream()
                .anyMatch(op -> op.y == 63 && op.targetMaterial == Material.DIRT);
        
        assertTrue(hasGrassAtTop, "Top layer should preserve GRASS_BLOCK");
        assertTrue(hasDirtBelow, "Below surface should use DIRT");
        
        // Commit and verify world state
        plan.commit();
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(100, 64, 200).getType(),
                "Foundation level should be GRASS_BLOCK to prevent dirt scars");
        assertEquals(Material.DIRT, world.getBlockAt(100, 63, 200).getType(),
                "Below surface should be DIRT");
    }
    
    @Test
    @DisplayName("T065: Grading preserves SAND surface material in desert biomes")
    void testGradingPreservesSandSurface() {
        // Set up terrain: sand surface at y=62, need to fill gap to foundation at y=64
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 61, z).setType(Material.SANDSTONE);
                world.getBlockAt(x, 62, z).setType(Material.SAND); // Desert surface
                world.getBlockAt(x, 63, z).setType(Material.AIR);
                world.getBlockAt(x, 64, z).setType(Material.AIR);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        plan.commit();
        
        // Top layer should be SAND, not DIRT
        assertEquals(Material.SAND, world.getBlockAt(100, 64, 200).getType(),
                "Foundation level should be SAND to preserve desert appearance");
    }
    
    @Test
    @DisplayName("T065: Grading preserves PODZOL surface material in taiga biomes")
    void testGradingPreservesPodzolSurface() {
        // Set up terrain: podzol surface at y=62, need to fill gap
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 61, z).setType(Material.DIRT);
                world.getBlockAt(x, 62, z).setType(Material.PODZOL); // Taiga surface
                world.getBlockAt(x, 63, z).setType(Material.AIR);
                world.getBlockAt(x, 64, z).setType(Material.AIR);
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        plan.commit();
        
        // Top layer should be PODZOL, not DIRT
        assertEquals(Material.PODZOL, world.getBlockAt(100, 64, 200).getType(),
                "Foundation level should be PODZOL to preserve taiga appearance");
    }
    
    @Test
    @DisplayName("T065: Rollback restores original GRASS_BLOCK surface state after grading")
    void testRollbackRestoresGrassBlockSurface() {
        // Set up terrain with grass surface that will be graded
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 61, z).setType(Material.DIRT);
                world.getBlockAt(x, 62, z).setType(Material.GRASS_BLOCK);
                world.getBlockAt(x, 63, z).setType(Material.AIR); // Will be filled with DIRT
                world.getBlockAt(x, 64, z).setType(Material.AIR); // Will be filled with GRASS_BLOCK
            }
        }
        
        Location origin = new Location(world, 100, 64, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        assertTrue(plan.commit());
        
        // Verify commit changed the blocks
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(100, 64, 200).getType());
        assertEquals(Material.DIRT, world.getBlockAt(100, 63, 200).getType());
        
        // Rollback
        assertTrue(plan.rollback());
        
        // Verify rollback restored original AIR blocks
        assertEquals(Material.AIR, world.getBlockAt(100, 64, 200).getType(),
                "Rollback should restore original AIR at y=64");
        assertEquals(Material.AIR, world.getBlockAt(100, 63, 200).getType(),
                "Rollback should restore original AIR at y=63");
    }
    
    @Test
    @DisplayName("T065: Filling gaps does not create dirt scars on grassy terrain")
    void testFillingDoesNotCreateDirtScars() {
        // Set up terrain with a small gap below foundation on grassy terrain
        // Surface at y=63 (grass), foundation target at y=65, gap at y=64
        for (int x = 100; x < 105; x++) {
            for (int z = 200; z < 205; z++) {
                world.getBlockAt(x, 62, z).setType(Material.DIRT);
                world.getBlockAt(x, 63, z).setType(Material.GRASS_BLOCK); // Surface
                world.getBlockAt(x, 64, z).setType(Material.AIR); // Gap below structure
                world.getBlockAt(x, 65, z).setType(Material.AIR); // Foundation level
            }
        }
        
        Location origin = new Location(world, 100, 65, 200);
        TerraformingPlan plan = TerraformingPlan.forSite(world, origin, 5, 5, 5);
        
        assertTrue(plan.plan());
        
        // Verify that fill operations use DIRT underground and preserve grass appearance at exposed level
        // Since foundation is at y=65, fills will be at y=64 (below foundation)
        List<TerraformingPlan.BlockOperation> fillOps = plan.getPlannedOperations().stream()
                .filter(op -> op.type == TerraformingPlan.BlockOperation.OperationType.FILL || 
                              op.type == TerraformingPlan.BlockOperation.OperationType.GRADE)
                .toList();
        
        // All fill operations should target y=64, which is below foundation (y=65)
        // Since y=64 is not the exposed surface layer for the structure, DIRT is appropriate here
        // But if y=64 becomes the new top of the fill, it should get grass
        assertTrue(fillOps.isEmpty() || fillOps.stream()
                .allMatch(op -> op.targetMaterial == Material.DIRT || op.targetMaterial == Material.GRASS_BLOCK),
                "Fill operations should use DIRT or preserve grass surface");
    }
}
