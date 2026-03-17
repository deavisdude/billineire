package com.davisodom.villageoverhaul.worldgen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Async terrain search utility for non-blocking village site discovery.
 * 
 * T052a: Prevents main thread blocking during terrain evaluation by:
 * - Using Paper's async chunk loading API where available
 * - Batching chunk loads with time budgets
 * - Yielding to main thread between batches
 * - Providing progress callbacks for diagnostics
 * 
 * Constitution compliance: Principle XII (Observability) - emits timing diagnostics
 */
public class AsyncTerrainSearch {
    
    private static final Logger LOGGER = Logger.getLogger(AsyncTerrainSearch.class.getName());
    
    // Configuration defaults
    private static final int DEFAULT_BATCH_SIZE = 4; // Chunks per batch
    private static final long DEFAULT_TIME_BUDGET_MS = 50; // Max ms per tick for terrain work
    private static final int DEFAULT_YIELD_TICKS = 1; // Ticks to yield between batches
    
    private final Plugin plugin;
    private final int batchSize;
    private final long timeBudgetMs;
    private final int yieldTicks;
    
    // Diagnostics
    private long totalBlockingTimeMs = 0;
    private int blockedBatches = 0;
    
    public AsyncTerrainSearch(Plugin plugin) {
        this(plugin, DEFAULT_BATCH_SIZE, DEFAULT_TIME_BUDGET_MS, DEFAULT_YIELD_TICKS);
    }
    
    public AsyncTerrainSearch(Plugin plugin, int batchSize, long timeBudgetMs, int yieldTicks) {
        this.plugin = plugin;
        this.batchSize = batchSize;
        this.timeBudgetMs = timeBudgetMs;
        this.yieldTicks = yieldTicks;
    }
    
    /**
     * Search for suitable village terrain asynchronously with time-budgeted processing.
     * Returns a CompletableFuture that completes with the best location found, or null.
     * 
     * @param world Target world
     * @param center Search center (typically spawn)
     * @param maxRadius Maximum search radius in blocks
     * @param progressCallback Optional callback for progress updates (location count checked)
     * @return CompletableFuture with suitable location or null
     */
    public CompletableFuture<Location> searchAsync(
            World world, 
            Location center, 
            int maxRadius,
            Consumer<SearchProgress> progressCallback) {
        
        CompletableFuture<Location> future = new CompletableFuture<>();
        
        // Build candidate list in spiral order
        List<CandidateLocation> candidates = buildCandidateList(center, maxRadius);
        
        // Start async evaluation
        SearchState state = new SearchState(world, candidates, future, progressCallback);
        
        // Schedule first batch
        scheduleNextBatch(state);
        
        return future;
    }
    
    /**
     * Synchronous terrain search with time-budgeted batching and yield points.
     * Must be called from main thread. Yields back to scheduler between batches.
     * 
     * @param world Target world
     * @param center Search center
     * @param maxRadius Maximum radius
     * @param callback Called when search completes (on main thread)
     */
    public void searchWithYielding(
            World world,
            Location center,
            int maxRadius,
            Consumer<Location> callback) {
        
        List<CandidateLocation> candidates = buildCandidateList(center, maxRadius);
        SearchStateYielding state = new SearchStateYielding(world, candidates, callback);
        
        processNextBatchYielding(state);
    }
    
    /**
     * Build spiral-ordered candidate locations.
     */
    private List<CandidateLocation> buildCandidateList(Location center, int maxRadius) {
        List<CandidateLocation> candidates = new ArrayList<>();
        int startX = center.getBlockX();
        int startZ = center.getBlockZ();
        int sampleInterval = 24;
        
        // Spiral search pattern
        for (int radius = 16; radius <= maxRadius; radius += sampleInterval) {
            // 8 points around circle at this radius
            for (int i = 0; i < 8; i++) {
                double angle = (i / 8.0) * 2 * Math.PI;
                int x = startX + (int)(radius * Math.cos(angle));
                int z = startZ + (int)(radius * Math.sin(angle));
                candidates.add(new CandidateLocation(x, z, radius));
            }
        }
        
        return candidates;
    }
    
