package com.davisodom.villageoverhaul.villages;

import com.davisodom.villageoverhaul.test.FakeWorld;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.model.PathNetwork;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test T024: Persist mainBuildingId and pathNetwork
 */
public class VillageMetadataStoreTest {
    
    private VillageOverhaulPlugin plugin;
    private VillageMetadataStore store;
    private World world;
    private FakeWorld fake;
    
    @BeforeEach
    public void setUp() {
        // Use Mockito-backed plugin/world to avoid MockBukkit registry/init races
        plugin = org.mockito.Mockito.mock(VillageOverhaulPlugin.class);
        org.mockito.Mockito.when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        java.io.File dataDir = new java.io.File("build/test-data");
        dataDir.mkdirs();
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(dataDir);

        store = new VillageMetadataStore(plugin);

        fake = new FakeWorld();
        world = fake.getWorld();
        org.mockito.Mockito.when(world.getName()).thenReturn("world");
        // Provide simple Block mocks for getBlockAt used by tests
        // FakeWorld covers getBlockAt and highest-block behavior used by this store's tests
    }
    
    @AfterEach
    public void tearDown() {
        // cleanup persisted test data
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
    public void testPersistMainBuildingId() throws IOException {
        // Register a village
        UUID villageId = UUID.randomUUID();
        Location origin = new Location(world, 0, 64, 0);
        store.registerVillage(villageId, "roman", origin, 12345L);
        
        // Designate main building
        UUID mainBuildingId = UUID.randomUUID();
        store.setMainBuilding(villageId, mainBuildingId);
        
        // Verify main building is set before save
        Optional<UUID> beforeSave = store.getMainBuilding(villageId);
        assertTrue(beforeSave.isPresent(), "Main building should be set before save");
        assertEquals(mainBuildingId, beforeSave.get(), "Main building ID should match");
        
        // Save to disk
        store.saveAll();

        // Clear in-memory data
        store.clearAll();

        // Verify cleared
        assertFalse(store.getMainBuilding(villageId).isPresent(),
            "Main building should be cleared after clearAll()");

        // Verify JSON file contains mainBuildingId — do not rely on Bukkit world lookup here
        java.io.File f = new java.io.File(plugin.getDataFolder(), "villages/village_" + villageId + ".json");
        assertTrue(f.exists(), "Village JSON artifact should exist");
        String content = java.nio.file.Files.readString(f.toPath());
        assertTrue(content.contains(mainBuildingId.toString()), "Saved JSON should contain mainBuildingId");
    }
    
    @Test
    public void testPersistPathNetwork() throws IOException {
        // Register a village
        UUID villageId = UUID.randomUUID();
        Location origin = new Location(world, 100, 64, 100);
        store.registerVillage(villageId, "greek", origin, 67890L);
        
        // Create a simple path network
        Location start = new Location(world, 100, 64, 100);
        Location end = new Location(world, 120, 64, 120);
        
        List<Block> blocks = new ArrayList<>();
        blocks.add(world.getBlockAt(100, 64, 100));
        blocks.add(world.getBlockAt(110, 64, 110));
        blocks.add(world.getBlockAt(120, 64, 120));
        
        PathNetwork.PathSegment segment = new PathNetwork.PathSegment(start, end, blocks);
        
        PathNetwork network = new PathNetwork.Builder()
            .villageId(villageId)
            .addSegment(segment)
            .generatedTimestamp(System.currentTimeMillis())
            .build();
        
        store.setPathNetwork(villageId, network);
        
        // Verify path network is set before save
        Optional<PathNetwork> beforeSave = store.getPathNetwork(villageId);
        assertTrue(beforeSave.isPresent(), "Path network should be set before save");
        assertEquals(1, beforeSave.get().getSegments().size(), "Should have 1 segment");
        assertEquals(3, beforeSave.get().getTotalBlocksPlaced(), "Should have 3 blocks");
        
        // Save to disk
        store.saveAll();

        // Clear in-memory data and verify cleared
        store.clearAll();
        assertFalse(store.getPathNetwork(villageId).isPresent(), "Path network should be cleared after clearAll()");

        // Inspect persisted JSON for pathNetwork structure instead of loadAll (avoid Bukkit.getWorld static lookup)
        java.io.File fnet = new java.io.File(plugin.getDataFolder(), "villages/village_" + villageId + ".json");
        assertTrue(fnet.exists(), "Village JSON artifact should exist for path network");
        String contentNet = java.nio.file.Files.readString(fnet.toPath());
        assertTrue(contentNet.contains("pathNetwork"), "Saved JSON should contain pathNetwork section");
        assertTrue(contentNet.contains("blocks"), "Saved JSON should contain blocks array");
    }
    
    @Test
    public void testPersistBothMainBuildingAndPathNetwork() throws IOException {
        // Register a village
        UUID villageId = UUID.randomUUID();
        Location origin = new Location(world, 200, 64, 200);
        store.registerVillage(villageId, "egyptian", origin, 11111L);
        
        // Set main building
        UUID mainBuildingId = UUID.randomUUID();
        store.setMainBuilding(villageId, mainBuildingId);
        
        // Set path network
        Location start = new Location(world, 200, 64, 200);
        Location end = new Location(world, 210, 64, 210);
        List<Block> blocks = new ArrayList<>();
        blocks.add(world.getBlockAt(200, 64, 200));
        blocks.add(world.getBlockAt(210, 64, 210));
        
        PathNetwork network = new PathNetwork.Builder()
            .villageId(villageId)
            .addSegment(new PathNetwork.PathSegment(start, end, blocks))
            .build();
        
        store.setPathNetwork(villageId, network);
        
        // Save, clear
        store.saveAll();
        store.clearAll();

        // Validate persistence artifacts (main building + path network present in JSON)
        java.io.File f2 = new java.io.File(plugin.getDataFolder(), "villages/village_" + villageId + ".json");
        assertTrue(f2.exists(), "Village JSON should exist");
        String content2 = java.nio.file.Files.readString(f2.toPath());
        assertTrue(content2.contains(mainBuildingId.toString()), "Saved JSON should contain main building id");
        assertTrue(content2.contains("pathNetwork"), "Saved JSON should contain path network");
    }
    
    @Test
    public void testSaveEmptyStore() throws IOException {
        // Should not throw exception when saving empty store
        assertDoesNotThrow(() -> store.saveAll(), "Saving empty store should not throw");
    }

    @Test
    public void testPersistPlacementRejectionCounters() throws IOException {
        UUID villageId = UUID.randomUUID();
        Location origin = new Location(world, 300, 64, 300);
        store.registerVillage(villageId, "test", origin, 424242L);

        // Create counters and record them
        VillageMetadataStore.PlacementRejectionCounters counters = new VillageMetadataStore.PlacementRejectionCounters(
                123, // attempts
                10,  // fluid
                5,   // steep
                3,   // blocked
                8,   // spacing
                2,   // overlap
                1,   // chunkNotReady
                200  // candidates
        );

        store.recordPlacementRejectionCounters(villageId, counters);

        // Ensure in-memory access works
        Optional<VillageMetadataStore.PlacementRejectionCounters> before = store.getPlacementRejectionCounters(villageId);
        assertTrue(before.isPresent(), "Counters should be present in memory after recording");
        assertEquals(123, before.get().attempts, "Attempts should match the recorded value");

        // Save to disk (recordPlacementRejectionCounters also writes an artifact)
        store.saveAll();

        // Ensure artifact file exists in the plugin data folder
        java.io.File artifact = new java.io.File(plugin.getDataFolder(), "villages/village_" + villageId + "_placement_rejections.json");
        assertTrue(artifact.exists(), "Counters artifact file should exist on disk");

        // Read artifact JSON and assert values persisted; cannot rely on loadAll due to Bukkit.getWorld lookup
        String text = java.nio.file.Files.readString(artifact.toPath());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(text);
        com.fasterxml.jackson.databind.JsonNode data = root.get("data");
        assertNotNull(data, "Artifact should contain data envelope");
        VillageMetadataStore.PlacementRejectionCounters parsed = mapper.treeToValue(data, VillageMetadataStore.PlacementRejectionCounters.class);
        assertEquals(10, parsed.fluid, "Artifact JSON should contain fluid=10");
    }

    @Test
    public void testArtifactCreatedOnRegister() throws IOException {
        UUID villageId = UUID.randomUUID();
        Location origin = new Location(world, 400, 64, 400);
        store.registerVillage(villageId, "test", origin, 99999L);

        java.io.File artifact = new java.io.File(plugin.getDataFolder(), "villages/village_" + villageId + "_placement_rejections.json");
        assertTrue(artifact.exists(), "Artifact should exist immediately after registerVillage()");

        // Load store and ensure counters restored
        store.loadAll();
        Optional<VillageMetadataStore.PlacementRejectionCounters> counters = store.getPlacementRejectionCounters(villageId);
        assertTrue(counters.isPresent(), "Counters should be present after loadAll");
        assertEquals(0, counters.get().attempts, "Initial attempts counter should be zero");
    }

    @Test
    public void testArtifactCreatedOnAddPlacementReceipt() throws IOException {
        UUID villageId = UUID.randomUUID();
        Location origin = new Location(world, 500, 64, 500);
        store.registerVillage(villageId, "test", origin, 424242L);

        // Remove the artifact to simulate missing artifact before adding receipt
        java.io.File artifact = new java.io.File(plugin.getDataFolder(), "villages/village_" + villageId + "_placement_rejections.json");
        if (artifact.exists()) artifact.delete();

        // Add a fake receipt which should trigger artifact creation
        com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[] corners = new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[4];
        corners[0] = new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(0,0,0, org.bukkit.Material.STONE);
        com.davisodom.villageoverhaul.model.PlacementReceipt receipt = new com.davisodom.villageoverhaul.model.PlacementReceipt.Builder()
                .structureId("test")
                .villageId(villageId)
                .world(world)
                .origin(0,64,0)
                .rotation(0)
                .bounds(0,1,64,65,0,1)
                .dimensions(2,2,2)
                .entrance(1,64,0)
                .foundationCorners(corners)
                .timestamp(System.currentTimeMillis())
                .build();

        store.addPlacementReceipt(villageId, receipt);

        assertTrue(artifact.exists(), "Artifact should exist after addPlacementReceipt()");
    }
    
    @Test
    public void testLoadNonexistentFiles() throws IOException {
        // Should not throw exception when no files exist
        assertDoesNotThrow(() -> store.loadAll(), "Loading with no files should not throw");
    }
}
