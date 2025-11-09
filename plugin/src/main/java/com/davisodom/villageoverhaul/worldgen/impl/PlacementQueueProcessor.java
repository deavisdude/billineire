package com.davisodom.villageoverhaul.worldgen.impl;

import com.davisodom.villageoverhaul.model.PlacementQueue;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Processes PlacementQueue instances with async preparation and main-thread batched commits.
 * 
 * This implementation follows Constitution Principle XI:
 * - Large structure preparation happens off-thread (building the queue)
 * - Block mutations are committed in small, main-thread batches
 * - Provides visible row/layer progress
 * - Deterministic ordering (layer-by-layer, row-by-row)
 * 
 * Performance targets:
 * - Batch size: 50 blocks per tick (configurable)
 * - Per-village tick budget: ≤ 2ms amortized
 * - No main-thread blocking beyond tick budget
 */
public class PlacementQueueProcessor {
    
    private static final Logger LOGGER = Logger.getLogger(PlacementQueueProcessor.class.getName());
    
    // Default batch size: 50 blocks per tick
    private static final int DEFAULT_BATCH_SIZE = 50;
    
    // Maximum concurrent async preparations (to prevent memory pressure)
    private static final int MAX_CONCURRENT_PREPARATIONS = 4;
    
    // Plugin instance for scheduling
    private final Plugin plugin;
    
    // Active queues being committed (queueId -> queue)
    private final Map<UUID, PlacementQueue> activeQueues = new ConcurrentHashMap<>();
    
    // Async preparation futures (queueId -> future)
    private final Map<UUID, CompletableFuture<PlacementQueue>> preparationFutures = new ConcurrentHashMap<>();
    
    // Main-thread ticker task
    private BukkitTask tickerTask;
    
    // Configuration
    private int batchSize = DEFAULT_BATCH_SIZE;
    private boolean debugLogging = false;
    