    /**
     * Schedule next batch of terrain evaluation (async path).
     */
    private void scheduleNextBatch(SearchState state) {
        if (state.future.isDone()) {
            return;
        }
        
        // Run evaluation off main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                processBatchAsync(state);
            } catch (Exception e) {
                LOGGER.severe("Error in async terrain search: " + e.getMessage());
                state.future.completeExceptionally(e);
            }
        });
    }
    
    /**
     * Process a batch of candidates asynchronously.
     */
    private void processBatchAsync(SearchState state) {
        long batchStart = System.currentTimeMillis();
        int processed = 0;
        
        while (state.currentIndex < state.candidates.size() && processed < batchSize) {
            CandidateLocation candidate = state.candidates.get(state.currentIndex);
            state.currentIndex++;
            processed++;
            
            // Evaluate terrain (this may block on chunk load)
            boolean suitable = evaluateTerrainSafe(state.world, candidate, state.surfaceSolver);
            
            if (suitable) {
                int y = state.surfaceSolver.getSurfaceHeight(candidate.x, candidate.z);
                Location result = new Location(state.world, candidate.x, y, candidate.z);
                
                // Complete on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    emitSearchDiagnostic(state, result, System.currentTimeMillis() - state.startTime);
                    state.future.complete(result);
                });
                return;
            }
            
            // Check time budget
            long elapsed = System.currentTimeMillis() - batchStart;
            if (elapsed > timeBudgetMs) {
                recordBudgetExceeded(elapsed);
                break;
            }
        }
        
        // Report progress
        if (state.progressCallback != null) {
            final int checked = state.currentIndex;
            final int total = state.candidates.size();
            Bukkit.getScheduler().runTask(plugin, () -> {
                state.progressCallback.accept(new SearchProgress(checked, total, false, null));
            });
        }
        
        // Check if done
        if (state.currentIndex >= state.candidates.size()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                emitSearchDiagnostic(state, null, System.currentTimeMillis() - state.startTime);
                state.future.complete(null);
            });
            return;
        }
        
        // Schedule next batch after yield
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduleNextBatch(state);
        }, yieldTicks);
    }
    
    /**
     * Process next batch with main-thread yielding.
     */
    private void processNextBatchYielding(SearchStateYielding state) {
        long batchStart = System.currentTimeMillis();
        int processed = 0;
        
        while (state.currentIndex < state.candidates.size()) {
            CandidateLocation candidate = state.candidates.get(state.currentIndex);
            state.currentIndex++;
            processed++;
            
            // Check if chunk is already loaded (fast path)
            int chunkX = candidate.x >> 4;
            int chunkZ = candidate.z >> 4;
            
            if (!state.world.isChunkLoaded(chunkX, chunkZ)) {
                // Chunk not loaded - skip or queue for async load
                state.skippedUnloaded++;
                
                // If too many skipped, force a batch load
                if (state.skippedUnloaded >= batchSize * 2) {
                    loadChunkBatchSafe(state.world, state.pendingChunks);
                    state.pendingChunks.clear();
                    state.skippedUnloaded = 0;
                }
                continue;
            }
            
            // Evaluate terrain (chunk is loaded, should be fast)
            boolean suitable = evaluateTerrainFast(state.world, candidate, state.surfaceSolver);
            
            if (suitable) {
                int y = state.surfaceSolver.getSurfaceHeight(candidate.x, candidate.z);
                Location result = new Location(state.world, candidate.x, y, candidate.z);
                LOGGER.info(String.format("[TERRAIN] Found suitable location at (%d, %d, %d) after checking %d candidates",
                        candidate.x, y, candidate.z, state.currentIndex));
                state.callback.accept(result);
                return;
            }
            
            // Check time budget
            long elapsed = System.currentTimeMillis() - batchStart;
            if (elapsed > timeBudgetMs) {
                // Yield to prevent blocking
                LOGGER.fine(String.format("[TERRAIN] Yielding after %dms, checked %d/%d candidates",
                        elapsed, state.currentIndex, state.candidates.size()));
                
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    processNextBatchYielding(state);
                }, yieldTicks);
                return;
            }
        }
        
        // Search complete, no suitable location found
        LOGGER.warning(String.format("[TERRAIN] No suitable location found after checking %d candidates",
                state.candidates.size()));
        state.callback.accept(null);
    }
    
    /**
     * Evaluate terrain at candidate location (safe version with chunk handling).
     */
    private boolean evaluateTerrainSafe(World world, CandidateLocation candidate, SurfaceSolver surfaceSolver) {
        try {
            // Ensure chunks are loaded for evaluation
            int checkRadius = 24;
            int chunkX = candidate.x >> 4;
            int chunkZ = candidate.z >> 4;
            
            // Load 3x3 chunk area (blocking in async context is OK)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!world.isChunkGenerated(chunkX + dx, chunkZ + dz)) {
                        // Use Paper async chunk loading if available
                        try {
                            world.getChunkAtAsync(chunkX + dx, chunkZ + dz).join();
                        } catch (Exception e) {
                            // Fall back to sync load (not ideal but safe in async context)
                            world.getChunkAt(chunkX + dx, chunkZ + dz);
                        }
                    }
                }
            }
            
            return evaluateTerrainFast(world, candidate, surfaceSolver);
        } catch (Exception e) {
            LOGGER.fine("Error evaluating terrain at (" + candidate.x + ", " + candidate.z + "): " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Fast terrain evaluation (assumes chunks are loaded).
     */
    private boolean evaluateTerrainFast(World world, CandidateLocation candidate, SurfaceSolver surfaceSolver) {
        int checkRadius = 24;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int waterBlocks = 0;
        int totalChecks = 0;
        
        // Sample terrain in grid pattern
        for (int x = -checkRadius; x <= checkRadius; x += 12) {
            for (int z = -checkRadius; z <= checkRadius; z += 12) {
                int checkX = candidate.x + x;
                int checkZ = candidate.z + z;
                
                int y = surfaceSolver.getSurfaceHeight(checkX, checkZ);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                totalChecks++;
                
                if (isWaterOrFrozenWaterSurface(world, checkX, y, checkZ)) {
                    waterBlocks++;
                }
            }
        }
        
        int yVariation = maxY - minY;
        double waterPercent = (double) waterBlocks / totalChecks;
        
        // Criteria matching VillageWorldgenAdapter.isTerrainSuitable()
        boolean flatEnough = yVariation <= 8;
        boolean notTooWatery = waterPercent < 0.3;
        boolean goodHeight = minY >= 50 && maxY <= 120;
        
        if (flatEnough && notTooWatery && goodHeight) {
            if (hasWaterInProximity(world, candidate.x, candidate.z, 25, surfaceSolver)) {
                return false;
            }
        }
        
        return flatEnough && notTooWatery && goodHeight;
    }
    
    /**
     * Safe highest block query.
     */
    private boolean hasWaterInProximity(World world, int centerX, int centerZ, int radius,
                                        SurfaceSolver surfaceSolver) {
        // Check in a cross pattern first (fast rejection)
        for (int d = -radius; d <= radius; d += 4) {
            int y1 = surfaceSolver.getSurfaceHeight(centerX + d, centerZ);
            if (isWaterOrFrozenWaterSurface(world, centerX + d, y1, centerZ)) {
                return true;
            }
            int y2 = surfaceSolver.getSurfaceHeight(centerX, centerZ + d);
            if (isWaterOrFrozenWaterSurface(world, centerX, y2, centerZ + d)) {
                return true;
            }
        }
        
        // Check diagonals
        for (int d = -radius; d <= radius; d += 6) {
            int y1 = surfaceSolver.getSurfaceHeight(centerX + d, centerZ + d);
            if (isWaterOrFrozenWaterSurface(world, centerX + d, y1, centerZ + d)) {
                return true;
            }
            int y2 = surfaceSolver.getSurfaceHeight(centerX + d, centerZ - d);
            if (isWaterOrFrozenWaterSurface(world, centerX + d, y2, centerZ - d)) {
                return true;
            }
        }
        
        return false;
    }

    private boolean isWaterOrFrozenWaterSurface(World world, int x, int groundY, int z) {
        Material surface = world.getBlockAt(x, groundY + 1, z).getType();
        return isWaterOrFrozenWater(surface);
    }
    
    private boolean isWaterOrFrozenWater(Material type) {
        return type == Material.WATER ||
               type == Material.ICE ||
               type == Material.PACKED_ICE ||
               type == Material.BLUE_ICE ||
               type == Material.FROSTED_ICE;
    }
    
    /**
     * Load a batch of chunks safely.
     */
    private void loadChunkBatchSafe(World world, List<int[]> chunks) {
        for (int[] chunk : chunks) {
            try {
                world.getChunkAtAsync(chunk[0], chunk[1]);
            } catch (Exception e) {
                // Ignore - async load best effort
            }
        }
    }
    
    /**
     * Record budget exceeded event.
     */
    private void recordBudgetExceeded(long actualMs) {
        blockedBatches++;
        totalBlockingTimeMs += (actualMs - timeBudgetMs);
        
        if (blockedBatches % 10 == 0) {
            LOGGER.warning(String.format("[TERRAIN][DIAG] Budget exceeded %d times, total excess: %dms",
                    blockedBatches, totalBlockingTimeMs));
        }
    }
    
    /**
     * Emit search diagnostic.
     */
    private void emitSearchDiagnostic(SearchState state, Location result, long totalMs) {
        String status = result != null ? "FOUND" : "NOT_FOUND";
        LOGGER.info(String.format("[TERRAIN][DIAG] Search %s: checked=%d/%d time=%dms blockedBatches=%d",
                status, state.currentIndex, state.candidates.size(), totalMs, blockedBatches));
    }
    
    /**
     * Get diagnostic metrics.
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diag = new HashMap<>();
        diag.put("blockedBatches", blockedBatches);
        diag.put("totalBlockingTimeMs", totalBlockingTimeMs);
        diag.put("batchSize", batchSize);
        diag.put("timeBudgetMs", timeBudgetMs);
        return diag;
    }
    
    // ==================== Inner Classes ====================
    
    /**
     * Candidate location for terrain evaluation.
     */
    private static class CandidateLocation {
        final int x;
        final int z;
        final int radius;
        
        CandidateLocation(int x, int z, int radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }
    }
    
    /**
     * Search state for async processing.
     */
    private static class SearchState {
        final World world;
        final SurfaceSolver surfaceSolver;
        final List<CandidateLocation> candidates;
        final CompletableFuture<Location> future;
        final Consumer<SearchProgress> progressCallback;
        final long startTime;
        int currentIndex = 0;
        
        SearchState(World world, List<CandidateLocation> candidates, 
                    CompletableFuture<Location> future, Consumer<SearchProgress> progressCallback) {
            this.world = world;
            this.candidates = candidates;
            this.future = future;
            this.progressCallback = progressCallback;
            this.startTime = System.currentTimeMillis();
            this.surfaceSolver = new SurfaceSolver(world, java.util.Collections.emptyList());
        }
    }
    
    /**
     * Search state for yielding main-thread processing.
     */
    private static class SearchStateYielding {
        final World world;
        final SurfaceSolver surfaceSolver;
        final List<CandidateLocation> candidates;
        final Consumer<Location> callback;
        final List<int[]> pendingChunks = new ArrayList<>();
        int currentIndex = 0;
        int skippedUnloaded = 0;
        
        SearchStateYielding(World world, List<CandidateLocation> candidates, Consumer<Location> callback) {
            this.world = world;
            this.candidates = candidates;
            this.callback = callback;
            this.surfaceSolver = new SurfaceSolver(world, java.util.Collections.emptyList());
        }
    }
    
    /**
     * Progress callback data.
     */
    public static class SearchProgress {
        public final int checked;
        public final int total;
        public final boolean complete;
        public final Location result;
        
        public SearchProgress(int checked, int total, boolean complete, Location result) {
            this.checked = checked;
            this.total = total;
            this.complete = complete;
            this.result = result;
        }
    }
}
