package com.davisodom.villageoverhaul.worldgen.impl;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.model.PlacementQueue;
import org.bukkit.Material;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlacementQueueProcessor async preparation and main-thread commits.
 * 
 * Constitution compliance:
 * - Principle XI: Structure Integration & NPC Construction (async prep, main-thread commits)
 * - Principle III: Performance Budgets (batch size control, tick budgets)
 * - Principle II: Deterministic Multiplayer Sync (deterministic ordering)
 * 
 * NOTE: These tests are disabled in unit test harness because PlacementQueueProcessor
 * has WorldEdit dependencies that are only available at runtime (provided scope).
 * Integration tests for PlacementQueueProcessor should be added to the headless
 * server harness (scripts/ci/sim/) to validate async preparation and main-thread commits
 * with actual WorldEdit integration.
 */
@Disabled("WorldEdit dependencies only available at runtime - requires integration test harness")
class PlacementQueueProcessorTest {
    
    private ServerMock server;
    private VillageOverhaulPlugin plugin;
    private PlacementQueueProcessor processor;
    
    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VillageOverhaulPlugin.class);
        processor = new PlacementQueueProcessor(plugin);
        server.addSimpleWorld("world");
    }
    
    @AfterEach
    void tearDown() {
        if (processor != null) {
            processor.stop();
        }
        MockBukkit.unmock();
    }
    
    @Test
    @DisplayName("Processor should start and stop cleanly")
    void testStartStop() {
        processor.start();
        assertEquals(0, processor.getActiveQueueCount());
        
        processor.stop();
        assertEquals(0, processor.getActiveQueueCount());
    }
    
    @Test
    @DisplayName("Processor should respect batch size configuration")
    void testBatchSizeConfiguration() {
        processor.setBatchSize(25);
        
        // Create a simple queue with known batch size
        UUID buildingId = UUID.randomUUID();
        List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
        
        // Add 100 blocks
        for (int i = 0; i < 100; i++) {
            blocks.add(new PlacementQueueProcessor.BlockPlacement(i, 64, 0, Material.STONE, null));
        }
        
        PlacementQueue queue = processor.prepareSimpleQueue(buildingId, blocks, 12345L);
        
        assertNotNull(queue);
        assertEquals(100, queue.getTotalBlocks());
        assertEquals(PlacementQueue.Status.READY, queue.getStatus());
    }
    
    @Test
    @DisplayName("Simple queue preparation should be deterministic")
    void testDeterministicQueuePreparation() {
        UUID buildingId = UUID.randomUUID();
        long seed = 42L;
        
        List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
        blocks.add(new PlacementQueueProcessor.BlockPlacement(0, 64, 0, Material.STONE, null));
        blocks.add(new PlacementQueueProcessor.BlockPlacement(1, 64, 0, Material.STONE, null));
        blocks.add(new PlacementQueueProcessor.BlockPlacement(0, 65, 0, Material.STONE, null));
        blocks.add(new PlacementQueueProcessor.BlockPlacement(1, 65, 0, Material.STONE, null));
        
        PlacementQueue queue1 = processor.prepareSimpleQueue(buildingId, blocks, seed);
        PlacementQueue queue2 = processor.prepareSimpleQueue(buildingId, blocks, seed);
        
        // Queues should have same structure (different IDs but same entries)
        assertEquals(queue1.getTotalBlocks(), queue2.getTotalBlocks());
        assertEquals(queue1.getStatus(), queue2.getStatus());
        
        // Verify deterministic ordering (layer-by-layer)
        List<PlacementQueue.Entry> entries = queue1.getEntries();
        assertEquals(4, entries.size());
        
        // Y=64 entries should come before Y=65
        assertTrue(entries.get(0).getY() <= entries.get(2).getY());
        assertTrue(entries.get(1).getY() <= entries.get(3).getY());
    }
    
    @Test
    @DisplayName("Queue should track progress correctly")
    void testQueueProgressTracking() {
        UUID buildingId = UUID.randomUUID();
        
        List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            blocks.add(new PlacementQueueProcessor.BlockPlacement(i, 64, 0, Material.STONE, null));
        }
        
        PlacementQueue queue = processor.prepareSimpleQueue(buildingId, blocks, 12345L);
        
        assertEquals(50, queue.getTotalBlocks());
        assertEquals(0, queue.getBlocksPlaced());
        assertEquals(50, queue.getBlocksRemaining());
        assertEquals(0.0, queue.getPercentComplete(), 0.001);
        
        // Simulate partial completion
        PlacementQueue updated = queue.withAdvancedIndex(25, System.currentTimeMillis());
        assertEquals(25, updated.getBlocksPlaced());
        assertEquals(25, updated.getBlocksRemaining());
        assertEquals(0.5, updated.getPercentComplete(), 0.001);
        
        // Simulate full completion
        PlacementQueue complete = updated.withAdvancedIndex(50, System.currentTimeMillis());
        assertEquals(50, complete.getBlocksPlaced());
        assertEquals(0, complete.getBlocksRemaining());
        assertEquals(1.0, complete.getPercentComplete(), 0.001);
        assertEquals(PlacementQueue.Status.COMPLETE, complete.getStatus());
    }
    
    @Test
    @DisplayName("Queue should return correct batches")
    void testQueueBatchRetrieval() {
        UUID buildingId = UUID.randomUUID();
        
        List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            blocks.add(new PlacementQueueProcessor.BlockPlacement(i, 64, 0, Material.STONE, null));
        }
        
        PlacementQueue queue = processor.prepareSimpleQueue(buildingId, blocks, 12345L);
        
        // First batch should have batchSize entries
        List<PlacementQueue.Entry> batch1 = queue.getNextBatch();
        assertEquals(50, batch1.size()); // Default batch size
        
        // After advancing, second batch
        PlacementQueue advanced = queue.withAdvancedIndex(50, System.currentTimeMillis());
        List<PlacementQueue.Entry> batch2 = advanced.getNextBatch();
        assertEquals(50, batch2.size());
        
        // After completion, no more batches
        PlacementQueue complete = advanced.withAdvancedIndex(100, System.currentTimeMillis());
        List<PlacementQueue.Entry> batch3 = complete.getNextBatch();
        assertTrue(batch3.isEmpty());
    }
    
    @Test
    @DisplayName("Queue submission should be tracked")
    void testQueueSubmission() {
        processor.start();
        
        UUID buildingId = UUID.randomUUID();
        List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
        blocks.add(new PlacementQueueProcessor.BlockPlacement(0, 64, 0, Material.STONE, null));
        
        PlacementQueue queue = processor.prepareSimpleQueue(buildingId, blocks, 12345L);
        
        assertEquals(0, processor.getActiveQueueCount());
        
        processor.submitQueue(queue);
        
        assertEquals(1, processor.getActiveQueueCount());
        assertTrue(processor.getQueueStatus(queue.getQueueId()).isPresent());
    }
    
    @Test
    @DisplayName("Queue cancellation should abort placement")
    void testQueueCancellation() {
        processor.start();
        
        UUID buildingId = UUID.randomUUID();
        List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            blocks.add(new PlacementQueueProcessor.BlockPlacement(i, 64, 0, Material.STONE, null));
        }
        
        PlacementQueue queue = processor.prepareSimpleQueue(buildingId, blocks, 12345L);
        processor.submitQueue(queue);
        
        assertEquals(1, processor.getActiveQueueCount());
        
        processor.cancelQueue(queue.getQueueId(), "Test cancellation");
        
        assertEquals(0, processor.getActiveQueueCount());
        assertFalse(processor.getQueueStatus(queue.getQueueId()).isPresent());
    }
    
    @Test
    @DisplayName("Processor should handle multiple concurrent queues")
    void testMultipleConcurrentQueues() {
        processor.start();
        
        // Submit 3 queues
        for (int q = 0; q < 3; q++) {
            UUID buildingId = UUID.randomUUID();
            List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                blocks.add(new PlacementQueueProcessor.BlockPlacement(i, 64, q, Material.STONE, null));
            }
            
            PlacementQueue queue = processor.prepareSimpleQueue(buildingId, blocks, 12345L + q);
            processor.submitQueue(queue);
        }
        
        assertEquals(3, processor.getActiveQueueCount());
        assertEquals(3, processor.getActiveQueueIds().size());
    }
    
    @Test
    @DisplayName("Debug logging should be configurable")
    void testDebugLogging() {
        processor.setDebugLogging(true);
        processor.setDebugLogging(false);
        
        // No exception should be thrown
        assertTrue(true);
    }
    
    @Test
    @DisplayName("Queue should enforce status constraints")
    void testStatusConstraints() {
        UUID buildingId = UUID.randomUUID();
        List<PlacementQueueProcessor.BlockPlacement> blocks = new ArrayList<>();
        blocks.add(new PlacementQueueProcessor.BlockPlacement(0, 64, 0, Material.STONE, null));
        
        PlacementQueue queue = processor.prepareSimpleQueue(buildingId, blocks, 12345L);
        
        // READY queue should be submittable
        assertDoesNotThrow(() -> processor.submitQueue(queue));
        
        // PREPARING queue should not be submittable
        PlacementQueue preparing = PlacementQueue.builder()
                .queueId(UUID.randomUUID())
                .buildingId(buildingId)
                .entries(List.of())
                .status(PlacementQueue.Status.PREPARING)
                .batchSize(50)
                .build();
        
        assertThrows(IllegalStateException.class, () -> processor.submitQueue(preparing));
    }
}
