package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.cultures.CultureService;
import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.model.PlacementReceipt;
import com.davisodom.villageoverhaul.model.VolumeMask;
import com.davisodom.villageoverhaul.villages.VillagePlacementService;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.PathService;
import com.davisodom.villageoverhaul.worldgen.StructureService;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import com.davisodom.villageoverhaul.worldgen.TerrainClassifier;
import com.davisodom.villageoverhaul.worldgen.impl.PathEmitter;
import com.davisodom.villageoverhaul.worldgen.impl.PathServiceImpl;
import com.davisodom.villageoverhaul.worldgen.impl.StructureServiceImpl;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Implementation of village placement service with integrated structure seating.
 */
public class VillagePlacementServiceImpl implements VillagePlacementService {
    
    private static final Logger LOGGER = Logger.getLogger(VillagePlacementServiceImpl.class.getName());
    
    // Minimum spacing between buildings (blocks)
    // This is applied on BOTH sides, so total gap = 2 * spacing = 4 blocks
    // Default value if no configuration provided
    private static final int DEFAULT_BUILDING_SPACING = 2;
    
    // Default minimum spacing between villages (border-to-border, blocks)
    private static final int DEFAULT_VILLAGE_SPACING = 200;
    
    // Configured spacing values (loaded from plugin config)
    private final int minBuildingSpacing;
    private final int minVillageSpacing;
    
    // Structure service for building placement
    private final StructureService structureService;
    
    // Path service for connecting buildings
    private final PathService pathService;
    
    // Path emitter for block placement
    private final PathEmitter pathEmitter;
    
    // Metadata storage
    private final VillageMetadataStore metadataStore;
    
    // Culture service for structure selection
    private final CultureService cultureService;
    
    // Main building selector
    private final MainBuildingSelector mainBuildingSelector;
    
    // In-memory cache of villages (villageId -> buildings)
    private final Map<UUID, List<Building>> villageBuildings = new HashMap<>();
    
    /**
     * Constructor for testing without plugin reference (uses procedural structures).
     */
    public VillagePlacementServiceImpl(VillageMetadataStore metadataStore, CultureService cultureService) {
        this.structureService = new StructureServiceImpl();
        this.pathService = new PathServiceImpl(metadataStore);
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.cultureService = cultureService;
        this.mainBuildingSelector = new MainBuildingSelector(LOGGER, cultureService);
        this.minBuildingSpacing = DEFAULT_BUILDING_SPACING;
        this.minVillageSpacing = DEFAULT_VILLAGE_SPACING;
    }
    
    /**
     * Constructor with plugin reference for loading schematics from disk.
     * 
     * @param plugin Plugin instance (provides data folder)
     * @param metadataStore Metadata storage
     * @param cultureService Culture service for main building selection
     */
    public VillagePlacementServiceImpl(Plugin plugin, VillageMetadataStore metadataStore, CultureService cultureService) {
        this.structureService = new StructureServiceImpl(plugin.getDataFolder());
        this.pathService = new PathServiceImpl(metadataStore);
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.cultureService = cultureService;
        this.mainBuildingSelector = new MainBuildingSelector(LOGGER, cultureService);
        // Load spacing from plugin config
        this.minBuildingSpacing = plugin.getConfig().getInt("village.minBuildingSpacing", DEFAULT_BUILDING_SPACING);
        this.minVillageSpacing = plugin.getConfig().getInt("village.minVillageSpacing", DEFAULT_VILLAGE_SPACING);
    }
    
    /**
     * Constructor with custom structure service (for dependency injection).
     */
    public VillagePlacementServiceImpl(StructureService structureService, VillageMetadataStore metadataStore, CultureService cultureService) {
        this.structureService = structureService;
        this.pathService = new PathServiceImpl(metadataStore);
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.cultureService = cultureService;
        this.mainBuildingSelector = new MainBuildingSelector(LOGGER, cultureService);
        this.minBuildingSpacing = DEFAULT_BUILDING_SPACING;
        this.minVillageSpacing = DEFAULT_VILLAGE_SPACING;
    }
    
