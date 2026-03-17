package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Non-blocking placement candidate search with time-budgeted chunk loading.
 * 
 * T052a: Provides async structure placement position search that:
 * - Uses Paper's async chunk API for non-blocking chunk loads
 * - Batches work with configurable time budgets
 * - Yields to main thread between batches
 * - Emits diagnostics when thresholds are exceeded
 * 
 * This replaces the blocking spiral search in VillagePlacementServiceImpl.findSuitablePlacementPosition()
 */
public class AsyncPlacementSearch {
    
    private static final Logger LOGGER = Logger.getLogger(AsyncPlacementSearch.class.getName());
    
    // Configuration
    private final Plugin plugin;
    private final int batchSize;
    private final long timeBudgetMs;
    private final int yieldTicks;
    
    // Diagnostics
    private long totalSearchTimeMs = 0;
    private int searchesPerformed = 0;
    private int budgetExceededCount = 0;
    
    public AsyncPlacementSearch(Plugin plugin, int batchSize, long timeBudgetMs, int yieldTicks) {
        this.plugin = plugin;
        this.batchSize = batchSize;
        this.timeBudgetMs = timeBudgetMs;
        this.yieldTicks = yieldTicks;
    }
    
    /**
     * Default configuration constructor.
     */
    public AsyncPlacementSearch(Plugin plugin) {
        this(plugin, 8, 40, 1); // 8 candidates per batch, 40ms budget, 1 tick yield
    }
    
    /**
     * Search for suitable placement position with time-budgeted processing.
     * 
     * @param world Target world
     * @param origin Village center
     * @param width Structure width (X)
     * @param depth Structure depth (Z)
     * @param height Structure height (Y)
     * @param buildingSeed Seed for deterministic rotation
     * @param existingMasks Volume masks to avoid
     * @param surfaceSolver Surface solver for ground finding
     * @param minSpacing Minimum spacing between structures
     * @param callback Called with result location (or null if not found)
     */
    public void searchAsync(
            World world,
            Location origin,
            int width, int depth, int height,
            long buildingSeed,
            List<VolumeMask> existingMasks,
            SurfaceSolver surfaceSolver,
            int minSpacing,
            Consumer<PlacementSearchResult> callback) {
        
        long startTime = System.currentTimeMillis();
        searchesPerformed++;
        
        // Build candidate list (same spiral pattern as sync version)
        List<CandidateSite> candidates = buildCandidateList(origin, 256, 4);
        
        // Sort deterministically
        candidates.sort((a, b) -> {
            int distCompare = Integer.compare(a.distanceSquared, b.distanceSquared);
            if (distCompare != 0) return distCompare;
            int xCompare = Integer.compare(a.x, b.x);
            if (xCompare != 0) return xCompare;
            return Integer.compare(a.z, b.z);
        });
        
        // Determine rotation deterministically
        Random rotRandom = new Random(buildingSeed);
        int rotation = rotRandom.nextInt(4) * 90;
        
        // Create search state
        PlacementSearchState state = new PlacementSearchState(
                world, origin, candidates,
                width, depth, height, rotation,
                existingMasks, surfaceSolver, minSpacing,
                callback, startTime
        );
        
        // Start processing
        processNextBatch(state);
    }
    
