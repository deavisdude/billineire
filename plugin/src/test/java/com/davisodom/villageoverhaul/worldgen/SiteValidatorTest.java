package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.test.FakeWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.Mockito;

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

        // Inject fluid at one sample
        fake.setBlockType(origin.getBlockX() + 1, origin.getBlockY() - 1, origin.getBlockZ() + 1, Material.WATER);

        SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 5);

        assertFalse(r.passed, "site must fail when a fluid tile exists");
        assertFalse(r.foundationOk, "foundationOk should be false when fluid present");
        assertEquals(1, r.classificationResult.fluid, "exactly one sampled tile should be classified as fluid");
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

        // Make only 10/16 positions solid, leave the others as AIR
        int solidCount = 0;
        // Make only 10/16 inner positions solid, others remain AIR
        for (int x = origin.getBlockX(); x < origin.getBlockX() + width; x++) {
            for (int z = origin.getBlockZ(); z < origin.getBlockZ() + depth; z++) {
                if (solidCount < 10) {
                    fake.setBlockType(x, origin.getBlockY() - 1, z, Material.STONE);
                    solidCount++;
                } else {
                    // leave as AIR (explicitly set nothing)
                }
            }
        }

        SiteValidator.ValidationResult r = sut.validateSite(world, origin, width, depth, 6);

        assertFalse(r.passed, "site should fail when solidity below MIN_FOUNDATION_SOLIDITY");
        assertFalse(r.foundationOk, "foundationOk must be false for low solidity");
        assertTrue(r.classificationResult.getTotal() >= width * depth, "samples should equal or exceed grid size");
    }
}
