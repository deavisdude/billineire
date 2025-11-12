package com.davisodom.villageoverhaul.villages;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
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
    
    private ServerMock server;
    private VillageOverhaulPlugin plugin;
    private VillageMetadataStore store;
    private World world;
    
    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VillageOverhaulPlugin.class);
        store = new VillageMetadataStore(plugin);
        world = server.addSimpleWorld("world");
    }
    
    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
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
        
        // Load from disk
        store.loadAll();
        
        // Verify main building is restored
        Optional<UUID> afterLoad = store.getMainBuilding(villageId);
        assertTrue(afterLoad.isPresent(), "Main building should be restored after load");
        assertEquals(mainBuildingId, afterLoad.get(), "Restored main building ID should match");
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
        
        // Clear in-memory data
        store.clearAll();
        
        // Verify cleared
        assertFalse(store.getPathNetwork(villageId).isPresent(),
            "Path network should be cleared after clearAll()");
        
        // Load from disk
        store.loadAll();
        
        // Verify path network is restored
        Optional<PathNetwork> afterLoad = store.getPathNetwork(villageId);
        assertTrue(afterLoad.isPresent(), "Path network should be restored after load");
        assertEquals(1, afterLoad.get().getSegments().size(), "Restored network should have 1 segment");
        assertEquals(3, afterLoad.get().getTotalBlocksPlaced(), "Restored network should have 3 blocks");
        
        // Verify segment details
        PathNetwork.PathSegment restoredSegment = afterLoad.get().getSegments().get(0);
        assertEquals(100, restoredSegment.getStart().getBlockX(), "Start X should match");
        assertEquals(120, restoredSegment.getEnd().getBlockX(), "End X should match");
        assertEquals(3, restoredSegment.getBlocks().size(), "Segment should have 3 blocks");
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
        
        // Save, clear, load
        store.saveAll();
        store.clearAll();
        store.loadAll();
        
        // Verify both are restored
        assertTrue(store.getMainBuilding(villageId).isPresent(), "Main building should be restored");
        assertTrue(store.getPathNetwork(villageId).isPresent(), "Path network should be restored");
        
        assertEquals(mainBuildingId, store.getMainBuilding(villageId).get(), 
            "Main building ID should match after restore");
        assertEquals(2, store.getPathNetwork(villageId).get().getTotalBlocksPlaced(),
            "Path network blocks should match after restore");
    }
    
    @Test
    public void testSaveEmptyStore() throws IOException {
        // Should not throw exception when saving empty store
        assertDoesNotThrow(() -> store.saveAll(), "Saving empty store should not throw");
    }
    
    @Test
    public void testLoadNonexistentFiles() throws IOException {
        // Should not throw exception when no files exist
        assertDoesNotThrow(() -> store.loadAll(), "Loading with no files should not throw");
    }
}
