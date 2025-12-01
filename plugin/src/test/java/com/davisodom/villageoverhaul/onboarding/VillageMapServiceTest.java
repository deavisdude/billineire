package com.davisodom.villageoverhaul.onboarding;

import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.model.VolumeMask;
import com.davisodom.villageoverhaul.test.FakeWorld;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T027n: Village map model integrity tests.
 *
 * Verifies footprints, border expansion, and stable retrieval semantics.
 */
public class VillageMapServiceTest {

    private VillageOverhaulPlugin plugin;
    private VillageMetadataStore store;
    private FakeWorld fake;
    private World world;

    @BeforeEach
    public void setUp() {
        plugin = org.mockito.Mockito.mock(VillageOverhaulPlugin.class);
        org.mockito.Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        java.io.File dataDir = new java.io.File("build/test-data");
        dataDir.mkdirs();
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(dataDir);

        store = new VillageMetadataStore(plugin);

        fake = new FakeWorld();
        world = fake.getWorld();
        org.mockito.Mockito.when(world.getName()).thenReturn("world");
    }

    @AfterEach
    public void tearDown() {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(plugin.getDataFolder().getAbsolutePath(), "villages");
            if (java.nio.file.Files.exists(dir)) {
                java.nio.file.Files.walk(dir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
            }
        } catch (Exception ignored) {}
    }

    @Test
    public void testFootprintsAddedAndRetrievedAndImmutable() {
        UUID vid = UUID.randomUUID();
        Location origin = new Location(world, 0, 64, 0);
        store.registerVillage(vid, "roman", origin, 1111L);

        Building b1 = new Building.Builder()
                .villageId(vid)
                .structureId("house")
                .origin(new Location(world, 0, 64, 0))
                .dimensions(5,5,5)
                .build();

        Building b2 = new Building.Builder()
                .villageId(vid)
                .structureId("barn")
                .origin(new Location(world, 10, 64, 0))
                .dimensions(3,4,3)
                .build();

        store.addBuilding(vid, b1);
        store.addBuilding(vid, b2);

        List<Building> first = store.getVillageBuildings(vid);
        assertEquals(2, first.size(), "Should have two footprints after additions");

        // Mutating the returned list must not affect the store
        first.remove(0);
        List<Building> second = store.getVillageBuildings(vid);
        assertEquals(2, second.size(), "Underlying storage should not be changed by client-side mutation");
    }

    @Test
    public void testVolumeMaskListStableAcrossQueries() {
        UUID vid = UUID.randomUUID();
        Location origin = new Location(world, 50, 64, 50);
        store.registerVillage(vid, "greek", origin, 2222L);

        VolumeMask mask = new VolumeMask.Builder()
                .structureId("s1")
                .villageId(vid)
                .bounds(0,4,60,64,0,4)
                .build();

        store.addVolumeMask(vid, mask);

        List<VolumeMask> a = store.getVolumeMasks(vid);
        List<VolumeMask> b = store.getVolumeMasks(vid);

        assertEquals(1, a.size(), "First query should return a single mask");
        assertEquals(1, b.size(), "Second query should return a single mask");

        // Mutate returned list and assert store is unaffected
        a.clear();
        List<VolumeMask> c = store.getVolumeMasks(vid);
        assertEquals(1, c.size(), "Clearing client list should not remove store entries");
    }

    @Test
    public void testBorderExpansionAndDistanceMetrics() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();

        store.registerVillage(v1, "one", new Location(world, 0, 64, 0), 1L);
        store.registerVillage(v2, "two", new Location(world, 100, 64, 100), 2L);

        // Add a building to v1 at (0,0) size 5x5 to expand border
        Building b1 = new Building.Builder()
                .villageId(v1)
                .structureId("a")
                .origin(new Location(world, -2, 64, -2))
                .dimensions(5,5,5)
                .build();
        store.addBuilding(v1, b1);

        // Add building to v2 near the other village
        Building b2 = new Building.Builder()
                .villageId(v2)
                .structureId("b")
                .origin(new Location(world, 95, 64, 95))
                .dimensions(6,5,6)
                .build();
        store.addBuilding(v2, b2);

        Optional<VillageMetadataStore.VillageMetadata> m1 = store.getVillage(v1);
        Optional<VillageMetadataStore.VillageMetadata> m2 = store.getVillage(v2);
        assertTrue(m1.isPresent());
        assertTrue(m2.isPresent());

        VillageMetadataStore.VillageBorder bdr1 = m1.get().getBorder();
        VillageMetadataStore.VillageBorder bdr2 = m2.get().getBorder();

        // Borders should not be empty and distances calculable
        assertTrue(bdr1.getWidth() > 0 && bdr2.getWidth() > 0);

        int dist = bdr1.getDistanceTo(bdr2);
        assertTrue(dist >= 0, "Distance should be non-negative");

        boolean within = bdr1.isWithinDistance(bdr2, 500);
        assertTrue(within, "With large threshold, borders should be within distance");
    }
}
