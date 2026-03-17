package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl.PlacementOutcome;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl.PlacementStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Processes village generation requests from commands with tick budgeting.
 * Spreads generation work across multiple ticks to avoid server freezes.
 * 
 * T066: Non-blocking command execution for /vo generate and /votest generate-structures
 * 
 * Budgets:
 * - Per-tick time budget: 10ms (configurable)
 * - Chunk loading: async with deferral to next tick if not ready
 * - Structure placement: batched, one per tick when possible
 * - Progress logging: every 5 ticks or on phase change
 */
public class TickBudgetedGenerationQueue {
    
    private static final Logger LOGGER = Logger.getLogger(TickBudgetedGenerationQueue.class.getName());
    private static final int MAX_SPAWN_RETRY_ATTEMPTS = 3;
    
    // Per-tick time budget in milliseconds
    private static final long DEFAULT_TICK_BUDGET_MS = 10;
    
    // Progress log interval in ticks
    private static final int PROGRESS_LOG_INTERVAL_TICKS = 5;
    
    // Maximum chunk load wait time before deferring to next tick
    private static final long MAX_CHUNK_WAIT_MS = 5;
    
    private final VillageOverhaulPlugin plugin;
    private final VillageMetadataStore metadataStore;
    private final VillageTerrainSearcher terrainSearcher;
    
    // Pending requests queue
    private final Queue<CommandGenerationRequest> pendingRequests = new ConcurrentLinkedQueue<>();
    
    // Currently processing request
    private CommandGenerationRequest currentRequest = null;
    
    // Generation state for current request
    private GenerationState currentState = null;
    
    // Ticker task
    private BukkitTask tickerTask = null;
    
    // Statistics
    private long tickBudgetMs = DEFAULT_TICK_BUDGET_MS;
    private int ticksSinceLastProgress = 0;
    
