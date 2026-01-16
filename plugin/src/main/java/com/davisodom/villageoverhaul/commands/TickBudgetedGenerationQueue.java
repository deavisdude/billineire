package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillagePlacementService;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.impl.VillagePlacementServiceImpl;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
        
        request.setCurrentPhase(CommandGenerationRequest.GenerationPhase.TERRAIN_SEARCH);
        request.sendMessage(Component.text("Starting village generation for '" + request.getVillageName() + "'...", 
                NamedTextColor.GRAY));
        
        currentState = new GenerationState();
        currentState.world = request.getOrigin().getWorld();
        currentState.searchOrigin = request.getOrigin();
        
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
        
        if (isFirstVillage && spawnProximityRadius > 0) {
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
        
        // Create village
        Village village = plugin.getVillageService().createVillage(
                currentRequest.getCultureId(),
                currentRequest.getVillageName(),
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        );
        
        currentState.villageId = village.getId();
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
            Optional<UUID> result = currentState.placementFuture.join();
            
            if (result.isPresent()) {
                // Success
                int buildingCount = metadataStore.getVillageBuildings(currentState.villageId).size();
                
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
                
            } else {
                // Failed
                currentRequest.sendMessage(Component.text("Failed to place structures for village '" + 
                        currentState.villageName + "'", NamedTextColor.RED));
                currentRequest.sendMessage(Component.text("Check server logs for details.", NamedTextColor.GRAY));
                
                // Place marker if allowed
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
        CompletableFuture<Optional<UUID>> future = new CompletableFuture<>();
        
        // Schedule placement on main server thread (required for block modifications)
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Optional<UUID> result = currentState.placementService.placeVillage(
                        currentState.world,
                        currentState.suitableLocation,
                        currentRequest.getCultureId(),
                        currentState.villageSeed
                );
                future.complete(result);
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
    
    /**
     * Format location for display.
     */
    private String formatLocation(Location loc) {
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
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
        VillagePlacementService placementService;
        CompletableFuture<Optional<UUID>> placementFuture;
    }
}
