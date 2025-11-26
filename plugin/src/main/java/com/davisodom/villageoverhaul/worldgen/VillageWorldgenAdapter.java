package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.VillageService;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Minimal worldgen adapter that seeds a deterministic test village near spawn.
 *
 * Goals for Phase 2.6 (bootstrap):
 * - Ensure at least one culture-tagged village exists in-world for US1 testing
 * - Deterministic placement near world spawn
 * - Very small footprint (a marker pillar) to avoid griefing worlds during development
 * - Register created village in VillageService
 */
public class VillageWorldgenAdapter implements Listener {

    private final VillageOverhaulPlugin plugin;
    private final Logger logger;
    private final AtomicBoolean seeded = new AtomicBoolean(false);

    public VillageWorldgenAdapter(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Hook world load to seed a test village once.
     * Note: WorldLoadEvent fires before onEnable, so this may not catch the initial world.
     * We also schedule a delayed check.
     */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        logger.info("WorldLoadEvent fired for: " + event.getWorld().getName());
        trySeed(event.getWorld());
    }

    /**
     * Can be called from onEnable to eagerly seed when a default world is already present (MockBukkit/CI).
     * For live servers, schedules a delayed check since worlds load before plugin enable.
     * 
     * T026d14: If system property "vo.suppress.worldgen" is set to "true", worldgen seeding is
     * skipped entirely. This allows CI harnesses running fixed-layout determinism tests to
     * prevent background village generation that would create non-deterministic artifacts.
     */
    public void seedIfPossible() {
        // T026d14: Check for CI suppression flag
        if ("true".equalsIgnoreCase(System.getProperty("vo.suppress.worldgen"))) {
            logger.info("[WORLDGEN] Worldgen seeding suppressed by system property vo.suppress.worldgen=true");
            return;
        }
        
        // Immediate attempt (works in tests where world is added before plugin load)
        if (!Bukkit.getWorlds().isEmpty()) {
            scheduleAsyncSeed(Bukkit.getWorlds().get(0));
            return;
        }
        
        // Schedule delayed check for live servers (worlds load before onEnable)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!Bukkit.getWorlds().isEmpty()) {
                logger.info("Delayed seeding check: found " + Bukkit.getWorlds().size() + " world(s)");
                scheduleAsyncSeed(Bukkit.getWorlds().get(0));
            } else {
                logger.warning("No worlds available for village seeding after delayed check");
            }
        }, 20L); // 1 second delay to ensure worlds are fully loaded
    }
    
    /**
     * Schedule village seeding to run asynchronously so it doesn't block server startup.
     */
    private void scheduleAsyncSeed(World world) {
        if (world == null) {
            logger.warning("scheduleAsyncSeed called with null world");
            return;
        }
        
        logger.info("Scheduling async village seeding for world: " + world.getName());
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            logger.info("ASYNC: Starting village terrain search and seeding (this may take 10-60 seconds)...");
            try {
                trySeed(world);
            } catch (Exception e) {
                logger.severe("Error during async village seeding: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void trySeed(World world) {
        if (world == null) {
            logger.warning("trySeed called with null world");
            return;
        }
        if (!seeded.compareAndSet(false, true)) {
            logger.fine("Village already seeded, skipping");
            return; // only once per server boot
        }

        logger.info("Attempting to seed village in world: " + world.getName());

        // Search for suitable terrain starting from spawn (this is slow, but now async!)
        Location spawn = world.getSpawnLocation();
        Location suitableLocation = findSuitableVillageLocation(world, spawn, 512); // Search up to 512 blocks
        
        if (suitableLocation == null) {
            logger.warning("Could not find suitable terrain for village placement, using spawn location as fallback");
            suitableLocation = spawn.clone().add(16, 0, 16);
        }
        
        int baseX = suitableLocation.getBlockX();
        int baseZ = suitableLocation.getBlockZ();
        
        // T052a: Pre-load chunks in the village area BEFORE switching to main thread
        // This ensures the placement search has enough loaded chunks to work with
        // without blocking the main thread with chunk loading calls
        logger.info("Pre-loading chunks for village placement area...");
        preloadVillageAreaChunks(world, baseX, baseZ, 256); // Load 256-block radius for placement search
        
        int y = world.getHighestBlockYAt(baseX, baseZ);

        logger.info("Village placement selected: " + baseX + ", " + y + ", " + baseZ);

        // Register village in service with culture fallback to first available (roman for now)
        String cultureId = plugin.getCultureService().all().stream().findFirst()
                .map(c -> c.getId()).orElse("roman");
        String name = switch (cultureId) {
            case "roman" -> "Roma I";
            default -> "Village I";
        };

        // T026d14: Use deterministic UUID derived from world seed for reproducible CI runs
        long worldSeed = world.getSeed();
        UUID deterministicVillageId = UUID.nameUUIDFromBytes(
            ("worldgen-village-" + worldSeed + "-" + baseX + "-" + baseZ).getBytes(StandardCharsets.UTF_8));
        
        VillageService vs = plugin.getVillageService();
        var village = vs.createVillage(deterministicVillageId, cultureId, name, world.getName(), baseX, y + 1, baseZ);
        
        // Village creation and structure placement must happen on main thread
        // We're already async from terrain search, so schedule sync for block operations
        final UUID villageId = village.getId();
        final String villageName = village.getName();
        final int finalY = y;
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Use shared metadata store (T012l: singleton for cross-session enforcement)
            VillageMetadataStore metadataStore = plugin.getMetadataStore();
            
            VillagePlacementServiceImpl placementService;
            try {
                placementService = new VillagePlacementServiceImpl(
                    plugin, metadataStore, plugin.getCultureService());
            } catch (NoClassDefFoundError e) {
                logger.severe("X Failed to initialize VillagePlacementServiceImpl: " + e.getMessage());
                logger.severe("  This usually means WorldEdit is not installed or incompatible");
                logger.severe("  Falling back to procedural structures without WorldEdit/FAWE");
                
                // Try fallback constructor without WorldEdit dependency
                try {
                    placementService = new VillagePlacementServiceImpl(metadataStore, plugin.getCultureService());
                    logger.info("OK Using fallback placement service with procedural structures");
                } catch (Exception ex) {
                    logger.severe("X Failed to initialize fallback placement service: " + ex.getMessage());
                    ex.printStackTrace();
                    
                    // Ultimate fallback: just place marker pillar
                    logger.warning("X Placing marker pillar only for village '" + villageName + "'");
                    safeSet(world, baseX, finalY, baseZ, Material.STONE);
                    safeSet(world, baseX, finalY + 1, baseZ, Material.STONE);
                    safeSet(world, baseX, finalY + 2, baseZ, Material.TORCH);
                    return;
                }
            }
            
            // Generate village structures using placement service
            // T026d14: Pass deterministic UUID to ensure same village ID is used for structures
            Location villageOrigin = new Location(world, baseX, finalY, baseZ);
            long seed = world.getSeed() + villageId.getMostSignificantBits();
            
            logger.info("[STRUCT] Generating structures for village '" + villageName + "' (ID: " + villageId + ")");
            Optional<UUID> placedVillageId = placementService.placeVillage(world, villageOrigin, cultureId, seed, villageId);
            
            if (placedVillageId.isPresent()) {
                logger.info("OK Seeded village '" + villageName + "' (" + cultureId + ") with structures at "
                        + world.getName() + " @ (" + baseX + "," + (finalY + 1) + "," + baseZ + ")");
            } else {
                logger.warning("X Failed to place structures for village '" + villageName + "', placing marker pillar");
                // Fallback: Create a tiny marker pillar (stone + torch) to indicate village center
                safeSet(world, baseX, finalY, baseZ, Material.STONE);
                safeSet(world, baseX, finalY + 1, baseZ, Material.STONE);
                safeSet(world, baseX, finalY + 2, baseZ, Material.TORCH);
            }
            
            // Generate initial projects for the village
            if (plugin.getProjectGenerator() != null) {
                plugin.getProjectGenerator().generateInitialProjects(village);
            }
            
            // Spawn initial custom villagers for the village
            spawnInitialVillagers(village, world, baseX, finalY + 1, baseZ);
        });
    }
    
    /**
     * Search for suitable flat terrain for village placement.
     * T052a: Uses time-budgeted chunk loading to prevent main thread blocking.
     * 
     * @param world Target world
     * @param start Starting search location (typically spawn)
     * @param maxRadius Maximum search radius in blocks
     * @return Suitable location or null if none found
     */
    private Location findSuitableVillageLocation(World world, Location start, int maxRadius) {
        logger.info("Searching for suitable village terrain within " + maxRadius + " blocks of spawn...");
        logger.info("  Spawn location: " + start.getBlockX() + ", " + start.getBlockY() + ", " + start.getBlockZ());
        
        int startX = start.getBlockX();
        int startZ = start.getBlockZ();
        int checkRadius = 24; // Check 24 block radius for flatness (reduced from 32)
        int sampleInterval = 24; // Check every 24 blocks in spiral (increased from 16 for speed)
        
        int locationsChecked = 0;
        long searchStartTime = System.currentTimeMillis();
        
        // T052a: Time budget for chunk loading (ms) - prevents extended blocking
        final long CHUNK_LOAD_BUDGET_MS = 2000; // Max 2 seconds of chunk loading for terrain search
        int chunksLoaded = 0;
        int chunksSkipped = 0;
        
        // Spiral search pattern - increased max radius for more opportunities
        for (int radius = 16; radius <= Math.min(maxRadius, 768); radius += sampleInterval) {
            // Check 8 points around the circle at this radius
            for (int i = 0; i < 8; i++) {
                double angle = (i / 8.0) * 2 * Math.PI;
                int x = startX + (int)(radius * Math.cos(angle));
                int z = startZ + (int)(radius * Math.sin(angle));
                
                locationsChecked++;
                
                // T052a: Check time budget before chunk loading
                long elapsed = System.currentTimeMillis() - searchStartTime;
                if (elapsed > CHUNK_LOAD_BUDGET_MS) {
                    // Budget exceeded - log diagnostic and skip remaining unloaded chunks
                    if (chunksSkipped == 0) {
                        logger.warning(String.format("[TERRAIN][DIAG] Chunk load budget exceeded (%dms > %dms), skipping unloaded chunks",
                                elapsed, CHUNK_LOAD_BUDGET_MS));
                    }
                    
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        chunksSkipped++;
                        continue;
                    }
                }
                
                // Check if this location is suitable
                if (isTerrainSuitableTimeBudgeted(world, x, z, checkRadius, searchStartTime, CHUNK_LOAD_BUDGET_MS)) {
                    int y = world.getHighestBlockYAt(x, z);
                    long searchTime = System.currentTimeMillis() - searchStartTime;
                    logger.info("OK Found suitable terrain after checking " + locationsChecked + " locations in " + searchTime + "ms");
                    logger.info("   Location: distance=" + radius + " blocks, coords=(" + x + ", " + y + ", " + z + ")");
                    
                    // T052a: Emit diagnostic if significant chunk loading occurred
                    if (chunksLoaded > 20 || chunksSkipped > 0) {
                        logger.info(String.format("[TERRAIN][DIAG] Terrain search stats: chunks_loaded=%d, chunks_skipped=%d, time=%dms",
                                chunksLoaded, chunksSkipped, searchTime));
                    }
                    
                    return new Location(world, x, y, z);
                }
            }
            
            // Log progress every 100 blocks of radius to show we're not stuck
            if (radius % 96 == 0) {
                long elapsed = System.currentTimeMillis() - searchStartTime;
                logger.info("  Terrain search progress: radius=" + radius + "/" + maxRadius + ", checked=" + locationsChecked + " locations, elapsed=" + elapsed + "ms");
            }
        }
        
        long searchTime = System.currentTimeMillis() - searchStartTime;
        logger.warning("X No suitable terrain found after checking " + locationsChecked + " locations in " + searchTime + "ms");
        
        // T052a: Emit final diagnostic
        if (chunksSkipped > 0) {
            logger.warning(String.format("[TERRAIN][DIAG] Search completed with %d chunks skipped due to budget", chunksSkipped));
        }
        
        return null;
    }
    
    /**
     * Check if terrain at location is suitable for village placement.
     * T052a: Uses time-budgeted chunk loading to prevent blocking.
     * 
     * @param world Target world
     * @param centerX Center X coordinate
     * @param centerZ Center Z coordinate
     * @param checkRadius Radius to check around center
     * @param searchStartTime When the search started (for budget tracking)
     * @param chunkLoadBudgetMs Maximum ms to spend on chunk loading
     * @return true if terrain is suitable
     */
    private boolean isTerrainSuitableTimeBudgeted(World world, int centerX, int centerZ, int checkRadius,
                                                   long searchStartTime, long chunkLoadBudgetMs) {
        // T052a: Check time budget before forcing chunk loads
        long elapsed = System.currentTimeMillis() - searchStartTime;
        boolean budgetExceeded = elapsed > chunkLoadBudgetMs;
        
        // Load chunks for terrain check - with time budget
        int chunkX = centerX >> 4;
        int chunkZ = centerZ >> 4;
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = chunkX + dx;
                int cz = chunkZ + dz;
                
                if (!world.isChunkLoaded(cx, cz)) {
                    if (budgetExceeded) {
                        // Budget exceeded - return false to skip this location
                        return false;
                    }
                    
                    // Try async chunk load (Paper API) with timeout
                    try {
                        world.getChunkAtAsync(cx, cz).get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        // Async failed or timed out - try sync load if we still have budget
                        long currentElapsed = System.currentTimeMillis() - searchStartTime;
                        if (currentElapsed < chunkLoadBudgetMs) {
                            try {
                                world.getChunkAt(cx, cz);
                            } catch (Exception ex) {
                                return false; // Chunk load failed
                            }
                        } else {
                            return false; // Budget exceeded
                        }
                    }
                }
            }
        }
        
        // Now do the actual terrain check (chunks are loaded)
        return evaluateTerrainFast(world, centerX, centerZ, checkRadius);
    }
    
    /**
     * Fast terrain evaluation (assumes chunks are loaded).
     */
    private boolean evaluateTerrainFast(World world, int centerX, int centerZ, int checkRadius) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int waterBlocks = 0;
        int totalChecks = 0;
        
        // Sample terrain in a grid pattern (increased from 8 to 12 for speed)
        for (int x = -checkRadius; x <= checkRadius; x += 12) {
            for (int z = -checkRadius; z <= checkRadius; z += 12) {
                int checkX = centerX + x;
                int checkZ = centerZ + z;
                int y = world.getHighestBlockYAt(checkX, checkZ);
                
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                totalChecks++;
                
                // Check if surface is water
                Material surface = world.getBlockAt(checkX, y, checkZ).getType();
                if (surface == Material.WATER) {
                    waterBlocks++;
                }
            }
        }
        
        int yVariation = maxY - minY;
        double waterPercent = (double) waterBlocks / totalChecks;
        
        // Criteria for suitable terrain (aligned with structure placement validation):
        // - Y variation <= 8 blocks (must match structure placement MAX_SLOPE_DELTA)
        // - Less than 30% water coverage
        // - Not too high or too low (between Y 50 and Y 120)
        // Tightened from 15 to 8 blocks to match TerrainClassifier MAX_SLOPE_DELTA
        boolean flatEnough = yVariation <= 8;
        boolean notTooWatery = waterPercent < 0.3;
        boolean goodHeight = minY >= 50 && maxY <= 120;
        
        return flatEnough && notTooWatery && goodHeight;
    }
    
    /**
     * T052a: Pre-load chunks in the village placement area.
     * This runs in the async context BEFORE switching to main thread for placement,
     * ensuring the placement search has loaded chunks to work with.
     * Uses async chunk loading with CompletableFuture.allOf() to batch load efficiently.
     * 
     * @param world Target world
     * @param centerX Village center X
     * @param centerZ Village center Z  
     * @param radius Radius in blocks to pre-load (will be converted to chunks)
     */
    private void preloadVillageAreaChunks(World world, int centerX, int centerZ, int radius) {
        // Convert block radius to chunk radius (16 blocks per chunk)
        int chunkRadius = (radius / 16) + 1;
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        
        List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> futures = new ArrayList<>();
        int totalChunks = 0;
        int alreadyLoaded = 0;
        
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                totalChunks++;
                if (world.isChunkLoaded(cx, cz)) {
                    alreadyLoaded++;
                    continue;
                }
                
                // Use Paper's async chunk loading API
                try {
                    futures.add(world.getChunkAtAsync(cx, cz));
                } catch (Exception e) {
                    // Async not available, will be loaded on-demand
                }
            }
        }
        
        // Wait for all async chunk loads to complete (we're in async context, so this is fine)
        if (!futures.isEmpty()) {
            try {
                java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(new java.util.concurrent.CompletableFuture[0])
                ).join();
                logger.info(String.format("Pre-loaded %d chunks for village area (%d were already loaded, %d total)",
                        futures.size(), alreadyLoaded, totalChunks));
            } catch (Exception e) {
                logger.warning("Some chunks failed to pre-load: " + e.getMessage());
            }
        } else {
            logger.info(String.format("All %d village area chunks already loaded", totalChunks));
        }
    }
    
    /**
     * Spawn initial custom villagers for a new village
     * Spawns 2-3 villagers based on culture professions
     */
    private void spawnInitialVillagers(com.davisodom.villageoverhaul.villages.Village village, 
                                       World world, int centerX, int centerY, int centerZ) {
        var npcService = plugin.getCustomVillagerService();
        var appearanceAdapter = plugin.getVillagerAppearanceAdapter();
        
        if (npcService == null || appearanceAdapter == null) {
            logger.warning("NPC services not initialized, skipping villager spawns");
            return;
        }
        
        String cultureId = village.getCultureId();
        
        // Spawn villagers at offset positions around village center
        int[][] spawnOffsets = {
            {3, 0, 2},   // East side
            {-2, 0, 3},  // West side
            {0, 0, -3}   // North side
        };
        
        String[] professions = {"merchant", "blacksmith", "elder"};
        
        for (int i = 0; i < Math.min(spawnOffsets.length, professions.length); i++) {
            int[] offset = spawnOffsets[i];
            String profession = professions[i];
            String definitionId = cultureId + "_" + profession;
            
            Location spawnLoc = new Location(
                world,
                centerX + offset[0] + 0.5,
                centerY + offset[1],
                centerZ + offset[2] + 0.5
            );
            
            var customVillager = npcService.spawnVillager(
                definitionId,
                cultureId,
                profession,
                village.getId(),
                spawnLoc
            );
            
            if (customVillager != null) {
                // Apply appearance
                org.bukkit.entity.Entity entity = plugin.getServer().getEntity(customVillager.getEntityId());
                if (entity != null) {
                    appearanceAdapter.applyAppearance(entity, definitionId);
                }
                
                logger.info("  OK Spawned " + definitionId + " at village " + village.getName());
            }
        }
    }

    private void safeSet(World world, int x, int y, int z, Material material) {
        try {
            world.getBlockAt(x, y, z).setType(material, false);
        } catch (Throwable t) {
            // In MockBukkit or when worlds aren't fully ready, ignore block placement failures
        }
    }
}

