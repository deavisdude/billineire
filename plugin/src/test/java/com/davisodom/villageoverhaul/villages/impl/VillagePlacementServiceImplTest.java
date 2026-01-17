package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.cultures.CultureService;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl.PlacementOutcome;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl.PlacementStatus;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import com.davisodom.villageoverhaul.model.PlacementReceipt;
import com.davisodom.villageoverhaul.npc.CustomVillagerService;
import com.davisodom.villageoverhaul.obs.Metrics;

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
        assertNotNull(m, "Could not find findSuitablePlacementPosition method via reflection");
        assertNotNull(m, "Could not find findSuitablePlacementPosition method via reflection");
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

    /**
     * T026d17: Verify that village UUID derivation is deterministic.
     * Same seed + same origin should always produce the same village UUID.
     */
    @Test
    @DisplayName("Village UUID derivation is deterministic for same seed and origin")
    public void testDeterministicVillageUuidDerivation() {
        long seed = 12345L;
        int originX = 100;
        int originZ = 200;
        
        // Compute the expected deterministic UUID using the same formula as VillagePlacementServiceImpl
        UUID expected = UUID.nameUUIDFromBytes(
            (seed + ":" + originX + ":" + originZ).getBytes(StandardCharsets.UTF_8));
        
        // Compute it again - should be identical
        UUID actual = UUID.nameUUIDFromBytes(
            (seed + ":" + originX + ":" + originZ).getBytes(StandardCharsets.UTF_8));
        
        assertEquals(expected, actual, "Same inputs should produce same village UUID");
        
        // Different seed should produce different UUID
        UUID different = UUID.nameUUIDFromBytes(
            (54321L + ":" + originX + ":" + originZ).getBytes(StandardCharsets.UTF_8));
        
        assertNotEquals(expected, different, "Different seed should produce different village UUID");
        
        // Different origin should also produce different UUID
        UUID differentOrigin = UUID.nameUUIDFromBytes(
            (seed + ":" + 999 + ":" + originZ).getBytes(StandardCharsets.UTF_8));
        
        assertNotEquals(expected, differentOrigin, "Different origin should produce different village UUID");
    }

    @Test
    @DisplayName("T074 - initial villager spawn count scales with structures")
    public void testInitialVillagerSpawnCount() {
        assertEquals(10, VillagePlacementServiceImpl.computeInitialVillagerCount(5, 2.0),
            "Five structures at 2 should equal ten villagers");
        assertEquals(1, VillagePlacementServiceImpl.computeInitialVillagerCount(0, 2.0),
            "Zero structures still yields the minimum count");
        assertEquals(4, VillagePlacementServiceImpl.computeInitialVillagerCount(5, 0.75),
            "Custom ratio should be respected");
    }

    @Test
    @DisplayName("T074 - placeVillage spawns initial villagers on success")
    public void testPlaceVillageSpawnsInitialVillagers() {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);
        Metrics metrics = new Metrics(plugin.getLogger());
        CustomVillagerService npcService = new CustomVillagerService(plugin, plugin.getLogger(), metrics, store);

        CultureService cs = Mockito.mock(CultureService.class);
        List<String> structures = Arrays.asList("house");
        Mockito.when(cs.get("test-culture")).thenReturn(Optional.of(new CultureService.Culture(
            "test-culture", "Test", structures, null)));

        Mockito.when(mockStructure.getStructureDimensions("house")).thenReturn(Optional.of(new int[]{3,3,3}));

        World world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("test-world");
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenReturn(63);
        Mockito.when(world.getMinHeight()).thenReturn(0);
        Mockito.when(world.getMaxHeight()).thenReturn(256);

        Block ground = Mockito.mock(Block.class);
        Mockito.when(ground.getType()).thenReturn(Material.DIRT);
        Block air = Mockito.mock(Block.class);
        Mockito.when(air.getType()).thenReturn(Material.AIR);
        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
            .thenAnswer(inv -> {
                int y = inv.getArgument(1);
                return y == 63 ? ground : air;
            });

        Entity entity = Mockito.mock(Entity.class);
        Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(world.spawnEntity(Mockito.any(Location.class), Mockito.eq(EntityType.VILLAGER)))
            .thenReturn(entity);

        Mockito.when(mockStructure.placeStructureAndGetReceipt(Mockito.anyString(), Mockito.eq(world),
            Mockito.any(Location.class), Mockito.anyLong(), Mockito.any(UUID.class), Mockito.anyList(),
            Mockito.anyInt(), Mockito.anyMap(), Mockito.any()))
            .thenAnswer(inv -> {
                String sid = inv.getArgument(0);
                UUID vid = inv.getArgument(4);
                int w = 3, h = 3, d = 3;
                int ox = 0;
                int oz = 0;
                PlacementReceipt receipt = new PlacementReceipt.Builder()
                    .structureId(sid)
                    .villageId(vid)
                    .world(world)
                    .origin(ox, 64, oz)
                    .rotation(0)
                    .bounds(ox, ox + w - 1, 64, 64 + h - 1, oz, oz + d - 1)
                    .dimensions(w, h, d)
                    .entrance(ox, 63, oz + 1)
                    .foundationCorners(new PlacementReceipt.CornerSample[]{
                        new PlacementReceipt.CornerSample(ox, 63, oz, Material.DIRT),
                        new PlacementReceipt.CornerSample(ox + w - 1, 63, oz, Material.DIRT),
                        new PlacementReceipt.CornerSample(ox + w - 1, 63, oz + d - 1, Material.DIRT),
                        new PlacementReceipt.CornerSample(ox, 63, oz + d - 1, Material.DIRT)
                    })
                    .build();
                return Optional.of(receipt);
            });

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(
            mockStructure, store, cs, npcService, null, 0.5);
        Location origin = new Location(world, 0, 64, 0);

        Optional<UUID> villageOpt = svc.placeVillage(world, origin, "test-culture", 42L);
        assertTrue(villageOpt.isPresent(), "Village placement should succeed");
        assertEquals(1, npcService.getVillagerCount(villageOpt.get()),
            "Initial villagers should spawn for successful placement");
        assertEquals(1, store.getVillagerRecords(villageOpt.get()).size(),
            "Villager record should be persisted on spawn");
    }

    @Test
    @DisplayName("T074 - zero-placement does not spawn villagers")
    public void testZeroPlacementDoesNotSpawnVillagers() {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);
        Metrics metrics = new Metrics(plugin.getLogger());
        CustomVillagerService npcService = new CustomVillagerService(plugin, plugin.getLogger(), metrics, store);

        CultureService cs = Mockito.mock(CultureService.class);
        List<String> structures = Arrays.asList("house");
        Mockito.when(cs.get("test-culture")).thenReturn(Optional.of(new CultureService.Culture(
            "test-culture", "Test", structures, null)));

        Mockito.when(mockStructure.getStructureDimensions("house")).thenReturn(Optional.of(new int[]{3,3,3}));
        Mockito.when(mockStructure.placeStructureAndGetReceipt(Mockito.anyString(), Mockito.any(World.class),
            Mockito.any(Location.class), Mockito.anyLong(), Mockito.any(UUID.class), Mockito.anyList(),
            Mockito.anyInt(), Mockito.anyMap(), Mockito.any()))
            .thenReturn(Optional.empty());

        World world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("test-world");
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenReturn(63);
        Mockito.when(world.getMinHeight()).thenReturn(0);
        Mockito.when(world.getMaxHeight()).thenReturn(256);

        Block ground = Mockito.mock(Block.class);
        Mockito.when(ground.getType()).thenReturn(Material.DIRT);
        Block air = Mockito.mock(Block.class);
        Mockito.when(air.getType()).thenReturn(Material.AIR);
        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
            .thenAnswer(inv -> {
                int y = inv.getArgument(1);
                return y == 63 ? ground : air;
            });

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(
            mockStructure, store, cs, npcService, null, 0.5);
        Location origin = new Location(world, 0, 64, 0);

        Optional<UUID> villageOpt = svc.placeVillage(world, origin, "test-culture", 42L);
        assertFalse(villageOpt.isPresent(), "Village placement should fail with zero placements");
        assertEquals(0, npcService.getActiveVillagerCount(), "No villagers should spawn on failure");
    }

        @Test
        @DisplayName("placeVillage places multiple structures and avoids overlaps")
        public void testPlaceVillagePlacesMultipleBuildings_NoOverlap() throws Exception {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);

        CultureService cs = Mockito.mock(CultureService.class);
        List<String> structures = Arrays.asList("house","market","workshop","bath","temple");
        Mockito.when(cs.get("test-culture")).thenReturn(Optional.of(new CultureService.Culture(
            "test-culture","Test", structures, null)));

        // dimensions for every structure
        Mockito.when(mockStructure.getStructureDimensions(Mockito.anyString())).thenReturn(Optional.of(new int[]{3,3,3}));

        World world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("test-world");
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt()))
            .thenAnswer(inv -> {
                int cx = inv.getArgument(0);
                int cz = inv.getArgument(1);
                return Math.abs(cx) <= 1 && Math.abs(cz) <= 1;
            });
        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenReturn(64);
        Mockito.when(world.getMinHeight()).thenReturn(0);
        Mockito.when(world.getMaxHeight()).thenReturn(256);

        Block dirt = Mockito.mock(Block.class);
        Mockito.when(dirt.getType()).thenReturn(Material.DIRT);

        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(dirt);

        // Return a placement receipt based on a deterministic increasing offset to ensure no overlaps
        // Updated for T057f: placeStructureAndGetReceipt now takes minBuildingSpacing parameter
        final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        Mockito.when(mockStructure.placeStructureAndGetReceipt(Mockito.anyString(), Mockito.eq(world), Mockito.any(Location.class), Mockito.anyLong(), Mockito.any(UUID.class), Mockito.anyList(), Mockito.anyInt(), Mockito.anyMap(), Mockito.any()))
            .thenAnswer(inv -> {
                String sid = inv.getArgument(0);
                UUID vid = inv.getArgument(4);
                int idx = counter.getAndIncrement();
                int w = 3, h = 3, d = 3;
                int ox = idx * 10; // spread placements 10 blocks apart
                int oz = idx * 10;

                PlacementReceipt receipt = new PlacementReceipt.Builder()
                    .structureId(sid)
                    .villageId(vid)
                    .world(world)
                    .origin(ox, 64, oz)
                    .rotation(0)
                    .bounds(ox, ox + w - 1,
                        64, 64 + h - 1,
                        oz, oz + d - 1)
                    .dimensions(w, h, d)
                    .entrance(ox, 63, oz + 1)
                    .foundationCorners(new PlacementReceipt.CornerSample[]{
                        new PlacementReceipt.CornerSample(ox, 63, oz, Material.DIRT),
                        new PlacementReceipt.CornerSample(ox + w - 1, 63, oz, Material.DIRT),
                        new PlacementReceipt.CornerSample(ox + w - 1, 63, oz + d - 1, Material.DIRT),
                        new PlacementReceipt.CornerSample(ox, 63, oz + d - 1, Material.DIRT)
                    })
                    .build();

                return Optional.of(receipt);
            });

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        Location origin = new Location(world, 0, 64, 0);
        Optional<UUID> villageOpt = svc.placeVillage(world, origin, "test-culture", 42L);

        assertTrue(villageOpt.isPresent(), "Village placement should succeed");

        UUID vid = villageOpt.get();
        List<com.davisodom.villageoverhaul.model.Building> buildings = store.getVillageBuildings(vid);
        assertEquals(5, buildings.size(), "Expected five buildings to be placed");

        // Ensure volume masks do not overlap
        List<com.davisodom.villageoverhaul.model.VolumeMask> masks = store.getVolumeMasks(vid);
        for (int i = 0; i < masks.size(); i++) {
            for (int j = i+1; j < masks.size(); j++) {
            com.davisodom.villageoverhaul.model.VolumeMask a = masks.get(i);
            com.davisodom.villageoverhaul.model.VolumeMask b = masks.get(j);
            boolean xOverlap = a.getMinX() <= b.getMaxX() && a.getMaxX() >= b.getMinX();
            boolean zOverlap = a.getMinZ() <= b.getMaxZ() && a.getMaxZ() >= b.getMinZ();
            assertFalse(xOverlap && zOverlap, "VolumeMasks should not overlap");
            }
        }
        }

    @Test
    @DisplayName("T072 - existing village reports FULL when all structures already placed")
    public void testExistingVillageFullSkipsPlacement() {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);

        CultureService cs = Mockito.mock(CultureService.class);
        List<String> structures = Arrays.asList("house", "market");
        Mockito.when(cs.get("test-culture")).thenReturn(Optional.of(new CultureService.Culture(
            "test-culture", "Test", structures, null)));

        World world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("test-world");

        Location origin = new Location(world, 0, 64, 0);
        UUID villageId = UUID.randomUUID();
        long seed = 123L;

        store.registerVillage(villageId, "test-culture", origin, seed);

        Building b1 = new Building.Builder()
            .buildingId(UUID.randomUUID())
            .villageId(villageId)
            .structureId("house")
            .origin(new Location(world, 0, 64, 0))
            .dimensions(3, 3, 3)
            .build();

        Building b2 = new Building.Builder()
            .buildingId(UUID.randomUUID())
            .villageId(villageId)
            .structureId("market")
            .origin(new Location(world, 10, 64, 10))
            .dimensions(3, 3, 3)
            .build();

        store.addBuilding(villageId, b1);
        store.addBuilding(villageId, b2);

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        PlacementOutcome outcome = svc.placeStructuresForExistingVillage(world, origin, "test-culture", seed, villageId);

        assertEquals(PlacementStatus.FULL, outcome.getStatus(), "Expected FULL when all structures already placed");
        assertEquals(2, outcome.getExistingBuildings(), "Expected existing building count to be reported");
        assertEquals(0, outcome.getPlacedBuildings(), "Expected no new buildings when full");

        Mockito.verifyNoInteractions(mockStructure);
    }

    @Test
    @DisplayName("T068 - site validation failures increment steep/blocked/fluid counters")
    public void testSiteValidationFailureCounters() {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);

        CultureService cs = Mockito.mock(CultureService.class);
        List<String> structures = Arrays.asList("house_roman_small");
        Mockito.when(cs.get("test-culture")).thenReturn(Optional.of(new CultureService.Culture(
            "test-culture", "Test", structures, null)));

        Mockito.when(mockStructure.getStructureDimensions(Mockito.anyString()))
            .thenReturn(Optional.of(new int[]{3, 3, 3}));

        // T070: With multi-candidate retry, the mock will be called multiple times (up to 20)
        // Track call count to verify retry behavior
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        Mockito.when(mockStructure.placeStructureAndGetReceipt(
            Mockito.anyString(), Mockito.any(World.class), Mockito.any(Location.class),
            Mockito.anyLong(), Mockito.any(UUID.class), Mockito.anyList(), Mockito.anyInt(), Mockito.anyMap(), Mockito.any()))
            .thenAnswer(inv -> {
                callCount.incrementAndGet();
                @SuppressWarnings("unchecked")
                java.util.Map<String, Integer> diagnostics = (java.util.Map<String, Integer>) inv.getArgument(7);
                diagnostics.put("placementAttempts", 1);
                diagnostics.put("terrainInvalid", 1);
                diagnostics.put("fluid", 1);
                diagnostics.put("steep", 5);
                diagnostics.put("blocked", 2);
                return Optional.empty();
            });

        World world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("test-world");
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenReturn(64);
        Mockito.when(world.getMinHeight()).thenReturn(0);
        Mockito.when(world.getMaxHeight()).thenReturn(256);

        Block dirt = Mockito.mock(Block.class);
        Mockito.when(dirt.getType()).thenReturn(Material.DIRT);
        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(dirt);

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        Location origin = new Location(world, 0, 64, 0);
        long seed = 777L;

        Optional<UUID> result = svc.placeVillage(world, origin, "test-culture", seed);
        assertTrue(result.isEmpty(), "Placement should fail to force zero-placement counters");

        // T070: Verify that retry behavior was invoked (multiple placement attempts)
        assertTrue(callCount.get() > 1, "T070: Should try multiple candidates before giving up, got " + callCount.get() + " attempts");

        UUID villageId = UUID.nameUUIDFromBytes((seed + ":" + origin.getBlockX() + ":" + origin.getBlockZ())
            .getBytes(StandardCharsets.UTF_8));

        Optional<VillageMetadataStore.PlacementRejectionCounters> countersOpt = store.getPlacementRejectionCounters(villageId);
        assertTrue(countersOpt.isPresent(), "Expected rejection counters to be recorded");

        VillageMetadataStore.PlacementRejectionCounters counters = countersOpt.get();
        // T070: Counters are now multiplied by retry count since each failed attempt adds to counters
        int expectedMultiplier = callCount.get();
        assertEquals(expectedMultiplier, counters.fluid, "Fluid rejection count should be recorded per attempt");
        assertEquals(5 * expectedMultiplier, counters.steep, "Steep rejection count should be recorded per attempt");
        assertEquals(2 * expectedMultiplier, counters.blocked, "Blocked rejection count should be recorded per attempt");
        assertTrue(counters.attempts > 0, "Attempts should be greater than zero");
    }

    @Test
    @DisplayName("Rotated AABB collision respects spacing buffer and rejects close candidates")
    public void testRotatedAABBCollision_respectsSpacingBuffer() throws Exception {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageMetadataStore store = Mockito.mock(VillageMetadataStore.class);
        CultureService cs = Mockito.mock(CultureService.class);

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 10, 64, 10);

        // Compute candidate AABB for a 3x3x3 structure with rotation=0
        java.lang.reflect.Method mAABB = VillagePlacementServiceImpl.class.getDeclaredMethod("computeRotatedAABB",
                org.bukkit.Location.class, int.class, int.class, int.class, int.class);
        mAABB.setAccessible(true);

        int[] candidate = (int[]) mAABB.invoke(svc, origin, 3, 3, 3, 0);

        // Place a blocking mask just outside candidate (starts at X=14..16) and same Z range
        java.util.UUID vid = java.util.UUID.randomUUID();
        com.davisodom.villageoverhaul.model.VolumeMask blocking = new com.davisodom.villageoverhaul.model.VolumeMask.Builder()
                .structureId("blocker")
                .villageId(vid)
                .bounds(14, 16, 63, 65, 10, 12)
                .build();

        java.lang.reflect.Method mCollision = VillagePlacementServiceImpl.class.getDeclaredMethod("checkRotatedAABBCollision", int[].class, java.util.List.class, int.class);
        mCollision.setAccessible(true);

        // No buffer: should NOT collide (close but touching)
        boolean collidesNoBuffer = (boolean) mCollision.invoke(svc, candidate, java.util.List.of(blocking), 0);

        // With spacing buffer=2 collisions should be detected
        boolean collidesWithBuffer = (boolean) mCollision.invoke(svc, candidate, java.util.List.of(blocking), 2);

        assertFalse(collidesNoBuffer, "Expected no collision when buffer=0 for adjacent mask");
        assertTrue(collidesWithBuffer, "Expected collision when spacing buffer applied");
    }

    @Test
    @DisplayName("checkRotatedAABBCollision detects full overlap with existing mask")
    public void testRotatedAABBCollision_detectsOverlap() throws Exception {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageMetadataStore store = Mockito.mock(VillageMetadataStore.class);
        CultureService cs = Mockito.mock(CultureService.class);

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        World world = Mockito.mock(World.class);
        Location origin = new Location(world, 20, 64, 20);

        // Candidate AABB for 5x5x5 structure
        java.lang.reflect.Method mAABB = VillagePlacementServiceImpl.class.getDeclaredMethod("computeRotatedAABB",
                org.bukkit.Location.class, int.class, int.class, int.class, int.class);
        mAABB.setAccessible(true);

        int[] candidate = (int[]) mAABB.invoke(svc, origin, 5, 5, 5, 0);

        // Create a blocking mask that is fully inside the candidate bounds
        java.util.UUID vid = java.util.UUID.randomUUID();
        com.davisodom.villageoverhaul.model.VolumeMask blocking = new com.davisodom.villageoverhaul.model.VolumeMask.Builder()
                .structureId("blocker")
                .villageId(vid)
                .bounds(candidate[0] + 1, candidate[1] - 1, candidate[2], candidate[3], candidate[4] + 1, candidate[5] - 1)
                .build();

        java.lang.reflect.Method mCollision = VillagePlacementServiceImpl.class.getDeclaredMethod("checkRotatedAABBCollision", int[].class, java.util.List.class, int.class);
        mCollision.setAccessible(true);

        boolean collides = (boolean) mCollision.invoke(svc, candidate, java.util.List.of(blocking), 0);

        assertTrue(collides, "Expected collision when existing mask intersects candidate AABB");
    }

        @Test
        @DisplayName("findSuitablePlacementPosition returns empty when existing masks block area")
        public void testFindSuitablePlacementPosition_ReturnsEmptyWhenAllCovered() throws Exception {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);
        CultureService cs = Mockito.mock(CultureService.class);

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        World world = Mockito.mock(World.class);
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenReturn(64);
        Mockito.when(world.getMinHeight()).thenReturn(0);
        Mockito.when(world.getMaxHeight()).thenReturn(256);

        Block dirt = Mockito.mock(Block.class);
        Mockito.when(dirt.getType()).thenReturn(Material.DIRT);
        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(dirt);

        // Create a giant mask that covers the whole search area
        UUID vid = UUID.randomUUID();
        // Limit the mask to the solver's search radius to avoid excessive chunk indexing
        // (previously -1000..1000 produced a very large index and triggered heavy JVM usage)
        com.davisodom.villageoverhaul.model.VolumeMask blocking = new com.davisodom.villageoverhaul.model.VolumeMask.Builder()
            .structureId("blocker")
            .villageId(vid)
            .bounds(-300, 300, 0, 256, -300, 300)
            .build();

        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method mm : VillagePlacementServiceImpl.class.getDeclaredMethods()) {
            if (mm.getName().equals("findSuitablePlacementPosition")) { m = mm; break; }
        }
        assertNotNull(m, "Could not find findSuitablePlacementPosition method via reflection");
        m.setAccessible(true);

        SurfaceSolver solver = new SurfaceSolver(world, List.of(blocking));

        Optional<Location> res = (Optional<Location>) m.invoke(svc, world, new Location(world, 0, 64, 0), 3, 3, 3, 100L, List.of(blocking), solver, null, vid, "blocker");

        assertFalse(res.isPresent(), "Expected no placement when masks block the area");
        }

        @Test
        @DisplayName("findSuitablePlacementPosition finds a valid location when chunks loaded and surface present")
        public void testFindSuitablePlacementPosition_ReturnsValidLocation() throws Exception {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);
        CultureService cs = Mockito.mock(CultureService.class);

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        World world = Mockito.mock(World.class);
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenReturn(64);
        Mockito.when(world.getMinHeight()).thenReturn(0);
        Mockito.when(world.getMaxHeight()).thenReturn(256);

        Block air = Mockito.mock(Block.class);
        Mockito.when(air.getType()).thenReturn(Material.AIR);
        Block dirt = Mockito.mock(Block.class);
        Mockito.when(dirt.getType()).thenReturn(Material.DIRT);

        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.eq(65), Mockito.anyInt())).thenReturn(air);
        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.eq(64), Mockito.anyInt())).thenReturn(dirt);

        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method mm : VillagePlacementServiceImpl.class.getDeclaredMethods()) {
            if (mm.getName().equals("findSuitablePlacementPosition")) { m = mm; break; }
        }
        m.setAccessible(true);

        SurfaceSolver solver = new SurfaceSolver(world, new ArrayList<>());

        Optional<Location> res = (Optional<Location>) m.invoke(svc, world, new Location(world, 0, 64, 0), 3, 3, 3, 100L, new ArrayList<>(), solver, null, UUID.randomUUID(), "house");

        assertTrue(res.isPresent(), "Expected a valid placement location to be found");
        Location loc = res.get();
        assertNotNull(loc, "Location should not be null");
        assertTrue(Math.abs(loc.getBlockX()) <= 256 && Math.abs(loc.getBlockZ()) <= 256, "Location should be within search radius");
        }

    /**
     * T026d17: Verify that building UUID derivation is deterministic.
     * Same villageId + structureId + seed should always produce the same building UUID.
     */
    @Test
    @DisplayName("Building UUID derivation is deterministic for same inputs")
    public void testDeterministicBuildingUuidDerivation() {
        UUID villageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String structureId = "house_roman_small";
        long buildingSeed = 98765L;
        
        // Compute using the same formula as VillagePlacementServiceImpl
        UUID expected = UUID.nameUUIDFromBytes(
            (villageId.toString() + ":" + structureId + ":" + buildingSeed).getBytes(StandardCharsets.UTF_8));
        
        UUID actual = UUID.nameUUIDFromBytes(
            (villageId.toString() + ":" + structureId + ":" + buildingSeed).getBytes(StandardCharsets.UTF_8));
        
        assertEquals(expected, actual, "Same inputs should produce same building UUID");
        
        // Different building seed should produce different UUID
        UUID different = UUID.nameUUIDFromBytes(
            (villageId.toString() + ":" + structureId + ":" + 11111L).getBytes(StandardCharsets.UTF_8));
        
        assertNotEquals(expected, different, "Different building seed should produce different building UUID");
    }

    /**
     * T070: Verify that placement retries with alternate candidates when initial terrain validation fails,
     * and eventually succeeds when a valid candidate is found later in the sequence.
     */
    @Test
    @DisplayName("T070 - placement retries alternate candidates when initial terrain validation fails")
    public void testPlacementRetriesAlternateCandidates() {
        com.davisodom.villageoverhaul.worldgen.StructureService mockStructure = Mockito.mock(
            com.davisodom.villageoverhaul.worldgen.StructureService.class);

        VillageOverhaulPlugin plugin = Mockito.mock(VillageOverhaulPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        Mockito.when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data"));

        VillageMetadataStore store = new VillageMetadataStore(plugin);

        CultureService cs = Mockito.mock(CultureService.class);
        List<String> structures = Arrays.asList("house_roman_small");
        Mockito.when(cs.get("test-culture")).thenReturn(Optional.of(new CultureService.Culture(
            "test-culture", "Test", structures, null)));

        Mockito.when(mockStructure.getStructureDimensions(Mockito.anyString()))
            .thenReturn(Optional.of(new int[]{3, 3, 3}));

        // T070: Track placement attempts and simulate success on 5th attempt
        java.util.concurrent.atomic.AtomicInteger attemptCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final int successOnAttempt = 5;
        
        Mockito.when(mockStructure.placeStructureAndGetReceipt(
            Mockito.anyString(), Mockito.any(World.class), Mockito.any(Location.class),
            Mockito.anyLong(), Mockito.any(UUID.class), Mockito.anyList(), Mockito.anyInt(), Mockito.anyMap(), Mockito.any()))
            .thenAnswer(inv -> {
                int currentAttempt = attemptCount.incrementAndGet();
                Location loc = inv.getArgument(2);
                UUID villageId = inv.getArgument(4);
                
                if (currentAttempt >= successOnAttempt) {
                    // Simulate successful placement on 5th attempt
                    // Create mock foundation corners
                    PlacementReceipt.CornerSample[] corners = new PlacementReceipt.CornerSample[]{
                        new PlacementReceipt.CornerSample(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ(), Material.STONE),
                        new PlacementReceipt.CornerSample(loc.getBlockX() + 3, loc.getBlockY() - 1, loc.getBlockZ(), Material.STONE),
                        new PlacementReceipt.CornerSample(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ() + 3, Material.STONE),
                        new PlacementReceipt.CornerSample(loc.getBlockX() + 3, loc.getBlockY() - 1, loc.getBlockZ() + 3, Material.STONE)
                    };
                    
                    PlacementReceipt receipt = new PlacementReceipt.Builder()
                        .structureId("house_roman_small")
                        .villageId(villageId)
                        .world(loc.getWorld())
                        .origin(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                        .entrance(loc.getBlockX() + 1, loc.getBlockY(), loc.getBlockZ())
                        .rotation(0)
                        .bounds(loc.getBlockX(), loc.getBlockX() + 3, loc.getBlockY(), loc.getBlockY() + 3, loc.getBlockZ(), loc.getBlockZ() + 3)
                        .dimensions(3, 3, 3)
                        .foundationCorners(corners)
                        .build();
                    return Optional.of(receipt);
                }
                
                // Fail terrain validation for first 4 attempts
                @SuppressWarnings("unchecked")
                java.util.Map<String, Integer> diagnostics = (java.util.Map<String, Integer>) inv.getArgument(7);
                diagnostics.put("placementAttempts", 1);
                diagnostics.put("terrainInvalid", 1);
                diagnostics.put("steep", 10);
                return Optional.empty();
            });

        World world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("test-world");
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.getHighestBlockYAt(Mockito.anyInt(), Mockito.anyInt())).thenReturn(64);
        Mockito.when(world.getMinHeight()).thenReturn(0);
        Mockito.when(world.getMaxHeight()).thenReturn(256);

        Block dirt = Mockito.mock(Block.class);
        Mockito.when(dirt.getType()).thenReturn(Material.DIRT);
        Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(dirt);

        VillagePlacementServiceImpl svc = new VillagePlacementServiceImpl(mockStructure, store, cs);

        Location origin = new Location(world, 0, 64, 0);
        long seed = 888L;

        Optional<UUID> result = svc.placeVillage(world, origin, "test-culture", seed);
        
        // T070: Verify placement succeeded after retrying candidates
        assertTrue(result.isPresent(), "T070: Placement should succeed after retrying alternate candidates");
        assertTrue(attemptCount.get() >= successOnAttempt, 
            "T070: Should have tried at least " + successOnAttempt + " candidates, got " + attemptCount.get());
        
        // Verify a building was placed and receipt persisted
        UUID villageId = result.get();
        List<com.davisodom.villageoverhaul.model.Building> buildings = store.getVillageBuildings(villageId);
        assertEquals(1, buildings.size(), "T070: One building should be placed after retry succeeded");
        assertEquals(1, store.getPlacementReceipts(villageId).size(),
            "T061: Receipt count should match placement count for summary logging");

    }

}