    public PlacementQueueProcessor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }
    
    /**
     * Start the processor's main-thread ticker.
     * Must be called after plugin enable.
     */
    public void start() {
        if (tickerTask != null) {
            LOGGER.warning("[STRUCT] PlacementQueueProcessor already started");
            return;
        }
        
        // Tick every game tick (20 TPS)
        tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        LOGGER.info("[STRUCT] PlacementQueueProcessor started (batch=" + batchSize + " blocks/tick)");
    }
    
    /**
     * Stop the processor and cancel all pending placements.
     */
    public void stop() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        
        // Cancel all async preparations
        for (CompletableFuture<PlacementQueue> future : preparationFutures.values()) {
            future.cancel(true);
        }
        preparationFutures.clear();
        
        // Log aborted queues
        if (!activeQueues.isEmpty()) {
            LOGGER.warning(String.format("[STRUCT] Aborted %d active placement queues on shutdown", 
                    activeQueues.size()));
        }
        activeQueues.clear();
        
        LOGGER.info("[STRUCT] PlacementQueueProcessor stopped");
    }
    
    /**
     * Set batch size (blocks per tick).
     */
    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }
    
    /**
     * Enable/disable debug logging.
     */
    public void setDebugLogging(boolean enabled) {
        this.debugLogging = enabled;
    }
    
    /**
     * Get number of active queues being committed.
     */
    public int getActiveQueueCount() {
        return activeQueues.size();
    }
    
    /**
     * Get number of queues being prepared asynchronously.
     */
    public int getPreparationCount() {
        return preparationFutures.size();
    }
    
    /**
     * Prepare a placement queue from a WorldEdit clipboard asynchronously.
     * 
     * @param buildingId Building UUID
     * @param clipboard WorldEdit clipboard containing structure blocks
     * @param world Target world
     * @param origin Target origin location
     * @param seed Deterministic seed for ordering
     * @return CompletableFuture that completes with the prepared queue
     */
    public CompletableFuture<PlacementQueue> prepareQueueFromClipboard(
            UUID buildingId,
            Clipboard clipboard,
            World world,
            Location origin,
            long seed) {
        
        // Check concurrent preparation limit
        if (preparationFutures.size() >= MAX_CONCURRENT_PREPARATIONS) {
            CompletableFuture<PlacementQueue> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException(
                    "Too many concurrent preparations (max=" + MAX_CONCURRENT_PREPARATIONS + ")"));
            return future;
        }
        
        UUID queueId = UUID.randomUUID();
        
        LOGGER.info(String.format("[STRUCT] Async prepare queue: building=%s, blocks=%d, seed=%d",
                buildingId, clipboard.getDimensions().getX() * clipboard.getDimensions().getY() * clipboard.getDimensions().getZ(),
                seed));
        
        // Prepare queue off-thread
        CompletableFuture<PlacementQueue> future = CompletableFuture.supplyAsync(() -> {
            return buildQueueFromClipboard(queueId, buildingId, clipboard, world, origin, seed);
        });
        
        preparationFutures.put(queueId, future);
        
        // Clean up preparation future when complete
        future.whenComplete((queue, error) -> {
            preparationFutures.remove(queueId);
            
            if (error != null) {
                LOGGER.warning(String.format("[STRUCT] Queue preparation failed: building=%s, error=%s",
                        buildingId, error.getMessage()));
            } else if (queue != null) {
                LOGGER.info(String.format("[STRUCT] Queue prepared: building=%s, blocks=%d, layers=%d",
                        buildingId, queue.getTotalBlocks(), getLayerCount(queue)));
            }
        });
        
        return future;
    }
    
    /**
     * Build queue from clipboard (runs off-thread).
     * Creates deterministically ordered entries (layer-by-layer, row-by-row).
     */
    private PlacementQueue buildQueueFromClipboard(
            UUID queueId,
            UUID buildingId,
            Clipboard clipboard,
            World world,
            Location origin,
            long seed) {
        
        List<PlacementQueue.Entry> entries = new ArrayList<>();
        
        BlockVector3 min = clipboard.getMinimumPoint();
        BlockVector3 max = clipboard.getMaximumPoint();
        
        int orderIndex = 0;
        
        // Iterate layer-by-layer (Y), row-by-row (Z), then X
        for (int y = min.getY(); y <= max.getY(); y++) {
            int layer = y - min.getY();
            
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                int row = z - min.getZ();
                
                for (int x = min.getX(); x <= max.getX(); x++) {
                    BlockVector3 pos = BlockVector3.at(x, y, z);
                    
                    // Get block from clipboard
                    com.sk89q.worldedit.world.block.BaseBlock block = clipboard.getFullBlock(pos);
                    
                    // Skip air blocks (no need to place)
                    if (block.getBlockType().getMaterial().isAir()) {
                        continue;
                    }
                    
                    // Convert WorldEdit block to Bukkit Material and BlockData
                    Material material = BukkitAdapter.adapt(block.getBlockType());
                    BlockData blockData = BukkitAdapter.adapt(block);
                    
                    // Calculate world position
                    int worldX = origin.getBlockX() + (x - min.getX());
                    int worldY = origin.getBlockY() + (y - min.getY());
                    int worldZ = origin.getBlockZ() + (z - min.getZ());
                    
                    // Create entry
                    PlacementQueue.Entry entry = new PlacementQueue.Entry(
                            worldX, worldY, worldZ,
                            material, blockData,
                            layer, row, orderIndex++
                    );
                    
                    entries.add(entry);
                }
            }
        }
        
        // Build queue with READY status (preparation complete)
        return PlacementQueue.builder()
                .queueId(queueId)
                .buildingId(buildingId)
                .entries(entries)
                .status(PlacementQueue.Status.READY)
                .batchSize(batchSize)
                .currentIndex(0)
                .createdAt(System.currentTimeMillis())
                .lastCommitAt(System.currentTimeMillis())
                .build();
    }
    
    /**
     * Prepare a simple queue from a list of blocks (for procedurally generated structures).
     * This is synchronous and suitable for small structures.
     * 
     * @param buildingId Building UUID
     * @param blocks List of block locations and types
     * @param seed Deterministic seed for ordering
     * @return Prepared PlacementQueue
     */
    public PlacementQueue prepareSimpleQueue(
            UUID buildingId,
            List<BlockPlacement> blocks,
            long seed) {
        
        UUID queueId = UUID.randomUUID();
        
        // Sort blocks by Y (layer), then Z (row), then X for deterministic ordering
        blocks.sort(Comparator.comparingInt(BlockPlacement::y)
                .thenComparingInt(BlockPlacement::z)
                .thenComparingInt(BlockPlacement::x));
        
        List<PlacementQueue.Entry> entries = new ArrayList<>();
        int orderIndex = 0;
        int currentLayer = -1;
        int currentRow = -1;
        int layer = 0;
        int row = 0;
        
        for (BlockPlacement blockPlacement : blocks) {
            // Track layer and row changes
            if (blockPlacement.y() != currentLayer) {
                currentLayer = blockPlacement.y();
                layer++;
                row = 0;
            }
            if (blockPlacement.z() != currentRow) {
                currentRow = blockPlacement.z();
                row++;
            }
            
            PlacementQueue.Entry entry = new PlacementQueue.Entry(
                    blockPlacement.x(), blockPlacement.y(), blockPlacement.z(),
                    blockPlacement.material(), blockPlacement.blockData(),
                    layer, row, orderIndex++
            );
            
            entries.add(entry);
        }
        
        return PlacementQueue.builder()
                .queueId(queueId)
                .buildingId(buildingId)
                .entries(entries)
                .status(PlacementQueue.Status.READY)
                .batchSize(batchSize)
                .currentIndex(0)
                .createdAt(System.currentTimeMillis())
                .lastCommitAt(System.currentTimeMillis())
                .build();
    }
    
    /**
     * Submit a prepared queue for main-thread commits.
     * The queue will start being processed on the next tick.
     */
    public void submitQueue(PlacementQueue queue) {
        if (!queue.isReadyForCommit()) {
            throw new IllegalStateException("Queue must be READY or COMMITTING to submit");
        }
        
        activeQueues.put(queue.getQueueId(), queue);
        
        LOGGER.info(String.format("[STRUCT] Queue submitted: id=%s, building=%s, blocks=%d, batch=%d",
                queue.getQueueId(), queue.getBuildingId(), queue.getTotalBlocks(), queue.getBatchSize()));
    }
    
    /**
     * Cancel a queue (abort placement).
     */
    public void cancelQueue(UUID queueId, String reason) {
        PlacementQueue queue = activeQueues.remove(queueId);
        
        if (queue != null) {
            PlacementQueue aborted = queue.asAborted(reason, System.currentTimeMillis());
            LOGGER.warning(String.format("[STRUCT] Queue aborted: id=%s, building=%s, reason=%s, placed=%d/%d",
                    queueId, aborted.getBuildingId(), reason, 
                    aborted.getBlocksPlaced(), aborted.getTotalBlocks()));
        }
        
        // Also cancel any ongoing async preparation
        CompletableFuture<PlacementQueue> future = preparationFutures.remove(queueId);
        if (future != null) {
            future.cancel(true);
        }
    }
    
    /**
     * Main tick method - processes one batch per active queue.
     * Runs on main thread.
     */
    private void tick() {
        if (activeQueues.isEmpty()) {
            return;
        }
        
        long tickStart = System.nanoTime();
        Iterator<Map.Entry<UUID, PlacementQueue>> iterator = activeQueues.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlacementQueue> entry = iterator.next();
            PlacementQueue queue = entry.getValue();
            
            try {
                // Process one batch
                PlacementQueue updated = processBatch(queue);
                
                if (updated.isFinished()) {
                    // Queue complete - remove from active set
                    iterator.remove();
                    
                    if (updated.getStatus() == PlacementQueue.Status.COMPLETE) {
                        LOGGER.info(String.format("[STRUCT] Queue complete: id=%s, building=%s, blocks=%d, time=%dms",
                                updated.getQueueId(), updated.getBuildingId(), updated.getTotalBlocks(),
                                updated.getLastCommitAt() - updated.getCreatedAt()));
                    }
                } else {
                    // Update queue in map
                    entry.setValue(updated);
                }
                
            } catch (Exception e) {
                LOGGER.warning(String.format("[STRUCT] Queue processing error: id=%s, error=%s",
                        queue.getQueueId(), e.getMessage()));
                
                // Abort queue on error
                queue.asAborted("Processing error: " + e.getMessage(), System.currentTimeMillis());
                iterator.remove();
            }
        }
        
        long tickEnd = System.nanoTime();
        double tickMs = (tickEnd - tickStart) / 1_000_000.0;
        
        if (debugLogging && !activeQueues.isEmpty()) {
            LOGGER.fine(String.format("[STRUCT] Tick complete: queues=%d, time=%.2fms",
                    activeQueues.size(), tickMs));
        }
    }
    
    /**
     * Process one batch from a queue (main thread).
     * Places blocks and advances queue index.
     */
    private PlacementQueue processBatch(PlacementQueue queue) {
        List<PlacementQueue.Entry> batch = queue.getNextBatch();
        
        if (batch.isEmpty()) {
            // No more blocks - mark complete
            return queue.withStatus(PlacementQueue.Status.COMPLETE, System.currentTimeMillis());
        }
        
        // Place blocks in batch
        int placed = 0;
        for (PlacementQueue.Entry entry : batch) {
            try {
                // Get world (assume first entry determines world)
                World world = Bukkit.getWorlds().get(0); // TODO: Store world reference in queue
                Block block = world.getBlockAt(entry.getX(), entry.getY(), entry.getZ());
                
                // Set block type and data
                if (entry.getBlockData() != null) {
                    block.setBlockData(entry.getBlockData(), false); // false = no physics update
                } else {
                    block.setType(entry.getMaterial(), false);
                }
                
                placed++;
                
            } catch (Exception e) {
                LOGGER.warning(String.format("[STRUCT] Block placement error: pos=(%d,%d,%d), mat=%s, error=%s",
                        entry.getX(), entry.getY(), entry.getZ(), entry.getMaterial(), e.getMessage()));
            }
        }
        
        // Advance queue index
        int newIndex = queue.getCurrentIndex() + placed;
        PlacementQueue updated = queue.withAdvancedIndex(newIndex, System.currentTimeMillis());
        
        // Log progress periodically
        if (debugLogging || (updated.getBlocksPlaced() % 500 == 0)) {
            PlacementQueue.ConstructionProgress progress = updated.getProgress();
            LOGGER.fine(String.format("[STRUCT] Queue progress: id=%s, placed=%d/%d (%.1f%%), layer=%d, row=%d",
                    updated.getQueueId(), updated.getBlocksPlaced(), updated.getTotalBlocks(),
                    progress.percentComplete() * 100, progress.currentLayer(), progress.currentRow()));
        }
        
        return updated;
    }
    
    /**
     * Get status of a queue.
     */
    public Optional<PlacementQueue> getQueueStatus(UUID queueId) {
        return Optional.ofNullable(activeQueues.get(queueId));
    }
    
    /**
     * Get all active queue IDs.
     */
    public Set<UUID> getActiveQueueIds() {
        return new HashSet<>(activeQueues.keySet());
    }
    
    /**
     * Get layer count from a queue.
     */
    private int getLayerCount(PlacementQueue queue) {
        return (int) queue.getEntries().stream()
                .mapToInt(PlacementQueue.Entry::getLayer)
                .distinct()
                .count();
    }
    
    /**
     * Simple record for block placement data.
     */
    public record BlockPlacement(int x, int y, int z, Material material, BlockData blockData) {}
}
