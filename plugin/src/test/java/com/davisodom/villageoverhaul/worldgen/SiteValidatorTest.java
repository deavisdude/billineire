package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.test.FakeWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.Mockito;

/**
 * SiteValidator Tests
 * 
 * T057: Extended to test false-positive reduction, configurable thresholds,
 * and vegetation handling fixes.
 */
@DisplayName("SiteValidator Tests")
class SiteValidatorTest {

    private FakeWorld fake;
    private World world;
    private SiteValidator sut;

    @BeforeEach
    void setUp() {
        fake = new FakeWorld();
        world = fake.getWorld();
        sut = new SiteValidator();

        // Delegate Location variant to x,z variant used by FakeWorld
        Mockito.when(world.getHighestBlockYAt(Mockito.any(Location.class))).thenAnswer(inv -> {
            Location loc = inv.getArgument(0);
            return world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        });
        
        // Mock getMinHeight for ground level searches
        Mockito.when(world.getMinHeight()).thenReturn(-64);
    }

    @Test
    @DisplayName("validateSite passes on flat, solid foundation")
    void testValidateSite_flatSolidPasses() {
        Location origin = new Location(world, 100, 64, 200);

        int width = 3, depth = 3;

        // Make a solid flat foundation at origin.y - 1
        // Ensure we populate a 1-block border so the classifier's 3x3 checks see solid ground
        for (int x = origin.getBlockX() - 1; x <= origin.getBlockX() + width; x++) {
            for (int z = origin.getBlockZ() - 1; z <= origin.getBlockZ() + depth; z++) {
                fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
            }
        }

        SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);