    public TickBudgetedGenerationQueue(VillageOverhaulPlugin plugin, VillageMetadataStore metadataStore) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore cannot be null");
        // T071: Use shared terrain searcher
        this.terrainSearcher = new VillageTerrainSearcher(plugin, metadataStore);
    }
    
    /**
     * Start the queue processor.
     */
    public void start() {
        if (tickerTask != null) {
            LOGGER.warning("[GEN-QUEUE] Already started");
            return;
        }
        
        // Run every tick (20 TPS)
        tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        LOGGER.info(String.format("[GEN-QUEUE] Started (budget=%dms/tick)", tickBudgetMs));
    }
    
    /**
     * Stop the queue processor and cancel all pending requests.
     */
    public void stop() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        
        // Notify pending requests
        int aborted = 0;
        while (!pendingRequests.isEmpty()) {
            CommandGenerationRequest request = pendingRequests.poll();
            if (request != null) {
                request.sendMessage(Component.text("Village generation cancelled: server shutting down", NamedTextColor.RED));
                aborted++;
            }
        }
        
        // Abort current request
        if (currentRequest != null) {
            currentRequest.sendMessage(Component.text("Village generation cancelled: server shutting down", NamedTextColor.RED));
            aborted++;
            currentRequest = null;
            currentState = null;
        }
        
        if (aborted > 0) {
            LOGGER.warning(String.format("[GEN-QUEUE] Aborted %d generation requests on shutdown", aborted));
        }
        
        LOGGER.info("[GEN-QUEUE] Stopped");
    }
    
    /**
     * Enqueue a generation request.
     * 
     * @param request Request to enqueue
     */
    public void enqueue(CommandGenerationRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        pendingRequests.offer(request);
        
        LOGGER.info(String.format("[GEN-QUEUE] Enqueued: %s (queue depth: %d)", 
                request, pendingRequests.size()));
        
        request.sendMessage(Component.text("Village generation queued. Position in queue: " + 
                pendingRequests.size(), NamedTextColor.GRAY));
    }
    
    /**
     * Get number of pending requests.
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }
    
    /**
     * Get currently processing request, or null if idle.
     */
    public CommandGenerationRequest getCurrentRequest() {
        return currentRequest;
    }
    
    /**
     * Set tick budget in milliseconds.
     */
    public void setTickBudgetMs(long budgetMs) {
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("tickBudgetMs must be positive");
        }
        this.tickBudgetMs = budgetMs;
    }
    
    /**
     * Main tick method: process current or next request.
     */
    private void tick() {
        long tickStart = System.currentTimeMillis();
        
        try {
            // Start new request if idle
            if (currentRequest == null && !pendingRequests.isEmpty()) {
                currentRequest = pendingRequests.poll();
                if (currentRequest != null) {
                    startRequest(currentRequest);
                }
            }
            
            // Process current request
            if (currentRequest != null && currentState != null) {
                processRequest(tickStart);
            }
            
            ticksSinceLastProgress++;
            
        } catch (Exception e) {
            LOGGER.severe(String.format("[GEN-QUEUE] Error processing request: %s", e.getMessage()));
            e.printStackTrace();
            
            if (currentRequest != null) {
                currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
                currentRequest.sendMessage(Component.text("Village generation failed: " + e.getMessage(), 
                        NamedTextColor.RED));
                finishRequest();
            }
        }
    }
    
    /**
     * Start processing a new request.
     */
    private void startRequest(CommandGenerationRequest request) {
        LOGGER.info(String.format("[GEN-QUEUE] Starting: %s", request));

        currentState = new GenerationState();
        currentState.world = request.getOrigin().getWorld();
        currentState.searchOrigin = request.getOrigin();
        currentState.existingVillageRequest = request.isExistingVillageRequest();

        if (request.isExistingVillageRequest()) {
            if (currentState.world == null) {
                request.sendMessage(Component.text("World not available", NamedTextColor.RED));
                request.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
                return;
            }

            currentState.villageId = request.getExistingVillageId();
            currentState.villageName = request.getVillageName();
            currentState.suitableLocation = request.getOrigin();

            Long seedOverride = request.getSeed();
            long resolvedSeed = seedOverride != null ? seedOverride : resolveExistingVillageSeed(request);
            currentState.villageSeed = resolvedSeed;

            currentState.placementService = new VillagePlacementServiceImpl(
                    plugin, metadataStore, plugin.getCultureService());

            request.setCurrentPhase(CommandGenerationRequest.GenerationPhase.STRUCTURE_PLACEMENT);
            request.sendMessage(Component.text("Existing village detected. Attempting to add missing structures...",
                    NamedTextColor.GRAY));
        } else {
            request.setCurrentPhase(CommandGenerationRequest.GenerationPhase.TERRAIN_SEARCH);
            request.sendMessage(Component.text("Starting village generation for '" + request.getVillageName() + "'...",
                    NamedTextColor.GRAY));
        }

        ticksSinceLastProgress = 0;
    }
    
    /**
     * Process current request with tick budget.
     */
    private void processRequest(long tickStart) {
        long remaining = tickBudgetMs - (System.currentTimeMillis() - tickStart);
        
        if (remaining <= 0) {
            // Budget exhausted this tick
            return;
        }
        
        switch (currentRequest.getCurrentPhase()) {
            case TERRAIN_SEARCH:
                processTerrainSearch(tickStart, remaining);
                break;
                
            case VILLAGE_CREATION:
                processVillageCreation(tickStart, remaining);
                break;
                
            case STRUCTURE_PLACEMENT:
                processStructurePlacement(tickStart, remaining);
                break;
                
            case COMPLETED:
            case FAILED:
                finishRequest();
                break;
                
            default:
                LOGGER.warning(String.format("[GEN-QUEUE] Unknown phase: %s", currentRequest.getCurrentPhase()));
                currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
                break;
        }
    }
    
    /**
     * Process terrain search phase (async chunk loading, no blocking).
     * 
     * T071: Updated to use proper terrain validation and inter-village spacing enforcement.
     */
    private void processTerrainSearch(long tickStart, long remainingMs) {
        // If we already found terrain, move to next phase
        if (currentState.suitableLocation != null) {
            if (!ensurePreloadReady(currentState.suitableLocation)) {
                return;
            }
            currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.VILLAGE_CREATION);
            logProgress();
            return;
        }
        
        // Get search parameters from request
        Location searchOrigin = currentRequest.getOrigin();
        World world = searchOrigin.getWorld();
        
        if (world == null) {
            currentRequest.sendMessage(Component.text("World not available", NamedTextColor.RED));
            currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
            return;
        }
        
        // T071: Adjust search origin based on first/subsequent village logic
        boolean isFirstVillage = terrainSearcher.isFirstVillage(world);
        int spawnProximityRadius = plugin.getSpawnProximityRadius();

        if (currentState.spawnRetryOrigin != null) {
            searchOrigin = currentState.spawnRetryOrigin;
        } else if (isFirstVillage && spawnProximityRadius > 0) {
            // First village: search near spawn
            searchOrigin = world.getSpawnLocation();
            currentRequest.sendMessage(Component.text("First village: searching within " + spawnProximityRadius +
                    " blocks of spawn...", NamedTextColor.GRAY));
        } else if (!isFirstVillage) {
            // Subsequent villages: find nearest existing village
            Location nearestVillage = terrainSearcher.findNearestVillageLocation(world, searchOrigin);
            if (nearestVillage != null) {
                searchOrigin = nearestVillage;
                currentRequest.sendMessage(Component.text("Subsequent village: searching near existing village...",
                        NamedTextColor.GRAY));
            }
        }
        
        // T071: Use proper terrain search with spacing enforcement
        int minVillageSpacing = plugin.getMinVillageSpacing();
        int maxSearchRadius = 512; // Match GenerateCommand default
        if (currentState.spawnRetryAttempts > 0) {
            maxSearchRadius += currentState.spawnRetryAttempts * 256;
        }
        
        Location suitableLocation = terrainSearcher.findSuitableVillageLocation(
                world, searchOrigin, maxSearchRadius, minVillageSpacing);
        
        if (suitableLocation == null) {
            // No suitable terrain found
            currentRequest.sendMessage(Component.text(
                    "Failed to find suitable terrain within " + maxSearchRadius + " blocks. " +
                    "All candidate locations either failed terrain checks or violated minVillageSpacing (" + 
                    minVillageSpacing + " blocks).", NamedTextColor.RED));
            currentRequest.sendMessage(Component.text(
                    "Try a different location or increase search radius.", NamedTextColor.GRAY));
            currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
            return;
        }
        
        // Found suitable terrain
        currentState.suitableLocation = suitableLocation;
        currentState.searchOrigin = searchOrigin;
        
        currentRequest.sendMessage(Component.text("Found suitable terrain at " + 
                formatLocation(currentState.suitableLocation), NamedTextColor.GREEN));

        if (!ensurePreloadReady(currentState.suitableLocation)) {
            return;
        }
        
        currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.VILLAGE_CREATION);
        logProgress();
    }
    
    /**
     * Process village creation phase (quick, main-thread safe).
     */
    private void processVillageCreation(long tickStart, long remainingMs) {
        // Create village entity
        Location loc = currentState.suitableLocation;
        
        // Calculate seed
        Long seed = currentRequest.getSeed();
        if (seed == null) {
            seed = loc.getWorld().getSeed() ^ (((long)loc.getBlockX() << 32) | (loc.getBlockZ() & 0xFFFFFFFFL));
        }
        currentState.villageSeed = seed;

        UUID deterministicVillageId = computeDeterministicVillageId(seed, loc);
        currentState.villageId = deterministicVillageId;
        
        // Create village
        Village village = plugin.getVillageService().createVillage(
            deterministicVillageId,
                currentRequest.getCultureId(),
                currentRequest.getVillageName(),
                loc.getWorld(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        );
        plugin.getMetadataStore().setVillageName(deterministicVillageId, currentRequest.getVillageName());

        currentState.villageName = currentRequest.getVillageName();
        
        currentRequest.sendMessage(Component.text("Village '" + village.getName() + "' created (ID: " + 
                village.getId() + ")", NamedTextColor.GRAY));
        
        // Set up placement service
        currentState.placementService = new VillagePlacementServiceImpl(
                plugin, metadataStore, plugin.getCultureService());
        
        // Move to structure placement
        currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.STRUCTURE_PLACEMENT);
        logProgress();
    }
    
    /**
     * Process structure placement phase (budgeted, one structure at a time).
     */
    private void processStructurePlacement(long tickStart, long remainingMs) {
        if (currentState.suitableLocation != null && !ensurePreloadReady(currentState.suitableLocation)) {
            return;
        }
        // If not started, initiate placement
        if (currentState.placementFuture == null) {
            initiateStructurePlacement();
            return; // Will check status next tick
        }
        
        // Check if placement is complete
        if (!currentState.placementFuture.isDone()) {
            // Still in progress, check next tick
            return;
        }
        
        // Placement complete, get results
        try {
            PlacementOutcome outcome = currentState.placementFuture.join();

            if (currentState.existingVillageRequest) {
            handleExistingVillageOutcome(outcome);
            } else {
            handleNewVillageOutcome(outcome);
            }
            
            logProgress();
            
        } catch (Exception e) {
            LOGGER.severe(String.format("[GEN-QUEUE] Placement error: %s", e.getMessage()));
            e.printStackTrace();
            
            currentRequest.sendMessage(Component.text("Village generation failed: " + e.getMessage(), 
                    NamedTextColor.RED));
            currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
        }
    }
    
    /**
     * Initiate structure placement on the main thread.
     * 
     * CRITICAL: Block placement MUST run on main thread per Minecraft/Paper requirements.
     * AsyncCatcher will throw IllegalStateException if blocks are modified off-thread.
     */
    private void initiateStructurePlacement() {
        CompletableFuture<PlacementOutcome> future = new CompletableFuture<>();
        
        // Schedule placement on main server thread (required for block modifications)
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (currentState.existingVillageRequest) {
                    PlacementOutcome outcome = currentState.placementService.placeStructuresForExistingVillage(
                            currentState.world,
                            currentState.suitableLocation,
                            currentRequest.getCultureId(),
                            currentState.villageSeed,
                            currentState.villageId
                    );
                    future.complete(outcome);
                } else {
                    Optional<UUID> result = currentState.placementService.placeVillage(
                            currentState.world,
                            currentState.suitableLocation,
                            currentRequest.getCultureId(),
                        currentState.villageSeed,
                        currentState.villageId
                    );

                    if (result.isPresent()) {
                        int buildingCount = resolvePlacedCount(currentState.villageId);
                        future.complete(new PlacementOutcome(PlacementStatus.SUCCESS, result.get(), 0, buildingCount, buildingCount));
                    } else {
                        future.complete(new PlacementOutcome(PlacementStatus.FAILED, currentState.villageId, 0, 0, 0));
                    }
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        currentState.placementFuture = future;
        
        LOGGER.info(String.format("[GEN-QUEUE] Initiated structure placement for '%s'", 
                currentRequest.getVillageName()));
    }
    
    /**
     * Finish current request and move to next.
     */
    private void finishRequest() {
        if (currentRequest == null) {
            return;
        }

        releaseChunkTickets();
        
        LOGGER.info(String.format("[GEN-QUEUE] Finished: %s (phase=%s, elapsed=%dms)", 
                currentRequest, currentRequest.getCurrentPhase(), currentRequest.getTotalElapsedMs()));
        
        currentRequest = null;
        currentState = null;
        ticksSinceLastProgress = 0;
    }
    
    /**
     * Log progress if interval reached or phase changed.
     */
    private void logProgress() {
        if (ticksSinceLastProgress >= PROGRESS_LOG_INTERVAL_TICKS || currentRequest == null) {
            if (currentRequest != null) {
                LOGGER.info(String.format("[GEN-PROGRESS] placed=%d attempts=%d elapsedMs=%d phase=%s village='%s'",
                        currentRequest.getStructuresPlaced(),
                        currentRequest.getStructureAttempts(),
                        currentRequest.getTotalElapsedMs(),
                        currentRequest.getCurrentPhase(),
                        currentRequest.getVillageName()));
            }
            ticksSinceLastProgress = 0;
        }
    }

    private boolean ensurePreloadReady(Location center) {
        if (currentState == null || center == null) {
            return true;
        }

        if (currentState.preloadFuture == null) {
            currentState.preloadCenterX = center.getBlockX();
            currentState.preloadCenterZ = center.getBlockZ();
            currentState.preloadRadiusBlocks = plugin.getMaxBoundsRadiusBlocks() + 32;
            currentState.preloadFuture = preloadPlacementChunks(center.getWorld(),
                    currentState.preloadCenterX, currentState.preloadCenterZ, currentState.preloadRadiusBlocks);
            currentRequest.sendMessage(Component.text("Pre-loading chunks for placement area...",
                    NamedTextColor.GRAY));
            return false;
        }

        if (!currentState.preloadFuture.isDone()) {
            return false;
        }

        if (currentState.preloadResult == null) {
            currentState.preloadResult = currentState.preloadFuture.join();
            logPreloadResult(currentState.preloadResult);
        }

        if (!currentState.chunkTicketsApplied) {
            currentState.chunkTicketsApplied = reserveChunkTickets(center.getWorld(),
                    currentState.preloadCenterX, currentState.preloadCenterZ, currentState.preloadRadiusBlocks);
        }

        return true;
    }

    private CompletableFuture<ChunkPreloadResult> preloadPlacementChunks(World world, int centerX, int centerZ, int radiusBlocks) {
        if (world == null) {
            return CompletableFuture.completedFuture(new ChunkPreloadResult(0, 0, 0, "World not available"));
        }

        int chunkRadius = (radiusBlocks / 16) + 1;
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;

        List<CompletableFuture<org.bukkit.Chunk>> futures = new ArrayList<>();
        int totalChunks = 0;
        int alreadyLoaded = 0;

        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                totalChunks++;
                if (world.isChunkLoaded(cx, cz)) {
                    alreadyLoaded++;
                    continue;
                }

                try {
                    futures.add(world.getChunkAtAsync(cx, cz));
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(new ChunkPreloadResult(0, alreadyLoaded, totalChunks,
                            "Chunk async load failed: " + e.getMessage()));
                }
            }
        }

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(new ChunkPreloadResult(0, alreadyLoaded, totalChunks, null));
        }

        final int finalAlreadyLoaded = alreadyLoaded;
        final int finalTotalChunks = totalChunks;

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return all.handle((ignored, error) -> {
            String errorMessage = error != null ? error.getMessage() : null;
            return new ChunkPreloadResult(futures.size(), finalAlreadyLoaded, finalTotalChunks, errorMessage);
        });
    }

    private void logPreloadResult(ChunkPreloadResult result) {
        if (result == null) {
            return;
        }
        if (result.errorMessage != null) {
            LOGGER.warning(String.format("[GEN-QUEUE] Chunk pre-load encountered errors: %s", result.errorMessage));
        }
        if (result.totalChunks == 0) {
            return;
        }
        LOGGER.info(String.format("[GEN-QUEUE] Pre-loaded %d chunks for placement area (%d were already loaded, %d total)",
                result.loadedNow, result.alreadyLoaded, result.totalChunks));
    }

    private boolean reserveChunkTickets(World world, int centerX, int centerZ, int radiusBlocks) {
        if (world == null || currentState == null) {
            return false;
        }

        int chunkRadius = (radiusBlocks / 16) + 1;
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;

        currentState.chunkTicketMinX = centerChunkX - chunkRadius;
        currentState.chunkTicketMaxX = centerChunkX + chunkRadius;
        currentState.chunkTicketMinZ = centerChunkZ - chunkRadius;
        currentState.chunkTicketMaxZ = centerChunkZ + chunkRadius;

        int applied = 0;
        for (int cx = currentState.chunkTicketMinX; cx <= currentState.chunkTicketMaxX; cx++) {
            for (int cz = currentState.chunkTicketMinZ; cz <= currentState.chunkTicketMaxZ; cz++) {
                try {
                    if (world.addPluginChunkTicket(cx, cz, plugin)) {
                        applied++;
                    }
                } catch (Exception e) {
                    LOGGER.warning(String.format("[GEN-QUEUE] Failed to add chunk ticket (%d,%d): %s", cx, cz, e.getMessage()));
                }
            }
        }

        LOGGER.info(String.format("[GEN-QUEUE] Applied %d chunk tickets for placement area", applied));
        return true;
    }

    private void releaseChunkTickets() {
        if (currentState == null || !currentState.chunkTicketsApplied) {
            return;
        }

        World world = currentState.world;
        if (world == null) {
            return;
        }

        int removed = 0;
        for (int cx = currentState.chunkTicketMinX; cx <= currentState.chunkTicketMaxX; cx++) {
            for (int cz = currentState.chunkTicketMinZ; cz <= currentState.chunkTicketMaxZ; cz++) {
                try {
                    if (world.removePluginChunkTicket(cx, cz, plugin)) {
                        removed++;
                    }
                } catch (Exception e) {
                    LOGGER.warning(String.format("[GEN-QUEUE] Failed to remove chunk ticket (%d,%d): %s", cx, cz, e.getMessage()));
                }
            }
        }

        LOGGER.info(String.format("[GEN-QUEUE] Released %d chunk tickets for placement area", removed));
    }
    
    /**
     * Format location for display.
     */
    private String formatLocation(Location loc) {
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private UUID computeDeterministicVillageId(long seed, Location origin) {
        return UUID.nameUUIDFromBytes(
            (seed + ":" + origin.getBlockX() + ":" + origin.getBlockZ()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private int resolvePlacedCount(UUID villageId) {
        if (villageId == null) {
            return 0;
        }
        int receiptCount = metadataStore.getPlacementReceipts(villageId).size();
        if (receiptCount > 0) {
            return receiptCount;
        }
        return metadataStore.getVillageBuildings(villageId).size();
    }
    
    /**
     * Internal state for current generation request.
     */
    private static class GenerationState {
        World world;
        Location searchOrigin;
        Location suitableLocation;
        UUID villageId;
        String villageName;
        long villageSeed;
        VillagePlacementServiceImpl placementService;
        CompletableFuture<PlacementOutcome> placementFuture;
        boolean existingVillageRequest;
        int spawnRetryAttempts;
        Location spawnRetryOrigin;
        CompletableFuture<ChunkPreloadResult> preloadFuture;
        ChunkPreloadResult preloadResult;
        int preloadCenterX;
        int preloadCenterZ;
        int preloadRadiusBlocks;
        boolean chunkTicketsApplied;
        int chunkTicketMinX;
        int chunkTicketMaxX;
        int chunkTicketMinZ;
        int chunkTicketMaxZ;
    }

    private static class ChunkPreloadResult {
        private final int loadedNow;
        private final int alreadyLoaded;
        private final int totalChunks;
        private final String errorMessage;

        private ChunkPreloadResult(int loadedNow, int alreadyLoaded, int totalChunks, String errorMessage) {
            this.loadedNow = loadedNow;
            this.alreadyLoaded = alreadyLoaded;
            this.totalChunks = totalChunks;
            this.errorMessage = errorMessage;
        }
    }

        private long resolveExistingVillageSeed(CommandGenerationRequest request) {
        if (request.getExistingVillageId() == null) {
            return 0L;
        }

        Optional<VillageMetadataStore.VillageMetadata> metadataOpt =
            metadataStore.getVillage(request.getExistingVillageId());

        if (metadataOpt.isPresent()) {
            return metadataOpt.get().getSeed();
        }

        World world = request.getOrigin().getWorld();
        if (world == null) {
            return 0L;
        }

        Location origin = request.getOrigin();
        return world.getSeed() ^ (((long) origin.getBlockX() << 32) | (origin.getBlockZ() & 0xFFFFFFFFL));
        }

        private void handleExistingVillageOutcome(PlacementOutcome outcome) {
        if (outcome.getStatus() == PlacementStatus.SUCCESS) {
            int total = outcome.getExistingBuildings() + outcome.getPlacedBuildings();
            currentRequest.setStructuresPlaced(outcome.getPlacedBuildings());
            currentRequest.sendMessage(Component.text("Added " + outcome.getPlacedBuildings() +
                " structures to village '" + currentState.villageName + "'", NamedTextColor.GREEN));
            currentRequest.sendMessage(Component.text("  Existing: " + outcome.getExistingBuildings() +
                ", Total: " + total, NamedTextColor.GRAY));
            currentRequest.sendMessage(Component.text("  Seed: " + currentState.villageSeed, NamedTextColor.GRAY));

            LOGGER.info(String.format("[GEN-QUEUE] Added %d structures to existing village '%s' (total=%d)",
                outcome.getPlacedBuildings(), currentState.villageName, total));

            currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.COMPLETED);
            return;
        }

        if (outcome.getStatus() == PlacementStatus.FULL) {
            currentRequest.sendMessage(Component.text("Village already has all structure types. No additional placement needed (no cap).",
                NamedTextColor.YELLOW));
            currentRequest.sendMessage(Component.text("  Existing: " + outcome.getExistingBuildings() +
                " / " + outcome.getTotalStructures() + " types", NamedTextColor.GRAY));
            currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.COMPLETED);
            return;
        }

        currentRequest.sendMessage(Component.text("Failed to place additional structures for village '" +
            currentState.villageName + "'", NamedTextColor.RED));
        currentRequest.sendMessage(Component.text("  Placed this run: " + outcome.getPlacedBuildings(), NamedTextColor.GRAY));
        currentRequest.sendMessage(Component.text("  Existing: " + outcome.getExistingBuildings() +
            " (structure types=" + outcome.getTotalStructures() + ", repeats allowed)", NamedTextColor.GRAY));
        currentRequest.sendMessage(Component.text("Check server logs for details.", NamedTextColor.GRAY));

        LOGGER.warning(String.format("[GEN-QUEUE] Failed to add structures for existing village '%s'",
            currentState.villageName));

        currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
        }

        private void handleNewVillageOutcome(PlacementOutcome outcome) {
        if (outcome.getStatus() == PlacementStatus.SUCCESS) {
            int buildingCount = resolvePlacedCount(currentState.villageId);
            currentRequest.setStructuresPlaced(buildingCount);

            currentRequest.sendMessage(Component.text("Village '" + currentState.villageName +
                "' generated successfully!", NamedTextColor.GREEN));
            currentRequest.sendMessage(Component.text("  Culture: " + currentRequest.getCultureId(),
                NamedTextColor.GRAY));
            currentRequest.sendMessage(Component.text("  Location: " + formatLocation(currentState.suitableLocation),
                NamedTextColor.GRAY));
            currentRequest.sendMessage(Component.text("  Buildings: " + buildingCount, NamedTextColor.GRAY));
            currentRequest.sendMessage(Component.text("  Seed: " + currentState.villageSeed, NamedTextColor.GRAY));

            LOGGER.info(String.format("[GEN-QUEUE] Successfully generated village '%s' with %d buildings",
                currentState.villageName, buildingCount));

            currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.COMPLETED);
            return;
        }

        if (shouldRetrySpawnAfterZeroPlacement()) {
            return;
        }

        currentRequest.sendMessage(Component.text("Failed to place structures for village '" +
            currentState.villageName + "'", NamedTextColor.RED));
        currentRequest.sendMessage(Component.text("Check server logs for details.", NamedTextColor.GRAY));

        boolean allowMarkerFallback = plugin.getConfig().getBoolean("worldgen.allowMarkerFallback", false);
        if (allowMarkerFallback) {
            Location loc = currentState.suitableLocation;
            loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                .setType(Material.STONE, false);
            loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ())
                .setType(Material.STONE, false);
            loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() + 2, loc.getBlockZ())
                .setType(Material.TORCH, false);
            currentRequest.sendMessage(Component.text("Placed marker pillar at village center.",
                NamedTextColor.GRAY));
        }

        LOGGER.warning(String.format("[GEN-QUEUE] Failed to place structures for village '%s'",
            currentState.villageName));

        currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.FAILED);
        }

        private boolean shouldRetrySpawnAfterZeroPlacement() {
        if (currentState == null || currentState.spawnRetryAttempts >= MAX_SPAWN_RETRY_ATTEMPTS) {
            return false;
        }
        if (currentState.villageId == null || currentState.world == null || currentState.suitableLocation == null) {
            return false;
        }

        Optional<VillageMetadataStore.PlacementFailureSummary> summaryOpt =
            metadataStore.getLastPlacementFailureSummary(currentState.villageId);
        VillageMetadataStore.PlacementFailureSummary summary = summaryOpt.orElse(null);
        if (summary == null || (summary.steep <= 0 && summary.blocked <= 0)) {
            return false;
        }

        String reason = resolveRetryReason(summary);
        Location origin = currentState.suitableLocation;
        int attemptNumber = currentState.spawnRetryAttempts + 1;

        LOGGER.warning(String.format("[STRUCT][SPAWN-RETRY] attempt=%d reason=%s origin=(%d,%d,%d)",
            attemptNumber,
            reason,
            origin.getBlockX(),
            origin.getBlockY(),
            origin.getBlockZ()));

        metadataStore.removeVillage(currentState.villageId);
        plugin.getVillageService().removeVillage(currentState.villageId);

        currentState.spawnRetryAttempts = attemptNumber;
        currentState.spawnRetryOrigin = computeRetryOrigin(currentState.world, origin, attemptNumber);
        resetPlacementStateForRetry();

        currentRequest.setCurrentPhase(CommandGenerationRequest.GenerationPhase.TERRAIN_SEARCH);
        currentRequest.sendMessage(Component.text("Retrying terrain search after zero-placement...", NamedTextColor.GRAY));
        return true;
        }

        private Location computeRetryOrigin(World world, Location baseOrigin, int attemptNumber) {
        int baseRadius = Math.max(1, plugin.getSpawnProximityRadius());
        long seed = world.getSeed() + (long) attemptNumber * 1640531513L;
        java.util.Random random = new java.util.Random(seed);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int radius = baseRadius + (attemptNumber * baseRadius);
        int offsetX = (int) Math.round(Math.cos(angle) * radius);
        int offsetZ = (int) Math.round(Math.sin(angle) * radius);
        return new Location(world,
            baseOrigin.getBlockX() + offsetX,
            baseOrigin.getBlockY(),
            baseOrigin.getBlockZ() + offsetZ);
        }

        private void resetPlacementStateForRetry() {
        currentState.suitableLocation = null;
        currentState.placementFuture = null;
        currentState.preloadFuture = null;
        currentState.preloadResult = null;
        currentState.chunkTicketsApplied = false;
        releaseChunkTickets();
        }

        private String resolveRetryReason(VillageMetadataStore.PlacementFailureSummary summary) {
        if (summary.steep >= summary.blocked && summary.steep > 0) {
            return "steep";
        }
        if (summary.blocked > 0) {
            return "blocked";
        }
        return "unknown";
        }
}
