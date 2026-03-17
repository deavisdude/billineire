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
    @DisplayName("trimVegetation removes whole connected vegetation components within the footprint")
    void testTrimVegetation_removesConnectedVegetation() {
        Location origin = new Location(world, 100, 64, 200);

        fake.setBlockType(100, 64, 200, Material.OAK_LOG);
        fake.setBlockType(100, 65, 200, Material.OAK_LOG);
        fake.setBlockType(100, 66, 200, Material.OAK_LEAVES);
        fake.setBlockType(101, 66, 200, Material.OAK_LEAVES);
        fake.setBlockType(99, 66, 200, Material.OAK_LEAVES);

        // Unconnected vegetation outside the footprint should remain untouched.
        fake.setBlockType(104, 70, 200, Material.OAK_LOG);

        int trimmed = TerraformingUtil.trimVegetation(world, origin, 1, 1, 5);

        assertEquals(5, trimmed);
        assertEquals(Material.AIR, world.getBlockAt(100, 64, 200).getType());
        assertEquals(Material.AIR, world.getBlockAt(100, 65, 200).getType());
        assertEquals(Material.AIR, world.getBlockAt(100, 66, 200).getType());
        assertEquals(Material.AIR, world.getBlockAt(101, 66, 200).getType());
        assertEquals(Material.AIR, world.getBlockAt(99, 66, 200).getType());
        assertEquals(Material.OAK_LOG, world.getBlockAt(104, 70, 200).getType());
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
        assertEquals(Material.STONE, world.getBlockAt(200, 64, 300).getType());

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
        assertEquals(Material.STONE, world.getBlockAt(301, 63, 400).getType());
    }

    @Test
    @DisplayName("backfillFoundation fills full footprint gaps with local surface material")
    void testBackfillFoundation_fullFootprintUsesLocalMaterial() {
        Location origin = new Location(world, 400, 70, 500);
        int width = 4;
        int depth = 4;

        for (int x = 400; x < 404; x++) {
            for (int z = 500; z < 504; z++) {
                fake.setBlockType(x, 66, z, Material.SAND);
                fake.setBlockType(x, 67, z, Material.SAND);
                fake.setBlockType(x, 68, z, Material.AIR);
                fake.setBlockType(x, 69, z, Material.AIR);
            }
        }

        int filled = TerraformingUtil.backfillFoundation(world, origin, width, depth, 70, Material.DIRT);

        assertEquals(width * depth * 3, filled);
        assertEquals(Material.SAND, world.getBlockAt(401, 68, 501).getType());
        assertEquals(Material.SAND, world.getBlockAt(401, 69, 501).getType());
        assertEquals(Material.SAND, world.getBlockAt(401, 70, 501).getType());
    }

    @Test
    @DisplayName("fillGaps ignores canopy blocks and preserves sand top layers")
    void testFillGaps_ignoresCanopyAndPreservesSurfaceMaterial() {
        Location origin = new Location(world, 450, 64, 550);

        fake.setBlockType(450, 62, 550, Material.SAND);
        fake.setBlockType(450, 63, 550, Material.AIR);
        fake.setBlockType(450, 64, 550, Material.AIR);
        fake.setBlockType(450, 66, 550, Material.OAK_LEAVES);

        int filled = TerraformingUtil.fillGaps(world, origin, 1, 1, 64);

        assertTrue(filled >= 2);
        assertEquals(Material.SAND, world.getBlockAt(450, 63, 550).getType());
        assertEquals(Material.SAND, world.getBlockAt(450, 64, 550).getType());
    }

    @Test
    @DisplayName("backfillFoundation fills deep unsupported columns beyond the legacy shallow-gap cap")
    void testBackfillFoundation_fillsDeepUnsupportedColumns() {
        Location origin = new Location(world, 480, 70, 580);

        fake.setBlockType(480, 63, 580, Material.SAND);
        fake.setBlockType(480, 64, 580, Material.SAND);
        for (int y = 65; y <= 70; y++) {
            fake.setBlockType(480, y, 580, Material.AIR);
        }
        fake.setBlockType(480, 71, 580, Material.STONE_BRICKS);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 71, Material.DIRT);

        assertEquals(6, filled, "Expected the full unsupported column up to the structure base to be filled");
        for (int y = 65; y <= 70; y++) {
            assertEquals(Material.SAND, world.getBlockAt(480, y, 580).getType(),
                "Deep underfill should preserve the local support material throughout the column");
        }
    }

    @Test
    @DisplayName("backfillFoundation ignores structure-provided false support when finding natural ground")
    void testBackfillFoundation_ignoresStructureBlocksBelowBase() {
        Location origin = new Location(world, 490, 70, 590);

        fake.setBlockType(490, 63, 590, Material.SAND);
        fake.setBlockType(490, 64, 590, Material.SAND);
        fake.setBlockType(490, 68, 590, Material.STONE_BRICKS);
        for (int y = 65; y <= 67; y++) {
            fake.setBlockType(490, y, 590, Material.AIR);
        }
        fake.setBlockType(490, 69, 590, Material.AIR);
        fake.setBlockType(490, 70, 590, Material.AIR);
        fake.setBlockType(490, 71, 590, Material.STONE_BRICKS);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 71, Material.DIRT);

        assertEquals(5, filled, "Expected fill to continue past blueprint support blocks down to the lowest placed block");
        assertEquals(Material.SAND, world.getBlockAt(490, 65, 590).getType());
        assertEquals(Material.SAND, world.getBlockAt(490, 66, 590).getType());
        assertEquals(Material.SAND, world.getBlockAt(490, 67, 590).getType());
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(490, 68, 590).getType(),
            "Existing schematic support blocks should remain intact");
        assertEquals(Material.SAND, world.getBlockAt(490, 69, 590).getType());
        assertEquals(Material.SAND, world.getBlockAt(490, 70, 590).getType());
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(490, 71, 590).getType());
    }

    @Test
    @DisplayName("backfillFoundation fills up to the lowest placed block when a structure column starts above base Y")
    void testBackfillFoundation_fillsToLowestPlacedBlockInColumn() {
        Location origin = new Location(world, 495, 70, 595);

        fake.setBlockType(495, 63, 595, Material.SAND);
        fake.setBlockType(495, 64, 595, Material.SAND);
        fake.setBlockType(495, 70, 595, Material.SAND);
        for (int y = 65; y <= 69; y++) {
            fake.setBlockType(495, y, 595, Material.AIR);
        }
        fake.setBlockType(495, 71, 595, Material.AIR);
        fake.setBlockType(495, 72, 595, Material.STONE_BRICKS);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 72, Material.DIRT);

        assertEquals(6, filled, "Expected the air pocket above the base layer to be filled up to the first placed block");
        for (int y = 65; y <= 69; y++) {
            assertEquals(Material.SAND, world.getBlockAt(495, y, 595).getType());
        }
        assertEquals(Material.SAND, world.getBlockAt(495, 70, 595).getType());
        assertEquals(Material.SAND, world.getBlockAt(495, 71, 595).getType());
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(495, 72, 595).getType());
    }

    @Test
    @DisplayName("backfillFoundation ignores foundation-like blocks inside the footprint until it reaches the actual floor")
    void testBackfillFoundation_ignoresGroundLikeBlocksBeforeRaisedFloor() {
        Location origin = new Location(world, 497, 70, 597);

        fake.setBlockType(497, 63, 597, Material.DIRT);
        fake.setBlockType(497, 64, 597, Material.GRASS_BLOCK);
        for (int y = 65; y <= 68; y++) {
            fake.setBlockType(497, y, 597, Material.AIR);
        }
        fake.setBlockType(497, 69, 597, Material.DIRT);
        fake.setBlockType(497, 70, 597, Material.AIR);
        fake.setBlockType(497, 71, 597, Material.AIR);
        fake.setBlockType(497, 72, 597, Material.SANDSTONE);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 72, Material.DIRT);

        assertTrue(filled >= 5, "Expected fill to continue past internal dirt/ground blocks toward the actual sandstone floor");
        for (int y = 65; y <= 68; y++) {
            assertEquals(Material.GRASS_BLOCK, world.getBlockAt(497, y, 597).getType());
        }
        assertEquals(Material.DIRT, world.getBlockAt(497, 69, 597).getType());
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(497, 70, 597).getType());
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(497, 71, 597).getType());
        assertEquals(Material.SANDSTONE, world.getBlockAt(497, 72, 597).getType());
    }

    @Test
    @DisplayName("backfillFoundation fills an empty base layer when structure blocks sit directly above it")
    void testBackfillFoundation_fillsEmptyBaseLayerUnderStructure() {
        Location origin = new Location(world, 500, 70, 600);

        fake.setBlockType(500, 68, 600, Material.STONE);
        fake.setBlockType(500, 69, 600, Material.STONE);
        fake.setBlockType(500, 70, 600, Material.AIR);
        fake.setBlockType(500, 71, 600, Material.STONE_BRICKS);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 71, Material.DIRT);

        assertTrue(filled >= 1, "Expected the empty base layer to be filled");
        assertEquals(Material.STONE, world.getBlockAt(500, 70, 600).getType(),
            "Base layer should inherit the predominant local support material when the schematic leaves it empty");
    }

    @Test
    @DisplayName("backfillFoundation seals the immediate underside band below a pasted solid base")
    void testBackfillFoundation_sealsImmediateUndersideBand() {
        Location origin = new Location(world, 510, 70, 610);

        fake.setBlockType(510, 66, 610, Material.DIRT);
        fake.setBlockType(510, 67, 610, Material.DIRT);
        fake.setBlockType(510, 68, 610, Material.DIRT);
        fake.setBlockType(510, 69, 610, Material.AIR);
        fake.setBlockType(510, 70, 610, Material.SMOOTH_SANDSTONE);
        fake.setBlockType(510, 71, 610, Material.AIR);
        fake.setBlockType(510, 72, 610, Material.AIR);
        fake.setBlockType(510, 73, 610, Material.TERRACOTTA);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 73, Material.DIRT);

        assertTrue(filled >= 1, "Expected the immediate underside band to be resealed after paste");
        assertEquals(Material.DIRT, world.getBlockAt(510, 69, 610).getType(),
            "The air band directly below the pasted base should be filled back in");
        assertEquals(Material.SMOOTH_SANDSTONE, world.getBlockAt(510, 70, 610).getType(),
            "The pasted base itself must remain intact");
        assertEquals(Material.AIR, world.getBlockAt(510, 71, 610).getType(),
            "Higher schematic air above the base should remain untouched by the underside reseal pass");
    }

    @Test
    @DisplayName("backfillFoundation compacts the underside band without climbing into higher schematic air volumes")
    void testBackfillFoundation_fillsMostlyAirLowerSchematicLayers() {
        Location origin = new Location(world, 520, 70, 620);
        int width = 5;
        int depth = 5;

        for (int x = 520; x < 525; x++) {
            for (int z = 620; z < 625; z++) {
                fake.setBlockType(x, 64, z, Material.GRASS_BLOCK);
                for (int y = 65; y <= 73; y++) {
                    fake.setBlockType(x, y, z, Material.AIR);
                }
                fake.setBlockType(x, 74, z, Material.SMOOTH_SANDSTONE);
            }
        }

        fake.setBlockType(520, 71, 620, Material.STONE_BRICKS);
        fake.setBlockType(524, 72, 624, Material.STONE_BRICKS);

        int filled = TerraformingUtil.backfillFoundation(world, origin, width, depth, 74, Material.DIRT);

        assertTrue(filled >= 150 && filled < 250,
            "Expected the underside band to be compacted without recreating tall dirt walls through the whole schematic");
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(521, 70, 621).getType(),
            "The lowest hollow underside band should be filled from the footprint base upward");
        assertEquals(Material.AIR, world.getBlockAt(523, 73, 623).getType(),
            "Higher schematic air volumes should remain open once the building shell begins");
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(520, 71, 620).getType(),
            "Existing support blocks inside a mostly-air layer must remain intact");
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(524, 72, 624).getType(),
            "Existing support blocks inside a mostly-air layer must remain intact");
        assertEquals(Material.SMOOTH_SANDSTONE, world.getBlockAt(522, 74, 622).getType(),
            "The first real building floor layer must stop the mostly-air fill pass");
    }

    @Test
    @DisplayName("backfillFoundation stops when retained air drops sharply, even if higher layers reopen")
    void testBackfillFoundation_stopsOnSharpRetainedAirDrop() {
        Location origin = new Location(world, 540, 70, 640);
        int width = 10;
        int depth = 10;

        for (int x = 540; x < 550; x++) {
            for (int z = 640; z < 650; z++) {
                fake.setBlockType(x, 64, z, Material.GRASS_BLOCK);
                for (int y = 65; y <= 73; y++) {
                    fake.setBlockType(x, y, z, Material.AIR);
                }
                fake.setBlockType(x, 74, z, Material.SMOOTH_SANDSTONE);
            }
        }

        // Leave the base layer partially hollow so the scan has real work to do.
        for (int x = 540; x < 545; x++) {
            for (int z = 640; z < 645; z++) {
                fake.setBlockType(x, 70, z, Material.STONE_BRICKS);
            }
        }

        // Create a sharp air-count drop one layer above the lower hollow band.
        int supportsPlaced = 0;
        for (int x = 540; x < 550 && supportsPlaced < 39; x++) {
            for (int z = 640; z < 650 && supportsPlaced < 39; z++) {
                fake.setBlockType(x, 72, z, Material.STONE_BRICKS);
                supportsPlaced++;
            }
        }

        // Higher layers reopen, which previously caused the heuristic to build tall dirt walls.
        for (int x = 540; x < 550; x++) {
            for (int z = 640; z < 650; z++) {
                fake.setBlockType(x, 73, z, Material.AIR);
            }
        }

        int filled = TerraformingUtil.backfillFoundation(world, origin, width, depth, 74, Material.DIRT);

        assertTrue(filled > 0,
            "Expected some underside fill before the sharp retained-air drop stopped the scan");
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(549, 70, 649).getType(),
            "The underside base layer should still be compacted before the sharp retained-air drop stops the scan");
        assertEquals(Material.AIR, world.getBlockAt(549, 73, 649).getType(),
            "Higher reopened layers must remain air once the retained-air drop indicates the building has started");
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(540, 72, 640).getType(),
            "Existing support blocks in the sharp-drop layer must remain intact");
        assertEquals(Material.SMOOTH_SANDSTONE, world.getBlockAt(545, 74, 645).getType(),
            "The actual building floor should still remain intact");
    }

    /**
     * Regression test: when the actual floor is placed at groundY which is BELOW the AABB
     * origin Y (origin.getBlockY() = groundY + 1), anchoring backfill from groundY must fill
     * only the air gap below the floor and must NOT flood the interior above the floor.
     * This covers the forum/villa scenario on sloped terrain where findGroundLevel returns a
     * value 1 below the placement origin.
     */
    @Test
    @DisplayName("backfillFoundation anchored at groundY fills gap below floor without flooding interior")
    void testBackfillFoundation_anchoredAtGroundY_fillsOnlyBelowFloor() {
        // Terrain at Y=62 (GRASS), air at 63-65, floor (SMOOTH_SANDSTONE) at 66,
        // interior air at 67-73, roof at 74.
        // Call backfill with origin at groundY=66 (effectiveBaseY=66), structureMaxY=74.
        Location origin = new Location(world, 700, 66, 800);

        // Natural ground column
        fake.setBlockType(700, 58, 800, Material.STONE);
        fake.setBlockType(700, 59, 800, Material.STONE);
        fake.setBlockType(700, 60, 800, Material.STONE);
        fake.setBlockType(700, 61, 800, Material.DIRT);
        fake.setBlockType(700, 62, 800, Material.GRASS_BLOCK); // terrain surface
        // Air gap 63-65 (structure floating above slope)
        // Floor placed by buildRomanForum at groundY=66
        fake.setBlockType(700, 66, 800, Material.SMOOTH_SANDSTONE);
        // Interior air 67-73
        // Roof
        fake.setBlockType(700, 74, 800, Material.TERRACOTTA);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 74, Material.DIRT);

        // Air at 63-65 (gap below floor) should all be filled
        assertEquals(3, filled, "Should fill exactly the 3 air blocks below the floor");
        // The fill material is derived from the surface (GRASS_BLOCK); check solid, not AIR
        assertNotEquals(Material.AIR, world.getBlockAt(700, 63, 800).getType(), "Y=63 must be filled");
        assertNotEquals(Material.AIR, world.getBlockAt(700, 64, 800).getType(), "Y=64 must be filled");
        assertNotEquals(Material.AIR, world.getBlockAt(700, 65, 800).getType(), "Y=65 must be64 must be filled");
        assertNotEquals(Material.AIR, world.getBlockAt(700, 65, 800).getType(), "Y=65 must be filled");
        // Floor itself should be untouched
        assertEquals(Material.SMOOTH_SANDSTONE, world.getBlockAt(700, 66, 800).getType(),
            "Floor should not be overwritten");
        // Interior above floor must remain air (not flooded with dirt)
        assertEquals(Material.AIR, world.getBlockAt(700, 67, 800).getType(),
            "Interior above floor must not be filled");
    }

    @Test
    @DisplayName("backfillFoundation falls back to the first solid support when no fully stable support exists below")
    void testBackfillFoundation_usesFallbackSupportWhenColumnNeverStabilizes() {
        Location origin = new Location(world, 710, 70, 810);

        fake.setBlockType(710, 64, 810, Material.DIRT);
        for (int y = 65; y <= 70; y++) {
            fake.setBlockType(710, y, 810, Material.AIR);
        }
        fake.setBlockType(710, 71, 810, Material.STONE_BRICKS);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 71, Material.DIRT);

        assertEquals(6, filled, "Expected fallback support to fill the entire hanging column up to the structure base");
        for (int y = 65; y <= 70; y++) {
            assertEquals(Material.DIRT, world.getBlockAt(710, y, 810).getType(),
                "Fallback support material should be propagated upward when no lower stable support exists");
        }
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(710, 71, 810).getType());
    }

    @Test
    @DisplayName("backfillFoundation seals the pasted base layer without flooding large open schematic air volumes")
    void testBackfillFoundation_sealsBaseLayerForOpenColumnsOnly() {
        Location origin = new Location(world, 720, 70, 820);

        fake.setBlockType(720, 63, 820, Material.DIRT);
        fake.setBlockType(720, 64, 820, Material.GRASS_BLOCK);
        for (int y = 65; y <= 70; y++) {
            fake.setBlockType(720, y, 820, Material.AIR);
        }
        fake.setBlockType(720, 74, 820, Material.TERRACOTTA);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 74, Material.DIRT);

        assertEquals(6, filled, "Expected the full unsupported base column to be sealed up to the pasted footprint base");
        for (int y = 65; y <= 70; y++) {
            assertEquals(Material.GRASS_BLOCK, world.getBlockAt(720, y, 820).getType(),
                "Base support should inherit the local terrain material");
        }
        assertEquals(Material.AIR, world.getBlockAt(720, 71, 820).getType(),
            "Large open air volumes above the base layer must remain open");
        assertEquals(Material.AIR, world.getBlockAt(720, 72, 820).getType(),
            "Large open air volumes above the base layer must remain open");
        assertEquals(Material.AIR, world.getBlockAt(720, 73, 820).getType(),
            "Large open air volumes above the base layer must remain open");
        assertEquals(Material.TERRACOTTA, world.getBlockAt(720, 74, 820).getType());
    }

    @Test
    @DisplayName("backfillFoundation compacts shallow underside voids instead of leaving cave shelves under the footprint")
    void testBackfillFoundation_compactsShallowUndersideVoids() {
        Location origin = new Location(world, 730, 70, 830);

        fake.setBlockType(730, 67, 830, Material.DIRT);
        fake.setBlockType(730, 66, 830, Material.DIRT);
        fake.setBlockType(730, 65, 830, Material.AIR);
        fake.setBlockType(730, 64, 830, Material.AIR);
        fake.setBlockType(730, 63, 830, Material.STONE);
        fake.setBlockType(730, 62, 830, Material.STONE);
        fake.setBlockType(730, 61, 830, Material.STONE);
        fake.setBlockType(730, 70, 830, Material.SMOOTH_SANDSTONE);
        fake.setBlockType(730, 79, 830, Material.TERRACOTTA);

        int filled = TerraformingUtil.backfillFoundation(world, origin, 1, 1, 79, Material.DIRT);

        assertEquals(4, filled, "Expected the voids below the footprint to be filled while preserving existing support shelves");
        assertEquals(Material.DIRT, world.getBlockAt(730, 64, 830).getType(),
            "Visible underside gaps should be filled instead of leaving air shelves under the structure");
        assertEquals(Material.DIRT, world.getBlockAt(730, 65, 830).getType(),
            "Visible underside gaps should be filled instead of leaving air shelves under the structure");
        assertEquals(Material.DIRT, world.getBlockAt(730, 66, 830).getType(),
            "Existing upper support shelves should remain intact");
        assertEquals(Material.DIRT, world.getBlockAt(730, 67, 830).getType(),
            "Existing upper support shelves should remain intact");
        assertEquals(Material.DIRT, world.getBlockAt(730, 68, 830).getType(),
            "The gap above the shelf should be compacted into the support pedestal");
        assertEquals(Material.DIRT, world.getBlockAt(730, 69, 830).getType(),
            "The gap above the shelf should be compacted into the support pedestal");
        assertEquals(Material.SMOOTH_SANDSTONE, world.getBlockAt(730, 70, 830).getType(),
            "The placed floor must remain intact");
    }
}