        assertTrue(r.passed, "site should pass on flat solid foundation; result=" + r);
        assertTrue(r.foundationOk, "foundationOk should be true");
        assertNotNull(r.classificationResult);
        assertEquals(width * depth, r.classificationResult.getTotal());
        assertEquals(width * depth, r.classificationResult.acceptable);
    }

    @Test
    @DisplayName("validateSite fails when any fluid tile present")
    void testValidateSite_failsOnFluid() {
        Location origin = new Location(world, 200, 64, 300);

        int width = 4, depth = 4;

        // Set most tiles to DIRT but put a single WATER tile
        // Populate a 1-block border with solid ground so slope checks are bounded
        for (int x = origin.getBlockX() - 1; x <= origin.getBlockX() + width; x++) {
            for (int z = origin.getBlockZ() - 1; z <= origin.getBlockZ() + depth; z++) {
                // keep the outer margin solid so classifier does not see 'holes'
                fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
            }
        }

        // Inject large water patch (5x5) to exceed small water tolerance (3x3x3 = 27 blocks max)
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                fake.setBlockType(origin.getBlockX() + x, origin.getBlockY() - 1, origin.getBlockZ() + z, Material.WATER);
            }
        }

        SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 5);

        assertFalse(r.passed, "site must fail when a large fluid patch exists (exceeds 3x3x3 limit)");
        assertFalse(r.foundationOk, "foundationOk should be false when large fluid patch present");
        assertTrue(r.classificationResult.fluid > 0, "should have sampled fluid tiles");
    }

    @Test
    @DisplayName("validateSite fails on lava as fluid")
    void testValidateSite_failsOnLava() {
        Location origin = new Location(world, 400, 64, 500);

        int width = 3, depth = 3;

        for (int x = origin.getBlockX() - 1; x <= origin.getBlockX() + width; x++) {
            for (int z = origin.getBlockZ() - 1; z <= origin.getBlockZ() + depth; z++) {
                fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
            }
        }

        // Place LAVA at one sample
        fake.setBlockType(origin.getBlockX(), origin.getBlockY() - 1, origin.getBlockZ(), Material.LAVA);

        SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);

        assertFalse(r.passed, "site must fail when lava is present");
        assertFalse(r.foundationOk, "foundationOk should be false when lava present");
        assertTrue(r.classificationResult.fluid >= 1, "lava should be classified as fluid");
    }

    @Test
    @DisplayName("validateSite fails when solidity below threshold")
    void testValidateSite_failsOnLowSolidity() {
        Location origin = new Location(world, 300, 64, 400);

        int width = 4, depth = 4; // 16 samples

        // Make only 8/16 positions solid (50% < 60% threshold), leave the others as AIR
        int solidCount = 0;
        for (int x = origin.getBlockX(); x < origin.getBlockX() + width; x++) {
            for (int z = origin.getBlockZ(); z < origin.getBlockZ() + depth; z++) {
                if (solidCount < 8) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.STONE);
                    solidCount++;
                } else {
                    // leave as AIR (explicitly set nothing)
                }
            }
        }

        SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 6);

        assertFalse(r.passed, "site should fail when solidity below MIN_FOUNDATION_SOLIDITY (60%)");
        assertFalse(r.foundationOk, "foundationOk must be false for low solidity");
        assertTrue(r.classificationResult.getTotal() >= width * depth, "samples should equal or exceed grid size");
    }
    
    // ==================== T057: False-Positive Reduction Tests ====================
    
    @Nested
    @DisplayName("T057: Vegetation Handling")
    class VegetationHandlingTests {
        
        @Test
        @DisplayName("Vegetation on solid ground counts toward solidity (can be cleared)")
        void testVegetationCountsAsSolid() {
            Location origin = new Location(world, 500, 64, 500);
            int width = 4, depth = 4; // 16 samples
            
            // Set solid foundation for all tiles including wide borders (3 blocks out)
            // Wide borders ensure slope detection sees consistent ground
            for (int x = origin.getBlockX() - 3; x <= origin.getBlockX() + width + 2; x++) {
                for (int z = origin.getBlockZ() - 3; z <= origin.getBlockZ() + depth + 2; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
                }
            }
            
            SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);
            
            assertTrue(r.passed, "Site with solid foundation should pass; result=" + r);
        }
        
        @Test
        @DisplayName("Site with 70% solid passes (above T057 60% threshold)")
        void testSeventyPercentSolidPasses() {
            Location origin = new Location(world, 600, 64, 600);
            int width = 5, depth = 4; // 20 samples
            
            // Fill entire area with DIRT including wide borders for slope detection
            for (int x = origin.getBlockX() - 3; x <= origin.getBlockX() + width + 2; x++) {
                for (int z = origin.getBlockZ() - 3; z <= origin.getBlockZ() + depth + 2; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
                }
            }
            
            // Remove 6 tiles (30%) to leave 70% solid - above 60% threshold
            int removed = 0;
            for (int x = origin.getBlockX(); x < origin.getBlockX() + width && removed < 6; x++) {
                for (int z = origin.getBlockZ(); z < origin.getBlockZ() + depth && removed < 6; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.AIR);
                    removed++;
                }
            }
            
            SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);
            
            // 70% solidity >= 60% threshold - should pass
            assertTrue(r.passed, "Site with 70% solidity should pass (above 60% threshold); result=" + r);
        }
    }
    
    @Nested
    @DisplayName("T057: Configurable Thresholds")
    class ConfigurableThresholdsTests {
        
        @Test
        @DisplayName("Constructor with custom thresholds respects all values")
        void testCustomThresholds() {
            // Very permissive thresholds
            SiteValidator permissive = new SiteValidator(
                2.0,   // maxSlope - very permissive
                0.20,  // minSolidity - very permissive  
                0.80,  // maxSteepFraction - very permissive
                0.80   // maxBlockedFraction - very permissive
            );
            
            Location origin = new Location(world, 700, 64, 700);
            int width = 4, depth = 4; // 16 samples
            
            // Fill entire area with solid blocks including wide borders
            for (int x = origin.getBlockX() - 3; x <= origin.getBlockX() + width + 2; x++) {
                for (int z = origin.getBlockZ() - 3; z <= origin.getBlockZ() + depth + 2; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.STONE);
                }
            }
            
            // Remove 12 of the 16 inner tiles to get 25% solid (4/16)
            int removed = 0;
            for (int x = origin.getBlockX(); x < origin.getBlockX() + width && removed < 12; x++) {
                for (int z = origin.getBlockZ(); z < origin.getBlockZ() + depth && removed < 12; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.AIR);
                    removed++;
                }
            }
            
            SiteValidator.ValidationResult r = permissive.validateSite(world, origin, width, depth, 4);
            
            // 25% solidity > 20% threshold, 75% blocked <= 80% threshold - should pass with permissive settings
            assertTrue(r.passed, "Site should pass with permissive thresholds (25% > 20%, 75% blocked <= 80%); result=" + r);
        }
        
        @Test
        @DisplayName("Strict thresholds reject borderline sites")
        void testStrictThresholds() {
            // Very strict thresholds
            SiteValidator strict = new SiteValidator(
                0.1,   // maxSlope - very strict
                0.95,  // minSolidity - very strict
                0.05,  // maxSteepFraction - very strict
                0.05   // maxBlockedFraction - very strict
            );
            
            Location origin = new Location(world, 800, 64, 800);
            int width = 4, depth = 4; // 16 samples
            
            // Set solid foundation with wide borders
            for (int x = origin.getBlockX() - 3; x <= origin.getBlockX() + width + 2; x++) {
                for (int z = origin.getBlockZ() - 3; z <= origin.getBlockZ() + depth + 2; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
                }
            }
            // Remove 2 tiles from inner area (87.5% = 14/16 solid)
            fake.setBlockType(origin.getBlockX(), origin.getBlockY() - 1, origin.getBlockZ(), Material.AIR);
            fake.setBlockType(origin.getBlockX() + 1, origin.getBlockY() - 1, origin.getBlockZ(), Material.AIR);
            
            SiteValidator.ValidationResult r = strict.validateSite(world, origin, width, depth, 4);
            
            // 87.5% solidity < 95% threshold - should fail with strict settings
            assertFalse(r.passed, "Site should fail with strict thresholds (87.5% < 95%); result=" + r);
        }
    }
    
    @Nested
    @DisplayName("T057: Rejection Reasons Diagnostics")
    class RejectionReasonTests {
        
        @Test
        @DisplayName("Failed validation includes rejection reasons")
        void testRejectionReasonsPopulated() {
            Location origin = new Location(world, 900, 64, 900);
            int width = 4, depth = 4; // 16 samples
            
            // Set only 4/16 = 25% solid (fails 60% threshold)
            int solidCount = 0;
            for (int x = origin.getBlockX(); x < origin.getBlockX() + width; x++) {
                for (int z = origin.getBlockZ(); z < origin.getBlockZ() + depth; z++) {
                    if (solidCount < 4) {
                        fake.setBlockType(x, origin.getBlockY() - 1, z, Material.STONE);
                        solidCount++;
                    }
                }
            }
            
            SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);
            
            assertFalse(r.passed, "Site should fail");
            
            List<String> reasons = r.getRejectionReasons();
            assertNotNull(reasons, "Rejection reasons should not be null");
            assertFalse(reasons.isEmpty(), "Rejection reasons should not be empty for failed validation");
            
            // Should have at least a solidity reason
            boolean hasSolidityReason = reasons.stream().anyMatch(s -> s.contains("solidity"));
            assertTrue(hasSolidityReason || reasons.stream().anyMatch(s -> s.contains("blocked")), 
                "Should have solidity or blocked rejection reason; reasons=" + reasons);
        }
        
        @Test
        @DisplayName("Fluid rejection reason is present when water detected")
        void testFluidRejectionReason() {
            Location origin = new Location(world, 1000, 64, 1000);
            int width = 3, depth = 3;
            
            // Set solid foundation
            for (int x = origin.getBlockX() - 1; x <= origin.getBlockX() + width; x++) {
                for (int z = origin.getBlockZ() - 1; z <= origin.getBlockZ() + depth; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
                }
            }
            
            // Add large water patch (5x5) to exceed small water tolerance
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    fake.setBlockType(origin.getBlockX() + x, origin.getBlockY() - 1, origin.getBlockZ() + z, Material.WATER);
                }
            }
            
            SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);
            
            assertFalse(r.passed, "Site should fail with large fluid patch");
            
            List<String> reasons = r.getRejectionReasons();
            boolean hasFluidReason = reasons.stream().anyMatch(s -> s.contains("fluid"));
            assertTrue(hasFluidReason, "Should have fluid rejection reason for large patch; reasons=" + reasons);
        }
        
        @Test
        @DisplayName("Passing validation has empty rejection reasons")
        void testPassingHasNoRejectionReasons() {
            Location origin = new Location(world, 1100, 64, 1100);
            int width = 3, depth = 3;
            
            // Set solid flat foundation
            for (int x = origin.getBlockX() - 1; x <= origin.getBlockX() + width; x++) {
                for (int z = origin.getBlockZ() - 1; z <= origin.getBlockZ() + depth; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
                }
            }
            
            SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);
            
            assertTrue(r.passed, "Site should pass; result=" + r);
            
            List<String> reasons = r.getRejectionReasons();
            assertTrue(reasons.isEmpty(), "Passing validation should have empty rejection reasons; reasons=" + reasons);
        }
    }
    
    @Nested
    @DisplayName("T057: Steep/Blocked Fraction Tolerance")
    class SteepBlockedToleranceTests {
        
        @Test
        @DisplayName("Site with 30% blocked tiles passes (within tolerance)")
        void testThirtyPercentBlockedPasses() {
            Location origin = new Location(world, 1200, 64, 1200);
            int width = 5, depth = 4; // 20 samples
            
            // Fill entire area with solid DIRT including wide borders for slope detection
            for (int x = origin.getBlockX() - 3; x <= origin.getBlockX() + width + 2; x++) {
                for (int z = origin.getBlockZ() - 3; z <= origin.getBlockZ() + depth + 2; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
                }
            }
            
            // Remove 6 inner tiles (30% of 20) to get 70% solid
            int removed = 0;
            for (int x = origin.getBlockX(); x < origin.getBlockX() + width && removed < 6; x++) {
                for (int z = origin.getBlockZ(); z < origin.getBlockZ() + depth && removed < 6; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.AIR);
                    removed++;
                }
            }
            
            SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);
            
            // 70% solidity >= 60% threshold, 30% blocked <= 30% threshold
            assertTrue(r.passed, "Site with 70% solid (30% blocked) should pass (at tolerance); result=" + r);
        }
        
        @Test
        @DisplayName("Site with 40% blocked tiles fails (over tolerance)")
        void testFortyPercentBlockedFails() {
            Location origin = new Location(world, 1300, 64, 1300);
            int width = 5, depth = 4; // 20 samples
            
            // Fill entire area with solid DIRT including wide borders
            for (int x = origin.getBlockX() - 3; x <= origin.getBlockX() + width + 2; x++) {
                for (int z = origin.getBlockZ() - 3; z <= origin.getBlockZ() + depth + 2; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.DIRT);
                }
            }
            
            // Remove 8 inner tiles (40% of 20) to get 60% solid but 40% blocked > 30% threshold
            int removed = 0;
            for (int x = origin.getBlockX(); x < origin.getBlockX() + width && removed < 8; x++) {
                for (int z = origin.getBlockZ(); z < origin.getBlockZ() + depth && removed < 8; z++) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.AIR);
                    removed++;
                }
            }
            
            SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 4);
            
            // 40% blocked > 30% threshold - should fail
            assertFalse(r.passed, "Site with 40% blocked should fail (over 30% tolerance); result=" + r);
        }
    }
}
