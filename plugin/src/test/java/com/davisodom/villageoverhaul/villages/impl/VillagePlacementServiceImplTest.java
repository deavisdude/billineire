package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.cultures.CultureService;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class VillagePlacementServiceImplTest {

    @Test
    @DisplayName("getCultureStructures is deterministic for the same seed")
    public void testGetCultureStructuresDeterministicOrdering() throws Exception {
        CultureService cs = Mockito.mock(CultureService.class);
        List<String> structures = Arrays.asList("main", "a", "b", "c", "d");
        Mockito.when(cs.get("test-culture")).thenReturn(Optional.of(new CultureService.Culture(
                "test-culture", "Test", structures, null)));

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(Mockito.mock(
                com.davisodom.villageoverhaul.worldgen.StructureService.class),
                Mockito.mock(com.davisodom.villageoverhaul.villages.VillageMetadataStore.class), cs);

        Method m = VillagePlacementServiceImpl.class.getDeclaredMethod("getCultureStructures", String.class, long.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> r1 = (List<String>) m.invoke(svc, "test-culture", 12345L);
        @SuppressWarnings("unchecked")
        List<String> r2 = (List<String>) m.invoke(svc, "test-culture", 12345L);

        assertEquals(r1, r2, "Same seed should produce the same deterministic ordering");

        @SuppressWarnings("unchecked")
        List<String> r3 = (List<String>) m.invoke(svc, "test-culture", 54321L);

        assertNotEquals(r1, r3, "Different seed should usually produce a different ordering");
    }
    
    // Candidate selection tests require a stable MockBukkit world and were flaky across CI
    // Focused deterministic ordering tests are provided elsewhere in the suite.

    @Test
    @DisplayName("placeBuilding produces deterministic building IDs for same seed")
    public void testPlaceBuildingDeterministicId() throws Exception {
    com.davisodom.villageoverhaul.worldgen.StructureService structureService = Mockito.mock(
        com.davisodom.villageoverhaul.worldgen.StructureService.class);

    com.davisodom.villageoverhaul.villages.VillageMetadataStore metadataStore = Mockito.mock(
        com.davisodom.villageoverhaul.villages.VillageMetadataStore.class);

    CultureService cs = Mockito.mock(CultureService.class);

    // Dimensions for the structure
    Mockito.when(structureService.getStructureDimensions("house")).thenReturn(Optional.of(new int[]{7,6,7}));

    World world = Mockito.mock(World.class);
    Location loc = new Location(world, 10, 64, 20);

    // Simulate placement result (actual location and rotation)
    Mockito.when(structureService.placeStructureAndGetResult(Mockito.eq("house"), Mockito.eq(world), Mockito.eq(loc), Mockito.anyLong()))
        .thenAnswer(inv -> Optional.of(new com.davisodom.villageoverhaul.worldgen.PlacementResult(loc, 0)));

    VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(structureService, metadataStore, cs);

    java.util.UUID villageId = java.util.UUID.randomUUID();
    long seed = 987654321L;

    Optional<com.davisodom.villageoverhaul.model.Building> b1 = svc.placeBuilding(world, loc, "house", villageId, seed);
    Optional<com.davisodom.villageoverhaul.model.Building> b2 = svc.placeBuilding(world, loc, "house", villageId, seed);

    assertTrue(b1.isPresent());
    assertTrue(b2.isPresent());
    assertEquals(b1.get().getBuildingId(), b2.get().getBuildingId(), "Same seed should produce deterministic building id");
    }

}