    @Override
    public Optional<UUID> placeVillage(World world, Location origin, String cultureId, long seed) {
        LOGGER.info(String.format("[STRUCT] Begin village placement: culture=%s, origin=%s, seed=%d, minBuildingSpacing=%d, minVillageSpacing=%d",
                cultureId, origin, seed, minBuildingSpacing, minVillageSpacing));
        
        // Check if this is the first village (Constitution v1.5.0, Principle XII - Spawn Proximity)
        boolean isFirst = isFirstVillage(world);
        
        if (isFirst) {
            // First village: verify spawn proximity (not exact spawn, within configured radius)
            Location spawn = world.getSpawnLocation();
            int spawnDistance = Math.abs(origin.getBlockX() - spawn.getBlockX()) + 
                               Math.abs(origin.getBlockZ() - spawn.getBlockZ());
            
            LOGGER.info(String.format("[STRUCT] First village: spawn distance=%d blocks (spawn at %s)",
                    spawnDistance, formatLocation(spawn)));
            
            // Note: Spawn proximity enforcement happens in terrain search (GenerateCommand/VillageWorldgenAdapter)
            // This is just logging for observability
        } else {
            // Subsequent villages: log nearest-neighbor distance
            int distanceToNearest = getDistanceToNearestVillage(origin);
            LOGGER.info(String.format("[STRUCT] Subsequent village: nearest existing village distance=%d blocks",
                    distanceToNearest));
        }
        
        // Check inter-village spacing (Constitution v1.5.0, Principle XII)
        // Reject sites within minVillageSpacing of any existing village border
        InterVillageSpacingResult spacingResult = checkInterVillageSpacingDetailed(origin, minVillageSpacing);
        if (!spacingResult.acceptable) {
            LOGGER.warning(String.format("[STRUCT] Village placement rejected: site at %s violates minVillageSpacing=%d " +
                    "(rejectedVillageSites.minDistance=%d, existingVillage=%s)",
                    formatLocation(origin), minVillageSpacing, spacingResult.actualDistance, spacingResult.violatingVillageId));
            return Optional.empty();
        }
        
        // NOTE: Site validation already performed by VillageWorldgenAdapter terrain search
        // Skip redundant validation here to avoid false negatives
        
        UUID villageId = UUID.randomUUID();
        
        // Register village in metadata store AFTER spacing validation passes
        // This prevents the village from rejecting itself during spacing checks
        metadataStore.registerVillage(villageId, cultureId, origin, seed);
        
        // TODO: Load culture definition and structure set
        // For now, use culture-appropriate structures based on loaded schematics
        List<String> structureIds = getCultureStructures(cultureId);
        
        List<Building> placedBuildings = new ArrayList<>();
        
        // R009: Use VolumeMasks for overlap detection instead of legacy Footprints
        // Initialize SurfaceSolver for ground finding
        List<VolumeMask> existingMasks = metadataStore.getVolumeMasks(villageId);
        SurfaceSolver surfaceSolver = new SurfaceSolver(world, existingMasks);
        
        // Place buildings one at a time with dynamic collision detection
        // Use grid-based spiral search for each building to find non-overlapping spots
        for (int i = 0; i < structureIds.size(); i++) {
            String structureId = structureIds.get(i);
            
            // Get structure dimensions
            Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
            if (!dimensions.isPresent()) {
                LOGGER.warning(String.format("[STRUCT] Structure '%s' dimensions not found, skipping", structureId));
                continue;
            }
            
            int[] dims = dimensions.get();
            int width = dims[0];
            int depth = dims[2];
            
            // Derive building-specific seed
            long buildingSeed = seed + i;
            
            // Find suitable position with integrated terrain/spacing/overlap checks
            // R009: Use SurfaceSolver and VolumeMasks
            Optional<Location> placementLocation = findSuitablePlacementPosition(
                    world, origin, width, depth, 
                    metadataStore.getVolumeMasks(villageId), surfaceSolver);
            
            if (!placementLocation.isPresent()) {
                LOGGER.warning(String.format("[STRUCT] Could not find suitable position for '%s'", structureId));
                continue;
            }
            
            Location buildingLocation = placementLocation.get();
            
            // R001: Place structure and get PlacementReceipt with ground-truth bounds and corner samples
            Optional<PlacementReceipt> receiptOpt = structureService.placeStructureAndGetReceipt(
                    structureId, world, buildingLocation, buildingSeed, villageId);
            
            if (receiptOpt.isPresent()) {
                PlacementReceipt receipt = receiptOpt.get();
                
                // Store receipt for persistence
                metadataStore.addPlacementReceipt(villageId, receipt);
                
                // R002: Create and store VolumeMask from receipt
                VolumeMask volumeMask = VolumeMask.fromReceipt(receipt);
                metadataStore.addVolumeMask(villageId, volumeMask);
                
                // Update SurfaceSolver with new mask for subsequent placements
                // (Re-creating solver is cheap enough for per-building frequency)
                surfaceSolver = new SurfaceSolver(world, metadataStore.getVolumeMasks(villageId));
                
                // Create building metadata
                Building building = new Building.Builder()
                        .villageId(villageId)
                        .structureId(structureId)
                        .origin(new Location(world, receipt.getOriginX(), receipt.getOriginY(), receipt.getOriginZ()))
                        .dimensions(receipt.getEffectiveWidth(), receipt.getHeight(), receipt.getEffectiveDepth())
                        .build();
                
                placedBuildings.add(building);
                
                LOGGER.info(String.format("[STRUCT] Placed %s at (%d,%d,%d) rotation=%d°", 
                        structureId, 
                        receipt.getOriginX(), receipt.getOriginY(), receipt.getOriginZ(),
                        receipt.getRotation()));
            } else {
                LOGGER.warning(String.format("[STRUCT] Failed to place building %s", structureId));
            }
        }
        
        if (placedBuildings.isEmpty()) {
            LOGGER.warning(String.format("[STRUCT] Abort: No buildings placed for village at %s", origin));
            return Optional.empty();
        }
        
        // Store village buildings
        for (Building building : placedBuildings) {
            metadataStore.addBuilding(villageId, building);
        }
        
        // Designate main building using culture-specific structure (T023)
        Optional<UUID> mainBuildingId = mainBuildingSelector.selectMainBuilding(cultureId, placedBuildings);
        if (mainBuildingId.isPresent()) {
            metadataStore.setMainBuilding(villageId, mainBuildingId.get());
            LOGGER.info(String.format("[STRUCT] Main building designated for village %s: %s", 
                villageId, mainBuildingId.get()));
        } else {
            LOGGER.warning(String.format("[STRUCT] Could not designate main building for village %s (culture: %s)", 
                villageId, cultureId));
        }
        
        // Generate path network connecting buildings to the main building
        if (placedBuildings.size() > 1) {
            LOGGER.info(String.format("[STRUCT] Generating path network for village %s", villageId));
            
            // T021c: Calculate entrance points for all buildings
            // R009: Use entrance locations from PlacementReceipts
            List<Location> buildingEntrances = new ArrayList<>();
            Location mainBuildingEntrance = null;
            
            List<PlacementReceipt> receipts = metadataStore.getPlacementReceipts(villageId);
            Map<String, PlacementReceipt> receiptMap = new HashMap<>();
            for (PlacementReceipt r : receipts) {
                // Map by structure ID is risky if duplicates allowed, but here we assume 1:1 for now
                // Better to map by building ID if available, but receipt doesn't have it yet
                // We can match by location
                receiptMap.put(r.getStructureId() + "@" + r.getOriginX() + "," + r.getOriginZ(), r);
            }
            
            for (Building building : placedBuildings) {
                // Find matching receipt
                String key = building.getStructureId() + "@" + building.getOrigin().getBlockX() + "," + building.getOrigin().getBlockZ();
                PlacementReceipt receipt = receiptMap.get(key);
                
                if (receipt != null) {
                    Location entrance = new Location(world, receipt.getEntranceX(), receipt.getEntranceY(), receipt.getEntranceZ());
                    buildingEntrances.add(entrance);
                    
                    if (mainBuildingId.isPresent() && building.getBuildingId().equals(mainBuildingId.get())) {
                        mainBuildingEntrance = entrance;
                    }
                } else {
                    LOGGER.warning(String.format("[STRUCT] Missing receipt for building %s, skipping path connection",
                            building.getBuildingId()));
                }
            }
            
            // Fallback: if no main building was designated, use first building's entrance
            if (mainBuildingEntrance == null && !buildingEntrances.isEmpty()) {
                mainBuildingEntrance = buildingEntrances.get(0);
            }
            
            // Generate path network (A* pathfinding) using entrance points
            boolean pathSuccess = pathService.generatePathNetwork(
                    world, 
                    villageId, 
                    buildingEntrances,
                    mainBuildingEntrance,
                    seed
            );
            
            if (pathSuccess) {
                // Place path blocks in the world
                List<List<Block>> pathNetwork = pathService.getVillagePathNetwork(villageId);
                int totalPathBlocks = 0;
                
                // R008: Get volume masks for path placement checks
                List<VolumeMask> masks = metadataStore.getVolumeMasks(villageId);
                
                for (List<Block> pathSegment : pathNetwork) {
                    int placed = pathEmitter.emitPathWithSmoothing(world, pathSegment, cultureId, masks);
                    totalPathBlocks += placed;
                }
                
                LOGGER.info(String.format("[STRUCT] Path network complete: village=%s, paths=%d, blocks=%d",
                        villageId, pathNetwork.size(), totalPathBlocks));
            } else {
                LOGGER.warning(String.format("[STRUCT] Path network generation failed for village %s", villageId));
            }
        } else {
            LOGGER.fine("[STRUCT] Only one building, skipping path generation");
        }
        
        LOGGER.info(String.format("[STRUCT] Village placement complete: villageId=%s, buildings=%d",
                villageId, placedBuildings.size()));
        
        return Optional.of(villageId);
    }
    
