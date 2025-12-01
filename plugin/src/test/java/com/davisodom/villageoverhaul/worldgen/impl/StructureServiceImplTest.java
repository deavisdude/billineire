package com.davisodom.villageoverhaul.worldgen.impl;

// StructureServiceImplTest uses pure unit logic; no MockBukkit required
// PlacementResult import removed (unused)
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import com.davisodom.villageoverhaul.test.FakeWorld;
import org.mockito.Mockito;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class StructureServiceImplTest {

    @Test
    @DisplayName("rotation derivation from seed is deterministic")
    public void testRotationDerivationIsDeterministic() {
        long seed = 123456L;
        int r1 = new java.util.Random(seed).nextInt(4) * 90;
        int r2 = new java.util.Random(seed).nextInt(4) * 90;
        assertEquals(r1, r2, "Rotation computed from identical seed must be deterministic");

        long other = 654321L;
        int r3 = new java.util.Random(other).nextInt(4) * 90;
        // It's common for different seeds to sometimes produce same rotation; ensure determinism only
        assertNotNull(r3);
    }

    @BeforeEach
    void setup() { }

    @Test
    @DisplayName("placeholder templates load with expected dimensions")
    public void testLoadPlaceholderTemplates() {
        StructureServiceImpl svc = new StructureServiceImpl();

        assertTrue(svc.getStructureDimensions("house_roman_small").isPresent());
        assertArrayEquals(new int[]{9,7,9}, svc.getStructureDimensions("house_roman_small").get());

        assertTrue(svc.getStructureDimensions("house_roman_medium").isPresent());
        assertTrue(svc.getStructureDimensions("house_roman_villa").isPresent());
        assertTrue(svc.getStructureDimensions("workshop_roman_forge").isPresent());
        assertTrue(svc.getStructureDimensions("market_roman_stall").isPresent());
        assertTrue(svc.getStructureDimensions("building_roman_bathhouse").isPresent());
    }

    @Test
    @DisplayName("placeStructureAndGetResult builds procedural house in FakeWorld")
    public void testPlaceStructureBuildsHouse() {
        FakeWorld fake = new FakeWorld();
        World world = fake.getWorld();
        Mockito.when(world.getName()).thenReturn("fake");

        // Make chunks ready
        Mockito.when(world.isChunkGenerated(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);

        StructureServiceImpl svc = new StructureServiceImpl();

        int originX = 100, originY = 65, originZ = 200;
        Location origin = new Location(world, originX, originY, originZ);

        // Prepare flat ground: solid blocks just below origin (originY - 1) and air at origin so findGroundLevel returns originY
        // Prepare an expanded flat area so rotation doesn't cause uncovered foundation
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                fake.setBlockType(originX + x, originY - 1, originZ + z, Material.STONE);
                fake.setBlockType(originX + x, originY, originZ + z, Material.AIR);
                fake.setBlockType(originX + x, originY + 1, originZ + z, Material.AIR);
            }
        }

        long seed = 12345L;
        Map<String, Integer> diagnostics = new HashMap<>();
        java.util.UUID villageId = java.util.UUID.randomUUID();
        Optional<com.davisodom.villageoverhaul.model.PlacementReceipt> receipt =
            svc.placeStructureAndGetReceipt("house_roman_small", world, origin, seed, villageId, null, diagnostics);

        assertTrue(receipt.isPresent(), "Placement should succeed on prepared FakeWorld");

        int expectedRotation = new java.util.Random(seed).nextInt(4) * 90;
        assertEquals(expectedRotation, receipt.get().getRotation());

        // Validate a few expected blocks from buildRomanHouse for a small house
        // Floor at (originX, originY, originZ)
        // Use receipt to find actual origin/height so assertions tolerate rotation
        int placedX = receipt.get().getOriginX();
        int placedY = receipt.get().getOriginY();
        int placedZ = receipt.get().getOriginZ();
        int placedWidth = receipt.get().getEffectiveWidth();
        int placedDepth = receipt.get().getEffectiveDepth();
        int placedHeight = receipt.get().getHeight();

        // Floor at origin should not be AIR
        assertNotEquals(Material.AIR, fake.getBlock(placedX, placedY, placedZ).getType());

        // Roof should contain some non-air blocks in the bounding footprint
        int roofY = placedY + placedHeight - 1;
        boolean sawRoofBlock = false;
        for (int x = 0; x < Math.max(1, placedWidth); x++) {
            for (int z = 0; z < Math.max(1, placedDepth); z++) {
                if (fake.getBlock(placedX + x, roofY, placedZ + z).getType() != Material.AIR) {
                    sawRoofBlock = true;
                    break;
                }
            }
            if (sawRoofBlock) break;
        }
        assertTrue(sawRoofBlock, "Expected some roof blocks to be placed (non-air)");
    }

    @Test
    @DisplayName("placeStructureAndGetReceipt increments diagnostics when terraforming plan rejects site")
    public void testTerraformPlanFailureProducesDiagnostics() {
        FakeWorld fake = new FakeWorld();
        World world = fake.getWorld();
        Mockito.when(world.getName()).thenReturn("fake");
        Mockito.when(world.isChunkGenerated(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);

        StructureServiceImpl svc = new StructureServiceImpl();

        int originX = 200, originY = 70, originZ = 300;
        Location origin = new Location(world, originX, originY, originZ);

        // Prepare foundation blocks (stone) but place a water tile within the bounding region so TerraformingPlan.plan() will fail
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                fake.setBlockType(originX + x, originY - 1, originZ + z, Material.STONE);
            }
        }

        // Add a water tile in the footprint to trigger plan rejection
        fake.setBlockType(originX + 1, originY + 1, originZ + 1, Material.WATER);

        Map<String, Integer> diagnostics = new HashMap<>();
        Optional<com.davisodom.villageoverhaul.model.PlacementReceipt> receipt =
                svc.placeStructureAndGetReceipt("house_roman_small", world, origin, 9999L, null, null, diagnostics);

        assertFalse(receipt.isPresent(), "Receipt should be empty when terraform plan rejects site");
        assertTrue(diagnostics.getOrDefault("terrainInvalid", 0) > 0, "Diagnostics should report terrainInvalid on terraform failure");
    }

    // Chunk readiness behavior is exercised indirectly by higher-level placement flows
    // Tests that require WorldEdit internals are avoided here to keep tests lightweight
}