    /**
     * Process next batch of candidates with time budget checking.
     */
    private void processNextBatch(PlacementSearchState state) {
        long batchStart = System.currentTimeMillis();
        int processed = 0;
        int chunkLoadsNeeded = 0;
        List<CompletableFuture<Void>> pendingChunkLoads = new ArrayList<>();
        
        while (state.currentIndex < state.candidates.size() && processed < batchSize) {
            CandidateSite candidate = state.candidates.get(state.currentIndex);
            state.currentIndex++;
            processed++;
            state.totalAttempts++;
            
            // Check chunk availability
            int chunkX = candidate.x >> 4;
            int chunkZ = candidate.z >> 4;
            
            if (!state.world.isChunkLoaded(chunkX, chunkZ)) {
                // Queue async chunk load instead of blocking
                chunkLoadsNeeded++;
                
                // Skip this candidate for now if too many pending loads
                if (chunkLoadsNeeded > 2) {
                    state.chunkNotReadyCount++;
                    continue;
                }
                
                // Try async load
                try {
                    CompletableFuture<Void> loadFuture = state.world.getChunkAtAsync(chunkX, chunkZ)
                            .thenAccept(chunk -> {
                                // Chunk loaded
                            });
                    pendingChunkLoads.add(loadFuture);
                } catch (Exception e) {
                    state.chunkNotReadyCount++;
                    continue;
                }
            }
            
            // Get surface height (fast if chunk loaded)
            int candidateY;
            try {
                candidateY = state.surfaceSolver.getSurfaceHeight(candidate.x, candidate.z);
            } catch (Exception e) {
                state.chunkNotReadyCount++;
                continue;
            }
            
            // Compute AABB for collision check
            int[] candidateAABB = computeRotatedAABB(
                    candidate.x, candidateY, candidate.z,
                    state.width, state.depth, state.height, state.rotation);
            
            // Check collision with existing masks
            boolean overlaps = checkAABBCollision(candidateAABB, state.existingMasks, state.minSpacing);
            
            // Progressive spacing relaxation
            if (overlaps && state.minSpacing > 0) {
                int half = Math.max(0, state.minSpacing / 2);
                if (half != state.minSpacing) {
                    overlaps = checkAABBCollision(candidateAABB, state.existingMasks, half);
                }
            }
            
            if (overlaps && state.minSpacing > 1) {
                overlaps = checkAABBCollision(candidateAABB, state.existingMasks, 0);
            }
            
            if (overlaps) {
                state.overlapRejections++;
                continue;
            }
            
            // Found valid spot!
            Location result = new Location(state.world, candidate.x, candidateY, candidate.z);
            long totalTime = System.currentTimeMillis() - state.startTime;
            totalSearchTimeMs += totalTime;
            
            LOGGER.info(String.format("[PLACEMENT] Found position at (%d, %d, %d) after %d attempts in %dms",
                    candidate.x, candidateY, candidate.z, state.totalAttempts, totalTime));
            
            state.callback.accept(new PlacementSearchResult(
                    result, state.totalAttempts, state.overlapRejections, 
                    state.chunkNotReadyCount, totalTime, true));
            return;
        }
        
        // Check time budget
        long elapsed = System.currentTimeMillis() - batchStart;
        if (elapsed > timeBudgetMs) {
            budgetExceededCount++;
            LOGGER.warning(String.format("[PLACEMENT][DIAG] Batch exceeded time budget: %dms > %dms (total exceeded: %d)",
                    elapsed, timeBudgetMs, budgetExceededCount));
        }
        
        // Check if done
        if (state.currentIndex >= state.candidates.size()) {
            // No valid position found
            long totalTime = System.currentTimeMillis() - state.startTime;
            totalSearchTimeMs += totalTime;
            
            LOGGER.warning(String.format("[PLACEMENT] No valid position found after %d attempts in %dms (overlap=%d, chunkNotReady=%d)",
                    state.totalAttempts, totalTime, state.overlapRejections, state.chunkNotReadyCount));
            
            state.callback.accept(new PlacementSearchResult(
                    null, state.totalAttempts, state.overlapRejections,
                    state.chunkNotReadyCount, totalTime, false));
            return;
        }
        
        // Wait for pending chunk loads then continue
        if (!pendingChunkLoads.isEmpty()) {
            CompletableFuture.allOf(pendingChunkLoads.toArray(new CompletableFuture[0]))
                    .whenComplete((v, ex) -> {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> processNextBatch(state), yieldTicks);
                    });
        } else {
            // Schedule next batch after yield
            Bukkit.getScheduler().runTaskLater(plugin, () -> processNextBatch(state), yieldTicks);
        }
    }
    
    /**
     * Build spiral candidate list.
     */
    private List<CandidateSite> buildCandidateList(Location origin, int maxRadius, int gridSize) {
        List<CandidateSite> candidates = new ArrayList<>();
        
        for (int radius = 0; radius <= maxRadius; radius += gridSize) {
            for (int dx = -radius; dx <= radius; dx += gridSize) {
                for (int dz = -radius; dz <= radius; dz += gridSize) {
                    if (radius > 0 && Math.abs(dx) < radius && Math.abs(dz) < radius) {
                        continue;
                    }
                    
                    int candidateX = origin.getBlockX() + dx;
                    int candidateZ = origin.getBlockZ() + dz;
                    int distanceSquared = dx * dx + dz * dz;
                    
                    candidates.add(new CandidateSite(candidateX, candidateZ, distanceSquared, dx, dz));
                }
            }
        }
        
        return candidates;
    }
    
    /**
     * Compute rotated AABB bounds.
     */
    private int[] computeRotatedAABB(int originX, int originY, int originZ,
                                      int baseWidth, int baseDepth, int height, int rotation) {
        int[][] corners = new int[8][3];
        int idx = 0;
        for (int x : new int[]{0, baseWidth}) {
            for (int y : new int[]{0, height}) {
                for (int z : new int[]{0, baseDepth}) {
                    corners[idx][0] = x;
                    corners[idx][1] = y;
                    corners[idx][2] = z;
                    idx++;
                }
            }
        }
        
        int[][] rotatedCorners = new int[8][3];
        for (int i = 0; i < 8; i++) {
            int x = corners[i][0];
            int y = corners[i][1];
            int z = corners[i][2];
            
            switch (rotation) {
                case 0:
                    rotatedCorners[i][0] = x;
                    rotatedCorners[i][2] = z;
                    break;
                case 90:
                    rotatedCorners[i][0] = -z;
                    rotatedCorners[i][2] = x;
                    break;
                case 180:
                    rotatedCorners[i][0] = -x;
                    rotatedCorners[i][2] = -z;
                    break;
                case 270:
                    rotatedCorners[i][0] = z;
                    rotatedCorners[i][2] = -x;
                    break;
            }
            rotatedCorners[i][1] = y;
        }
        
        int minRotX = Integer.MAX_VALUE, maxRotX = Integer.MIN_VALUE;
        int minRotY = Integer.MAX_VALUE, maxRotY = Integer.MIN_VALUE;
        int minRotZ = Integer.MAX_VALUE, maxRotZ = Integer.MIN_VALUE;
        
        for (int i = 0; i < 8; i++) {
            minRotX = Math.min(minRotX, rotatedCorners[i][0]);
            maxRotX = Math.max(maxRotX, rotatedCorners[i][0]);
            minRotY = Math.min(minRotY, rotatedCorners[i][1]);
            maxRotY = Math.max(maxRotY, rotatedCorners[i][1]);
            minRotZ = Math.min(minRotZ, rotatedCorners[i][2]);
            maxRotZ = Math.max(maxRotZ, rotatedCorners[i][2]);
        }
        
        return new int[]{
                originX + minRotX, originX + maxRotX - 1,
                originY + minRotY, originY + maxRotY - 1,
                originZ + minRotZ, originZ + maxRotZ - 1
        };
    }
    
    /**
     * Check AABB collision with existing masks.
     */
    private boolean checkAABBCollision(int[] candidateAABB, List<VolumeMask> existingMasks, int buffer) {
        int candMinX = candidateAABB[0];
        int candMaxX = candidateAABB[1];
        int candMinZ = candidateAABB[4];
        int candMaxZ = candidateAABB[5];
        
        for (VolumeMask mask : existingMasks) {
            int maskMinX = mask.getMinX() - buffer;
            int maskMaxX = mask.getMaxX() + buffer;
            int maskMinZ = mask.getMinZ() - buffer;
            int maskMaxZ = mask.getMaxZ() + buffer;
            
            boolean xOverlap = candMinX <= maskMaxX && candMaxX >= maskMinX;
            boolean zOverlap = candMinZ <= maskMaxZ && candMaxZ >= maskMinZ;
            
            if (xOverlap && zOverlap) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get diagnostic statistics.
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diag = new HashMap<>();
        diag.put("searchesPerformed", searchesPerformed);
        diag.put("totalSearchTimeMs", totalSearchTimeMs);
        diag.put("avgSearchTimeMs", searchesPerformed > 0 ? totalSearchTimeMs / searchesPerformed : 0);
        diag.put("budgetExceededCount", budgetExceededCount);
        return diag;
    }
    
    // ==================== Inner Classes ====================
    
    private static class CandidateSite {
        final int x;
        final int z;
        final int distanceSquared;
        final int dx;
        final int dz;
        
        CandidateSite(int x, int z, int distanceSquared, int dx, int dz) {
            this.x = x;
            this.z = z;
            this.distanceSquared = distanceSquared;
            this.dx = dx;
            this.dz = dz;
        }
    }
    
    private static class PlacementSearchState {
        final World world;
        final Location origin;
        final List<CandidateSite> candidates;
        final int width, depth, height, rotation;
        final List<VolumeMask> existingMasks;
        final SurfaceSolver surfaceSolver;
        final int minSpacing;
        final Consumer<PlacementSearchResult> callback;
        final long startTime;
        
        int currentIndex = 0;
        int totalAttempts = 0;
        int overlapRejections = 0;
        int chunkNotReadyCount = 0;
        
        PlacementSearchState(World world, Location origin, List<CandidateSite> candidates,
                             int width, int depth, int height, int rotation,
                             List<VolumeMask> existingMasks, SurfaceSolver surfaceSolver, int minSpacing,
                             Consumer<PlacementSearchResult> callback, long startTime) {
            this.world = world;
            this.origin = origin;
            this.candidates = candidates;
            this.width = width;
            this.depth = depth;
            this.height = height;
            this.rotation = rotation;
            this.existingMasks = existingMasks;
            this.surfaceSolver = surfaceSolver;
            this.minSpacing = minSpacing;
            this.callback = callback;
            this.startTime = startTime;
        }
    }
    
    /**
     * Result of placement search.
     */
    public static class PlacementSearchResult {
        public final Location location;
        public final int totalAttempts;
        public final int overlapRejections;
        public final int chunkNotReadyCount;
        public final long searchTimeMs;
        public final boolean found;
        
        public PlacementSearchResult(Location location, int totalAttempts, int overlapRejections,
                                      int chunkNotReadyCount, long searchTimeMs, boolean found) {
            this.location = location;
            this.totalAttempts = totalAttempts;
            this.overlapRejections = overlapRejections;
            this.chunkNotReadyCount = chunkNotReadyCount;
            this.searchTimeMs = searchTimeMs;
            this.found = found;
        }
    }
}