    @Override
    public boolean validateSite(World world, Location origin, int radius) {
        // Check for existing structures in the area
        if (hasCollision(world, origin, radius)) {
            LOGGER.fine(String.format("[STRUCT] Site validation failed: collision detected at %s", origin));
            return false;
        }
        
        // Check terrain flatness within the radius
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        
        for (int x = -radius; x <= radius; x += 8) {
            for (int z = -radius; z <= radius; z += 8) {
                int y = world.getHighestBlockYAt(origin.getBlockX() + x, origin.getBlockZ() + z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        
        int yVariation = maxY - minY;
        boolean flatEnough = yVariation <= 20; // Allow up to 20 blocks variation for village area
        
        if (!flatEnough) {
            LOGGER.fine(String.format("[STRUCT] Site validation failed: too much Y variation (%d blocks)", yVariation));
        }
        
        return flatEnough;
    }
    
    @Override
    public Optional<Building> placeBuilding(World world, Location location, String structureId, UUID villageId, long seed) {
        LOGGER.fine(String.format("[STRUCT] Begin building placement: structure=%s, location=%s, seed=%d",
                structureId, location, seed));
        
        // Get structure dimensions
        Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
        
        if (!dimensions.isPresent()) {
            LOGGER.warning(String.format("[STRUCT] Structure '%s' not found", structureId));
            return Optional.empty();
        }
        
        // T021b: Use new method that returns BOTH the final re-seated location AND rotation
        // This is critical for calculating correct building footprints - rotation swaps width/depth
        Optional<com.davisodom.villageoverhaul.worldgen.PlacementResult> placementResult = 
                structureService.placeStructureAndGetResult(structureId, world, location, seed);
        
        if (!placementResult.isPresent()) {
            LOGGER.warning(String.format("[STRUCT] Failed to place structure '%s' at %s", structureId, location));
            return Optional.empty();
        }
        
        // Create building metadata using the ACTUAL placed location (not the requested location)
        int[] dims = dimensions.get();
        Building building = new Building.Builder()
                .villageId(villageId)
                .structureId(structureId)
                .origin(placementResult.get().getActualLocation()) // Use actual final location, not requested location
                .dimensions(dims[0], dims[1], dims[2])
                .build();
        
        LOGGER.fine(String.format("[STRUCT] Building placed successfully: buildingId=%s at actual location=%s, rotation=%d°", 
                building.getBuildingId(), placementResult.get().getActualLocation(), 
                placementResult.get().getRotationDegrees()));
        
        return Optional.of(building);
    }
    
    @Override
    public List<Building> getVillageBuildings(UUID villageId) {
        // Try cache first
        List<Building> buildings = villageBuildings.get(villageId);
        
        if (buildings != null) {
            return new ArrayList<>(buildings);
        }
        
        // Load from metadata store
        buildings = metadataStore.getVillageBuildings(villageId);
        
        if (!buildings.isEmpty()) {
            villageBuildings.put(villageId, buildings);
        }
        
        return buildings;
    }
    
    @Override
    public boolean hasCollision(World world, Location location, int radius) {
        // Check all loaded villages for collision
        for (Map.Entry<UUID, List<Building>> entry : villageBuildings.entrySet()) {
            for (Building building : entry.getValue()) {
                Location buildingLoc = building.getOrigin();
                
                if (buildingLoc.getWorld().equals(world)) {
                    double distance = buildingLoc.distance(location);
                    
                    if (distance < radius) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    @Override
    public boolean removeVillage(UUID villageId) {
        List<Building> buildings = villageBuildings.remove(villageId);
        
        if (buildings == null) {
            LOGGER.warning(String.format("[STRUCT] Village not found: %s", villageId));
            return false;
        }
        
        // Remove from metadata store
        metadataStore.removeVillage(villageId);
        
        LOGGER.info(String.format("[STRUCT] Removed village %s (%d buildings)", villageId, buildings.size()));
        return true;
    }
    
    /**
     * Get structure IDs for a given culture.
     * 
     * CRITICAL: The main building MUST always be placed first to ensure every village has it.
     * Other buildings are randomly selected from the culture's structure set.
     * 
     * @param cultureId Culture ID to get structures for
     * @return List of structure IDs with main building first, followed by randomly selected others
     */
    private List<String> getCultureStructures(String cultureId) {
        Optional<CultureService.Culture> cultureOpt = cultureService.get(cultureId);
        if (!cultureOpt.isPresent()) {
            LOGGER.warning(String.format("[STRUCT] Culture '%s' not found, using empty structure list", cultureId));
            return Collections.emptyList();
        }
        
        CultureService.Culture culture = cultureOpt.get();
        List<String> structureSet = culture.getStructureSet();
        
        if (structureSet == null || structureSet.isEmpty()) {
            LOGGER.warning(String.format("[STRUCT] Culture '%s' has no structures defined", cultureId));
            return Collections.emptyList();
        }
        
        // Get main building structure ID (or default to first if not specified)
        String mainBuildingStructureId = culture.getMainBuildingStructureId();
        if (mainBuildingStructureId == null || mainBuildingStructureId.isEmpty()) {
            mainBuildingStructureId = structureSet.get(0);
        }
        
        // Build result list: main building FIRST, then other structures
        List<String> result = new ArrayList<>();
        result.add(mainBuildingStructureId);
        
        // Add remaining structures (excluding the main building)
        List<String> otherStructures = new ArrayList<>();
        for (String structureId : structureSet) {
            if (!structureId.equals(mainBuildingStructureId)) {
                otherStructures.add(structureId);
            }
        }
        
        // Shuffle other structures for variety (but keep main building first!)
        Collections.shuffle(otherStructures, new Random());
        result.addAll(otherStructures);
        
        LOGGER.info(String.format("[STRUCT] Structure placement order for culture '%s': main building '%s' + %d others", 
            cultureId, mainBuildingStructureId, otherStructures.size()));
        
        return result;
    }
    
    /**
     * Find suitable placement position with integrated terrain, spacing, and overlap checks.
     * Uses spiral search pattern from origin.
     * R009: Uses SurfaceSolver for ground finding and VolumeMasks for overlap checks.
     */
    private Optional<Location> findSuitablePlacementPosition(
            World world, Location origin, int width, int depth,
            List<VolumeMask> existingMasks, SurfaceSolver surfaceSolver) {
        
        final int maxRadius = 100;
        final int gridSize = 8;
        
        // Spiral search pattern: start at origin, expand outward
        for (int radius = 0; radius <= maxRadius; radius += gridSize) {
            // For each ring, try all 4 quadrants
            for (int dx = -radius; dx <= radius; dx += gridSize) {
                for (int dz = -radius; dz <= radius; dz += gridSize) {
                    // Skip interior points (already checked in previous rings)
                    if (radius > 0 && Math.abs(dx) < radius && Math.abs(dz) < radius) {
                        continue;
                    }
                    
                    int candidateX = origin.getBlockX() + dx;
                    int candidateZ = origin.getBlockZ() + dz;
                    
                    // R009: Use SurfaceSolver to find ground level
                    // This finds the highest solid block NOT inside any existing mask
                    int candidateY = surfaceSolver.getSurfaceHeight(candidateX, candidateZ);
                    
                    // Check overlap with existing masks (including spacing buffer)
                    // Since we don't know final rotation yet, use conservative bounds:
                    // candidate could extend maxDim in any direction from its origin
                    int maxDim = Math.max(width, depth);
                    int buffer = minBuildingSpacing;
                    
                    // Conservative candidate footprint: origin could place structure extending maxDim in all directions
                    // (rotation determines actual extent, so we check worst case)
                    int candidateRadius = maxDim + buffer;
                    
                    boolean overlaps = false;
                    for (VolumeMask mask : existingMasks) {
                        // Expand mask by candidate radius for overlap check
                        // If candidate origin is within (mask + candidateRadius), structures could overlap
                        int maskMinX = mask.getMinX() - candidateRadius;
                        int maskMaxX = mask.getMaxX() + candidateRadius;
                        int maskMinZ = mask.getMinZ() - candidateRadius;
                        int maskMaxZ = mask.getMaxZ() + candidateRadius;
                        
                        // Check if candidate origin is within expanded mask zone
                        if (candidateX >= maskMinX && candidateX <= maskMaxX &&
                            candidateZ >= maskMinZ && candidateZ <= maskMaxZ) {
                            overlaps = true;
                            break;
                        }
                    }

                    
                    if (overlaps) {
                        continue;
                    }
                    
                    // Found valid spot
                    return Optional.of(new Location(world, candidateX, candidateY, candidateZ));
                }
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Check if a proposed village location violates inter-village spacing requirements.
     * 
     * @param proposedOrigin Proposed village origin (center)
     * @param minDistance Minimum border-to-border distance required
     * @return true if spacing is acceptable, false if too close to an existing village
     */
    private boolean checkInterVillageSpacing(Location proposedOrigin, int minDistance) {
        // Create a temporary border for the proposed village at its origin (point)
        // We check the origin point against all existing village borders
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
                proposedOrigin.getBlockX(), proposedOrigin.getBlockX(),
                proposedOrigin.getBlockZ(), proposedOrigin.getBlockZ()
        );
        
        // Check against all existing villages in the same world
        for (VillageMetadataStore.VillageMetadata existingVillage : metadataStore.getAllVillages()) {
            // Skip villages in different worlds
            if (!existingVillage.getOrigin().getWorld().equals(proposedOrigin.getWorld())) {
                continue;
            }
            
            VillageMetadataStore.VillageBorder existingBorder = existingVillage.getBorder();
            
            // Check if borders are within minimum distance
            if (proposedBorder.isWithinDistance(existingBorder, minDistance)) {
                int actualDistance = proposedBorder.getDistanceTo(existingBorder);
                LOGGER.fine(String.format("[STRUCT] Inter-village spacing violation: proposed=%s, existing=%s (village=%s), distance=%d, required=%d",
                        formatLocation(proposedOrigin), existingBorder, existingVillage.getVillageId(), actualDistance, minDistance));
                return false;
            }
        }
        
        return true; // No violations found
    }
    
    /**
     * Check inter-village spacing with detailed metrics for observability.
     * (Constitution v1.5.0, Principle XII - Observability)
     * 
     * @param proposedOrigin Proposed village origin (center)
     * @param minDistance Minimum border-to-border distance required
     * @return InterVillageSpacingResult with acceptance status and metrics
     */
    private InterVillageSpacingResult checkInterVillageSpacingDetailed(Location proposedOrigin, int minDistance) {
        // Create a temporary border for the proposed village at its origin (point)
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
                proposedOrigin.getBlockX(), proposedOrigin.getBlockX(),
                proposedOrigin.getBlockZ(), proposedOrigin.getBlockZ()
        );
        
        // Check against all existing villages in the same world
        for (VillageMetadataStore.VillageMetadata existingVillage : metadataStore.getAllVillages()) {
            // Skip villages in different worlds
            if (!existingVillage.getOrigin().getWorld().equals(proposedOrigin.getWorld())) {
                continue;
            }
            
            VillageMetadataStore.VillageBorder existingBorder = existingVillage.getBorder();
            
            // Check if borders are within minimum distance
            if (proposedBorder.isWithinDistance(existingBorder, minDistance)) {
                int actualDistance = proposedBorder.getDistanceTo(existingBorder);
                LOGGER.fine(String.format("[STRUCT] Inter-village spacing violation: proposed=%s, existing=%s (village=%s), distance=%d, required=%d",
                        formatLocation(proposedOrigin), existingBorder, existingVillage.getVillageId(), actualDistance, minDistance));
                return new InterVillageSpacingResult(false, actualDistance, existingVillage.getVillageId());
            }
        }
        
        return new InterVillageSpacingResult(true, Integer.MAX_VALUE, null); // No violations
    }
    
    /**
     * Check if this is the first village in the world.
     * 
     * @param world Target world
     * @return true if no villages exist in this world yet
     */
    private boolean isFirstVillage(World world) {
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (village.getOrigin().getWorld().equals(world)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Check if a location is within spawn proximity radius.
     * 
     * @param location Location to check
     * @param spawnProximityRadius Maximum radius from spawn
     * @return true if within radius (or radius is 0 to disable check)
     */
    private boolean isWithinSpawnProximity(Location location, int spawnProximityRadius) {
        if (spawnProximityRadius <= 0) {
            return true; // Spawn proximity disabled
        }
        
        Location spawn = location.getWorld().getSpawnLocation();
        int dx = Math.abs(location.getBlockX() - spawn.getBlockX());
        int dz = Math.abs(location.getBlockZ() - spawn.getBlockZ());
        int distance = dx + dz; // Manhattan distance
        
        return distance <= spawnProximityRadius;
    }
    
    /**
     * Calculate distance from a location to the nearest existing village border.
     * 
     * @param location Location to check
     * @return Distance to nearest village, or Integer.MAX_VALUE if no villages exist
     */
    private int getDistanceToNearestVillage(Location location) {
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
                location.getBlockX(), location.getBlockX(),
                location.getBlockZ(), location.getBlockZ()
        );
        
        int minDistance = Integer.MAX_VALUE;
        
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (!village.getOrigin().getWorld().equals(location.getWorld())) {
                continue;
            }
            
            int distance = proposedBorder.getDistanceTo(village.getBorder());
            minDistance = Math.min(minDistance, distance);
        }
        
        return minDistance;
    }
    
    /**
     * Format location for logging.
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
    
    /**
     * Calculate entrance point for a building (T021c).
     * Returns a ground-level location outside the building footprint based on rotation.
     * Entrance is positioned 1-2 blocks away from the building face in the direction the entrance faces.
     * CRITICAL: Accounts for WorldEdit rotation behavior where origin point represents different corners.
     * 
     * @param building Building with origin, dimensions, and rotation
     * @param placement PlacementResult containing actual rotation applied
     * @param world World to check ground level
     * @return Entrance location (ground level, outside footprint)
     */
    private Location calculateEntrancePoint(Building building, 
            com.davisodom.villageoverhaul.worldgen.PlacementResult placement, World world) {
        Location origin = building.getOrigin();
        int[] dims = building.getDimensions();
        int width = dims[0];
        int depth = dims[2];
        int rotationDegrees = placement.getRotationDegrees();
        
        // Calculate effective dimensions after rotation
        int effectiveWidth = placement.getEffectiveWidth(width, depth);
        int effectiveDepth = placement.getEffectiveDepth(width, depth);
        
        // CRITICAL: Calculate actual structure bounds based on WorldEdit rotation behavior
        // WorldEdit rotates clipboard around origin, changing which corner origin represents:
        // 0°: origin = NW corner (minX, minZ), extends +X, +Z
        // 90°: origin = NE corner (minX, maxZ), extends +X, -Z
        // 180°: origin = SE corner (maxX, maxZ), extends -X, -Z
        // 270°: origin = SW corner (maxX, minZ), extends -X, +Z
        
        int originX = origin.getBlockX();
        int originZ = origin.getBlockZ();
        
        int structureMinX, structureMaxX, structureMinZ, structureMaxZ;
        
        switch (rotationDegrees) {
            case 0: // Origin is NW corner
                structureMinX = originX;
                structureMaxX = originX + effectiveWidth - 1;
                structureMinZ = originZ;
                structureMaxZ = originZ + effectiveDepth - 1;
                break;
            case 90: // Origin is NE corner
                structureMinX = originX;
                structureMaxX = originX + effectiveWidth - 1;
                structureMinZ = originZ - effectiveDepth + 1;
                structureMaxZ = originZ;
                break;
            case 180: // Origin is SE corner
                structureMinX = originX - effectiveWidth + 1;
                structureMaxX = originX;
                structureMinZ = originZ - effectiveDepth + 1;
                structureMaxZ = originZ;
                break;
            case 270: // Origin is SW corner
                structureMinX = originX - effectiveWidth + 1;
                structureMaxX = originX;
                structureMinZ = originZ;
                structureMaxZ = originZ + effectiveDepth - 1;
                break;
            default:
                LOGGER.warning(String.format("[PATH] Invalid rotation %d° for building %s, using origin",
                        rotationDegrees, building.getBuildingId()));
                return origin.clone();
        }
        
        // Calculate entrance at center of appropriate face, 2 blocks outside structure bounds
        // Default entrance faces SOUTH (positive Z) at 0° rotation
        // Rotation transforms: 0°=South, 90°=West, 180°=North, 270°=East
        
        int entranceX, entranceZ;
        
        switch (rotationDegrees) {
            case 0: // South face (positive Z) - outside maxZ
                entranceX = (structureMinX + structureMaxX) / 2;
                entranceZ = structureMaxZ + 2;
                break;
            case 90: // West face (negative X) - outside minX
                entranceX = structureMinX - 2;
                entranceZ = (structureMinZ + structureMaxZ) / 2;
                break;
            case 180: // North face (negative Z) - outside minZ
                entranceX = (structureMinX + structureMaxX) / 2;
                entranceZ = structureMinZ - 2;
                break;
            case 270: // East face (positive X) - outside maxX
                entranceX = structureMaxX + 2;
                entranceZ = (structureMinZ + structureMaxZ) / 2;
                break;
            default:
                LOGGER.warning(String.format("[PATH] Invalid rotation %d° for building %s, using origin",
                        rotationDegrees, building.getBuildingId()));
                return origin.clone();
        }
        
        // CRITICAL: Find actual ground level OUTSIDE the building footprint
        // Scan DOWN from building base to find actual terrain
        // Since entrance X/Z is already outside footprint (2 blocks away),
        // we just need to find the first solid block below building base
        int buildingMinY = origin.getBlockY();
        int groundY = buildingMinY - 1; // Start scan from just below building
        
        // Scan down to find first solid block (actual terrain)
        for (int checkY = groundY; checkY >= buildingMinY - 10 && checkY >= world.getMinHeight(); checkY--) {
            Block block = world.getBlockAt(entranceX, checkY, entranceZ);
            if (block.getType().isSolid()) {
                groundY = checkY;
                break;
            }
        }
        
        Location entranceLocation = new Location(world, entranceX, groundY, entranceZ);
        
        LOGGER.info(String.format("[PATH] Building entrance: %s at (%d,%d,%d) facing %s (rotation=%d°) [buildingMinY=%d] [structure bounds: X[%d-%d] Z[%d-%d]]",
                building.getBuildingId(), entranceX, groundY, entranceZ, 
                getDirectionName(rotationDegrees), rotationDegrees, buildingMinY,
                structureMinX, structureMaxX, structureMinZ, structureMaxZ));
        
        return entranceLocation;
    }
    
    /**
     * Find ground level at given X,Z coordinates, starting from hint Y.
     * Scans down to find first solid block suitable for path placement.
     * 
     * DEPRECATED: Use findGroundLevelBelowBuilding for entrance calculations to avoid
     * finding building floor blocks.
     * 
     * @param world World to scan
     * @param x X coordinate
     * @param z Z coordinate
     * @param hintY Starting Y coordinate (typically building origin Y)
     * @return Ground level Y coordinate
     */
    private int findGroundLevel(World world, int x, int z, int hintY) {
        // Scan down from hint Y to find solid ground
        for (int y = hintY; y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            // Found solid ground that's suitable for walking
            if (type.isSolid() && !type.isAir()) {
                // Return Y+1 (on top of the solid block)
                return y + 1;
            }
        }
        
        // Fallback to hint Y if no solid ground found
        return hintY;
    }
    
    /**
     * Find ground level BELOW a building footprint.
     * Scans down from maxY to find natural terrain, skipping all building/terraformed blocks.
     * CRITICAL: Must skip foundation/grading blocks that extend outside footprint.
     * 
     * @param world World to scan
     * @param x X coordinate
     * @param z Z coordinate
     * @param maxY Maximum Y to start scan (should be buildingMinY - 5 to skip foundation)
     * @return Ground level Y coordinate on natural terrain, guaranteed <= buildingMinY
     */
    private int findGroundLevelBelowBuilding(World world, int x, int z, int maxY) {
        int firstNaturalY = -1;
        
        // Scan down from maxY to find natural solid ground (not building blocks)
        for (int y = maxY; y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            // Skip non-solid blocks (air, water, etc.)
            if (!type.isSolid() || type.isAir()) {
                continue;
            }
            
            // Check if this is natural ground (not building materials)
            if (isNaturalGroundForEntrance(type)) {
                firstNaturalY = y + 1; // Y+1 = standing on top of block
                break;
            }
            
            // If we hit a non-natural solid block, continue scanning down
            // (might be building foundation blocks or terraformed grading)
        }
        
        // If we found natural terrain, return it
        if (firstNaturalY > 0) {
            return firstNaturalY;
        }
        
        // Fallback: scan up from bedrock if we somehow missed natural terrain
        for (int y = world.getMinHeight(); y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            if (type.isSolid() && !type.isAir() && isNaturalGroundForEntrance(type)) {
                return y + 1;
            }
        }
        
        // Last resort: return maxY (should rarely happen)
        LOGGER.warning(String.format("[PATH] Could not find natural ground at (%d,%d), using fallback Y=%d",
                x, z, maxY));
        return maxY;
    }
    
    /**
     * Check if a material is natural ground suitable for building entrances.
     * More restrictive than path traversal - excludes building materials.
     * 
     * @param material Material to check
     * @return true if natural ground, false if building material or unsuitable
     */
    private boolean isNaturalGroundForEntrance(Material material) {
        // Only allow natural terrain blocks for entrance ground
        return material == Material.GRASS_BLOCK ||
               material == Material.DIRT ||
               material == Material.COARSE_DIRT ||
               material == Material.PODZOL ||
               material == Material.MYCELIUM ||
               material == Material.SAND ||
               material == Material.RED_SAND ||
               material == Material.GRAVEL ||
               material == Material.CLAY;
        // NOTE: Excludes STONE, COBBLESTONE, PLANKS, etc. (building materials)
    }
    
    /**
     * Get direction name for rotation angle.
     */
    private String getDirectionName(int rotationDegrees) {
        switch (rotationDegrees) {
            case 0: return "South";
            case 90: return "West";
            case 180: return "North";
            case 270: return "East";
            default: return "Unknown";
        }
    }
    
    // ==================== Inner Classes ====================
    
    /**
     * Result of inter-village spacing check with observability metrics.
     */
    private static class InterVillageSpacingResult {
        final boolean acceptable;
        final int actualDistance;
        final UUID violatingVillageId;
        
        InterVillageSpacingResult(boolean acceptable, int actualDistance, UUID violatingVillageId) {
            this.acceptable = acceptable;
            this.actualDistance = actualDistance;
            this.violatingVillageId = violatingVillageId;
        }
    }
    
    private static class GridPosition {
        final int x;
        final int z;
        
        GridPosition(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
    
    /**
     * Footprint of a placed structure (including spacing buffer).
     */
    private static class Footprint {
        final int x;
        final int z;
        final int width;
        final int depth;
        
        Footprint(int x, int z, int width, int depth) {
            this.x = x;
            this.z = z;
            this.width = width;
            this.depth = depth;
        }
    }
    
    /**
     * Tracks rejection reasons for placement attempts.
     * Used to provide detailed debug logging per Constitution v1.4.0, Principle XII.
     */
    private static class PlacementRejectionTracker {
        int terrainRejections = 0;
        int spacingRejections = 0;
        int overlapRejections = 0;
        int totalAttempts = 0;
        
        // Detailed terrain breakdown
        int fluidRejections = 0;
        int steepRejections = 0;
        int blockedRejections = 0;
        
        void recordAttempt() {
            totalAttempts++;
        }
        
        void recordTerrainRejection(TerrainClassifier.ClassificationResult terrainResult) {
            terrainRejections++;
            fluidRejections += terrainResult.fluid;
            steepRejections += terrainResult.steep;
            blockedRejections += terrainResult.blocked;
        }
        
        void recordSpacingRejection() {
            spacingRejections++;
        }
        
        void recordOverlapRejection() {
            overlapRejections++;
        }
        
        @Override
        public String toString() {
            return String.format("attempts=%d, rejected: terrain=%d (fluid=%d, steep=%d, blocked=%d), spacing=%d, overlap=%d",
                    totalAttempts, terrainRejections, fluidRejections, steepRejections, blockedRejections, 
                    spacingRejections, overlapRejections);
        }
        
        /**
         * Calculate average rejected attempts.
         */
        double getAverageRejectedAttempts() {
            int totalRejections = terrainRejections + spacingRejections + overlapRejections;
            return totalAttempts > 0 ? (double) totalRejections / totalAttempts : 0.0;
        }
    }
}
