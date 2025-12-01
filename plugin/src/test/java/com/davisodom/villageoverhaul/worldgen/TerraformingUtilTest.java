package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.test.FakeWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TerraformingUtil Tests")
class TerraformingUtilTest {

    private FakeWorld fake;
    private World world;

    @BeforeEach
    void setUp() {
        fake = new FakeWorld();
        world = fake.getWorld();
        // Ensure calls using Location delegate to x,z variant used by FakeWorld
        Mockito.when(world.getHighestBlockYAt(Mockito.any(Location.class))).thenAnswer(inv -> {
            Location loc = inv.getArgument(0);
            return world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        });
    }

    @Test
    @DisplayName("trimVegetation removes ground-level plants but not high blocks")
    void testTrimVegetation_groundLevelOnly() {
        Location origin = new Location(world, 100, 64, 200);

        // Ground-level vegetation (should be removed)
        fake.setBlockType(100, 64, 200, Material.TALL_GRASS);
        fake.setBlockType(101, 64, 200, Material.DANDELION);

        // Low leaves/logs within trim height (should be removed because TRIMMABLE_VEGETATION includes them)
        fake.setBlockType(102, 64, 200, Material.OAK_LEAVES);

        // A log placed above the trim height must remain
        fake.setBlockType(103, 68, 200, Material.OAK_LOG);

        int trimmed = TerraformingUtil.trimVegetation(world, origin, 4, 1, 5);

        assertEquals(3, trimmed);
        assertEquals(Material.AIR, world.getBlockAt(100, 64, 200).getType());
        assertEquals(Material.AIR, world.getBlockAt(101, 64, 200).getType());
        assertEquals(Material.AIR, world.getBlockAt(102, 64, 200).getType());
        assertEquals(Material.OAK_LOG, world.getBlockAt(103, 68, 200).getType());
    }

    @Test
    @DisplayName("lightGrading fills shallow gaps but does not dig down")
    void testLightGrading_fillsGapsNoDig() {
        Location origin = new Location(world, 200, 64, 300);

        // Column (200,*,300) surface at y=61 (3 below target => should be filled up to target 64)
        fake.setBlockType(200, 61, 300, Material.STONE);

        // Column (201,*,300) surface at y=65 (above target => should not be modified)
        fake.setBlockType(201, 65, 300, Material.DIRT);

        // Column (202,*,300) surface at y=64 (already at target => no changes)
        fake.setBlockType(202, 64, 300, Material.DIRT);

        int modified = TerraformingUtil.lightGrading(world, origin, 3, 1, 64);

        // For column 200: fill y=62,63,64 => 3 blocks
        // For columns 201 and 202: no upward filling
        assertEquals(3, modified);

        assertEquals(Material.DIRT, world.getBlockAt(200, 62, 300).getType());
        assertEquals(Material.DIRT, world.getBlockAt(200, 63, 300).getType());
        assertEquals(Material.DIRT, world.getBlockAt(200, 64, 300).getType());

        // Ensure nothing was removed from the higher column
        assertEquals(Material.DIRT, world.getBlockAt(201, 65, 300).getType());
    }

    @Test
    @DisplayName("fillGaps replaces bad foundation materials and fills near-surface gaps")
    void testFillGaps_replacesAndFills() {
        Location origin = new Location(world, 300, 64, 400);

        // Foundation position at (300,64,400) is SNOW (unsuitable) -> should be replaced with DIRT
        fake.setBlockType(300, 64, 400, Material.SNOW);

        // Nearby column where surface is at y=62 and foundationY=64 -> fill y=63
        fake.setBlockType(301, 62, 400, Material.STONE);
        fake.setBlockType(301, 63, 400, Material.AIR);
        fake.setBlockType(301, 64, 400, Material.AIR); // foundation before fill

        int filled = TerraformingUtil.fillGaps(world, origin, 2, 1, 64);

        // Expect foundation SNOW replaced (+1) and the air at (301,63) filled (+1)
        assertTrue(filled >= 2);

        assertEquals(Material.DIRT, world.getBlockAt(300, 64, 400).getType());
        assertEquals(Material.DIRT, world.getBlockAt(301, 63, 400).getType());
    }

    @Test
    @DisplayName("backfillFoundation fills perimeter gaps only and respects max gap")
    void testBackfillFoundation_perimeterOnly() {
        Location origin = new Location(world, 400, 70, 500);
        int width = 6;
        int depth = 6;

        // Set natural terrain lower at peripheral columns (create gap of 2)
        for (int x = 400; x < 406; x++) {
            for (int z = 500; z < 506; z++) {
                // interior keep at structure base (70)
                if (x >= 402 && x <= 403 && z >= 502 && z <= 503) {
                    // interior: terrain at same level
                    fake.setBlockType(x, 70, z, Material.DIRT);
                } else {
                    // perimeter: terrain at 68 (gap=2)
                    fake.setBlockType(x, 68, z, Material.STONE);
                    fake.setBlockType(x, 69, z, Material.AIR);
                }
            }
        }

        int filled = TerraformingUtil.backfillFoundation(world, origin, width, depth, Material.DIRT);

        // Perimeter for 6x6 with 2-block-wide border: perimeter positions count = total - interior
        int total = width * depth;
        int interiorW = width - 4; // 2-block border each side
        int interiorD = depth - 4;
        int interior = Math.max(0, interiorW) * Math.max(0, interiorD);
        int expectedPerimeter = total - interior;

        assertEquals(expectedPerimeter * 1 /* at least one block per perimeter position */, filled);

        // Verify that interior blocks were not filled (interior stays as DIRT at base)
        assertEquals(Material.DIRT, world.getBlockAt(402, 70, 502).getType());
    }
}
