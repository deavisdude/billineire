package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.villages.VillagePlacementService;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.PathService;
import com.davisodom.villageoverhaul.worldgen.StructureService;
import com.davisodom.villageoverhaul.worldgen.TerrainClassifier;
import com.davisodom.villageoverhaul.worldgen.impl.PathEmitter;
import com.davisodom.villageoverhaul.worldgen.impl.PathServiceImpl;
import com.davisodom.villageoverhaul.worldgen.impl.StructureServiceImpl;
import org.bukkit.Location;
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
    
    // In-memory cache of villages (villageId -> buildings)
    private final Map<UUID, List<Building>> villageBuildings = new HashMap<>();
    
    /**
     * Constructor for testing without plugin reference (uses procedural structures).
     */
    public VillagePlacementServiceImpl(VillageMetadataStore metadataStore) {
        this.structureService = new StructureServiceImpl();
        this.pathService = new PathServiceImpl();
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.minBuildingSpacing = DEFAULT_BUILDING_SPACING;
        this.minVillageSpacing = DEFAULT_VILLAGE_SPACING;
    }
    
    /**
     * Constructor with plugin reference for loading schematics from disk.
     * 
     * @param plugin Plugin instance (provides data folder)
     * @param metadataStore Metadata storage
     */
    public VillagePlacementServiceImpl(Plugin plugin, VillageMetadataStore metadataStore) {
        this.structureService = new StructureServiceImpl(plugin.getDataFolder());
        this.pathService = new PathServiceImpl();
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        // Load spacing from plugin config
        this.minBuildingSpacing = plugin.getConfig().getInt("village.minBuildingSpacing", DEFAULT_BUILDING_SPACING);
        this.minVillageSpacing = plugin.getConfig().getInt("village.minVillageSpacing", DEFAULT_VILLAGE_SPACING);
    }
    
    /**
     * Constructor with custom structure service (for dependency injection).
     */
    public VillagePlacementServiceImpl(StructureService structureService, VillageMetadataStore metadataStore) {
        this.structureService = structureService;
        this.pathService = new PathServiceImpl();
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
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
        
        // Track occupied footprints to prevent overlaps DURING placement
        List<Footprint> occupiedFootprints = new ArrayList<>();
        
        // Track rejection reasons for all placement attempts (Constitution v1.4.0, Principle XII)
        PlacementRejectionTracker villageRejectionTracker = new PlacementRejectionTracker();
        
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
            
            // Determine rotation for this building
            Random rotationRandom = new Random(buildingSeed);
            int rotationDegrees = rotationRandom.nextInt(4) * 90;
            
            // Calculate effective footprint after rotation
            int effectiveWidth, effectiveDepth;
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                effectiveWidth = depth;
                effectiveDepth = width;
            } else {
                effectiveWidth = width;
                effectiveDepth = depth;
            }
            
            // Find suitable position with integrated terrain/spacing/overlap checks
            PlacementResult placementResult = findSuitablePlacementPosition(
                    world, origin, effectiveWidth, effectiveDepth, 
                    occupiedFootprints, villageRejectionTracker);
            
            if (placementResult == null) {
                LOGGER.warning(String.format("[STRUCT] Could not find suitable position for '%s' after %d attempts", 
                        structureId, villageRejectionTracker.totalAttempts));
                continue;
            }
            
            Location buildingLocation = new Location(
                    world,
                    placementResult.position.x,
                    world.getHighestBlockYAt(placementResult.position.x, placementResult.position.z),
                    placementResult.position.z
            );
            
            Optional<Building> building = placeBuilding(world, buildingLocation, structureId, villageId, buildingSeed);
            
            if (building.isPresent()) {
                placedBuildings.add(building.get());
                
                // IMPORTANT: Track actual placed location for collision detection
                // actualOrigin is the CORNER of the schematic (not center), so use it directly
                Location actualOrigin = building.get().getOrigin();
                
                Footprint footprint = new Footprint(
                        actualOrigin.getBlockX(), 
                        actualOrigin.getBlockZ(), 
                        effectiveWidth, 
                        effectiveDepth);
                occupiedFootprints.add(footprint);
                
                LOGGER.info(String.format("[STRUCT] Placed %s at (%d,%d,%d), tracking footprint: x=%d, z=%d, w=%d, d=%d", 
                        structureId, 
                        actualOrigin.getBlockX(), actualOrigin.getBlockY(), actualOrigin.getBlockZ(),
                        footprint.x, footprint.z, footprint.width, footprint.depth));
            } else {
                LOGGER.warning(String.format("[STRUCT] Failed to place building %s", structureId));
            }
        }
        
        if (placedBuildings.isEmpty()) {
            LOGGER.warning(String.format("[STRUCT] Abort: No buildings placed for village at %s", origin));
            return Optional.empty();
        }
        
        // Log placement metrics (Constitution v1.4.0, Principle XII)
        LOGGER.info(String.format("[STRUCT] Placement metrics for village %s: %s, avgRejected=%.2f",
                villageId, villageRejectionTracker, villageRejectionTracker.getAverageRejectedAttempts()));
        
        // Store village buildings
        for (Building building : placedBuildings) {
            metadataStore.addBuilding(villageId, building);
        }
        
        // Generate path network connecting buildings to the first (main) building
        if (placedBuildings.size() > 1) {
            LOGGER.info(String.format("[STRUCT] Generating path network for village %s", villageId));
            
            // Use first building as main building (temporary until T023 implements proper selection)
            Location mainBuildingLocation = placedBuildings.get(0).getOrigin();
            
            // Collect all building locations
            List<Location> buildingLocations = new ArrayList<>();
            for (Building building : placedBuildings) {
                buildingLocations.add(building.getOrigin());
            }
            
            // Generate path network (A* pathfinding)
            boolean pathSuccess = pathService.generatePathNetwork(
                    world, 
                    villageId, 
                    buildingLocations, 
                    mainBuildingLocation, 
                    seed
            );
            
            if (pathSuccess) {
                // Place path blocks in the world
                List<List<Block>> pathNetwork = pathService.getVillagePathNetwork(villageId);
                int totalPathBlocks = 0;
                
                for (List<Block> pathSegment : pathNetwork) {
                    int placed = pathEmitter.emitPathWithSmoothing(world, pathSegment, cultureId);
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
        
        // Attempt placement with seating via StructureService
        boolean placed = structureService.placeStructure(structureId, world, location, seed);
        
        if (!placed) {
            LOGGER.warning(String.format("[STRUCT] Failed to place structure '%s' at %s", structureId, location));
            return Optional.empty();
        }
        
        // Create building metadata using Builder pattern
        int[] dims = dimensions.get();
        Building building = new Building.Builder()
                .villageId(villageId)
                .structureId(structureId)
                .origin(location)
                .dimensions(dims[0], dims[1], dims[2])
                .build();
        
        LOGGER.fine(String.format("[STRUCT] Building placed successfully: buildingId=%s", building.getBuildingId()));
        
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
     * Returns appropriate structure names based on loaded schematics.
     */
    private List<String> getCultureStructures(String cultureId) {
        // For Roman culture, use full structure names that match the schematics
        if ("roman".equalsIgnoreCase(cultureId)) {
            return Arrays.asList(
                "house_roman_small",
                "house_roman_medium",
                "house_roman_villa",
                "workshop_roman_forge",
                "market_roman_stall"
            );
        }
        
        // Default fallback for unknown cultures
        return Arrays.asList(
            "house_roman_small",
            "house_roman_medium",
            "house_roman_villa",
            "workshop_roman_forge",
            "market_roman_stall"
        );
    }
    
    /**
     * Calculate grid positions for buildings with fixed spacing.
     * Places buildings in a spiral pattern outward from center, ensuring
     * minimum spacing between all structures.
     * 
     * @param world Target world
     * @param origin Village center location
     * @param structureIds List of structures to place
     * @param seed Deterministic seed for placement order
     * @return List of grid positions for each building
     */
    private List<GridPosition> calculateGridPositions(World world, Location origin, List<String> structureIds, long seed) {
        List<GridPosition> positions = new ArrayList<>();
        
        // Track occupied grid cells with structure footprints
        List<Footprint> occupiedFootprints = new ArrayList<>();
        
        int originX = origin.getBlockX();
        int originZ = origin.getBlockZ();
        
        for (int i = 0; i < structureIds.size(); i++) {
            String structureId = structureIds.get(i);
            
            // Get structure dimensions
            Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
            if (!dimensions.isPresent()) {
                LOGGER.warning(String.format("[STRUCT] Structure '%s' dimensions not found, skipping grid calculation", structureId));
                continue;
            }
            
            int[] dims = dimensions.get();
            int width = dims[0];
            int depth = dims[2]; // Z dimension
            
            // Determine rotation for this building (same logic as placement uses)
            long buildingSeed = seed + i;
            Random rotationRandom = new Random(buildingSeed);
            int rotationDegrees = rotationRandom.nextInt(4) * 90; // 0, 90, 180, or 270
            
            // Calculate ACTUAL footprint after rotation
            int effectiveWidth, effectiveDepth;
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                // 90° or 270° rotation swaps width and depth
                effectiveWidth = depth;
                effectiveDepth = width;
            } else {
                // 0° or 180° rotation keeps original dimensions
                effectiveWidth = width;
                effectiveDepth = depth;
            }
            
            // Find closest position to center that doesn't overlap
            GridPosition bestPosition = findNonOverlappingPosition(
                    originX, originZ, effectiveWidth, effectiveDepth, occupiedFootprints);
            
            if (bestPosition != null) {
                positions.add(bestPosition);
                
                // Mark this footprint as occupied (including spacing buffer)
                // Spacing buffer extends OUTWARD from structure on all sides
                occupiedFootprints.add(new Footprint(
                        bestPosition.x - DEFAULT_BUILDING_SPACING,  // Start spacing blocks BEFORE structure
                        bestPosition.z - DEFAULT_BUILDING_SPACING,
                        effectiveWidth + DEFAULT_BUILDING_SPACING * 2,  // Structure width + spacing on both sides
                        effectiveDepth + DEFAULT_BUILDING_SPACING * 2   // Structure depth + spacing on both sides
                ));
                
                LOGGER.info(String.format("[STRUCT] Grid position for '%s': origin=(%d, %d), footprint=(%d, %d) size=%dx%d (rotated %d° from %dx%d)+%d buffer",
                        structureId, bestPosition.x, bestPosition.z, 
                        bestPosition.x - DEFAULT_BUILDING_SPACING, bestPosition.z - DEFAULT_BUILDING_SPACING,
                        effectiveWidth, effectiveDepth, rotationDegrees, width, depth, DEFAULT_BUILDING_SPACING));
            } else {
                LOGGER.warning(String.format("[STRUCT] Could not find grid position for '%s'", structureId));
            }
        }
        
        return positions;
    }
    
    /**
     * Find non-overlapping position closest to village center.
     * Uses spiral search pattern outward from center.
     */
    private GridPosition findNonOverlappingPosition(int centerX, int centerZ, int width, int depth, 
                                                     List<Footprint> occupied) {
        // Start at center
        if (occupied.isEmpty()) {
            return new GridPosition(centerX - width / 2, centerZ - depth / 2);
        }
        
        // Spiral search outward from center
        int maxRadius = 150; // Increased search radius for more placement attempts
        
        for (int radius = 5; radius < maxRadius; radius += 5) {
            // Try positions in a circle around the center
            int numPositions = Math.max(8, radius / 2); // More positions as radius increases
            
            for (int i = 0; i < numPositions; i++) {
                double angle = (2.0 * Math.PI * i) / numPositions;
                int testX = centerX + (int)(radius * Math.cos(angle)) - width / 2;
                int testZ = centerZ + (int)(radius * Math.sin(angle)) - depth / 2;
                
                // Check if this position overlaps with any occupied footprint
                boolean overlaps = false;
                for (Footprint footprint : occupied) {
                    if (footprintsOverlap(testX, testZ, width, depth, footprint)) {
                        overlaps = true;
                        break;
                    }
                }
                
                if (!overlaps) {
                    return new GridPosition(testX, testZ);
                }
            }
        }
        
        return null; // Could not find valid position
    }
    
    /**
     * Check if two footprints overlap.
     * The new structure (x1, z1, w1, d1) needs spacing buffer added.
     * The occupied footprint (f2) already has spacing buffer included.
     */
    private boolean footprintsOverlap(int x1, int z1, int w1, int d1, Footprint f2) {
        // Add spacing buffer to new structure being checked
        int bufferedX1 = x1 - minBuildingSpacing;
        int bufferedZ1 = z1 - minBuildingSpacing;
        int bufferedW1 = w1 + minBuildingSpacing * 2;
        int bufferedD1 = d1 + minBuildingSpacing * 2;
        
        int x2 = f2.x;
        int z2 = f2.z;
        int w2 = f2.width;
        int d2 = f2.depth;
        
        // Check AABB overlap with buffered dimensions
        return !(bufferedX1 + bufferedW1 <= x2 || bufferedX1 >= x2 + w2 || 
                 bufferedZ1 + bufferedD1 <= z2 || bufferedZ1 >= z2 + d2);
    }
    
    /**
     * Check if two footprints overlap (without spacing buffer).
     * Used for direct AABB collision detection after spacing already verified.
     */
    private boolean footprintsOverlap(Footprint f1, Footprint f2) {
        // Simple AABB overlap check
        return !(f1.x + f1.width <= f2.x || f1.x >= f2.x + f2.width || 
                 f1.z + f1.depth <= f2.z || f1.z >= f2.z + f2.depth);
    }
    
    /**
     * Check terrain suitability for a building footprint.
     * Samples foundation area and classifies terrain to detect water, steep slopes, etc.
     * 
     * RELAXED TOLERANCE: Allows up to 20% of samples to be non-ACCEPTABLE (e.g., minor slopes, 
     * sparse vegetation). Only rejects if >20% bad OR ANY fluid detected (hard veto on water).
     * 
     * @param world World to check
     * @param origin Proposed building origin (southwest corner)
     * @param width Building width (X axis)
     * @param depth Building depth (Z axis)
     * @return Classification result with counts
     */
    private TerrainClassifier.ClassificationResult checkTerrainSuitability(
            World world, Location origin, int width, int depth) {
        TerrainClassifier.ClassificationResult result = new TerrainClassifier.ClassificationResult();
        
        // Sample foundation blocks (every 4 blocks - reduced density for performance)
        int sampleStep = 4;
        for (int x = 0; x < width; x += sampleStep) {
            for (int z = 0; z < depth; z += sampleStep) {
                int worldX = origin.getBlockX() + x;
                int worldY = origin.getBlockY();
                int worldZ = origin.getBlockZ() + z;
                
                TerrainClassifier.Classification classification = 
                        TerrainClassifier.classify(world, worldX, worldY, worldZ);
                result.increment(classification);
            }
        }
        
        return result;
    }
    
    /**
     * Simple grid position holder.
     */
    /**
     * Result of placement position search with integrated terrain/spacing/overlap checks.
     */
    private static class PlacementResult {
        final GridPosition position;
        final int attempts;
        
        PlacementResult(GridPosition position, int attempts) {
            this.position = position;
            this.attempts = attempts;
        }
    }
    
    /**
     * Find suitable placement position with integrated terrain, spacing, and overlap checks.
     * Uses spiral search pattern from origin, checking terrain FIRST (cheapest), then spacing, then overlap.
     * Records all rejection reasons in tracker for observability (Constitution v1.4.0, Principle XII).
     * 
     * @param world World to search
     * @param origin Village origin (center point)
     * @param width Building width (after rotation)
     * @param depth Building depth (after rotation)
     * @param occupiedFootprints Already placed buildings
     * @param tracker Rejection reason tracker
     * @return PlacementResult with position and attempt count, or null if no suitable position found
     */
    private PlacementResult findSuitablePlacementPosition(
            World world, Location origin, int width, int depth,
            List<Footprint> occupiedFootprints, 
            PlacementRejectionTracker tracker) {
        
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
                    
                    tracker.recordAttempt();
                    
                    int candidateX = origin.getBlockX() + dx;
                    int candidateZ = origin.getBlockZ() + dz;
                    int candidateY = world.getHighestBlockYAt(candidateX, candidateZ);
                    
                    Location candidateLocation = new Location(world, candidateX, candidateY, candidateZ);
                    
                    // CHECK 1: Terrain classification (cheapest, fails fast)
                    TerrainClassifier.ClassificationResult terrainResult = 
                            checkTerrainSuitability(world, candidateLocation, width, depth);
                    
                    // Use relaxed tolerance check: hard veto on water, 20% tolerance for steep/blocked
                    if (!terrainResult.isAcceptableWithTolerance()) {
                        tracker.recordTerrainRejection(terrainResult);
                        LOGGER.finest(String.format("[STRUCT] Terrain rejection at (%d,%d): %s", 
                                candidateX, candidateZ, terrainResult));
                        continue;
                    }
                    
                    // CHECK 2: Spacing check (create temporary footprint)
                    Footprint candidateFootprint = new Footprint(candidateX, candidateZ, width, depth);
                    
                    // Check minimum spacing to all occupied footprints
                    boolean hasSpacingViolation = false;
                    for (Footprint occupied : occupiedFootprints) {
                        if (!hasMinimumSpacing(candidateFootprint, occupied, minBuildingSpacing)) {
                            hasSpacingViolation = true;
                            break;
                        }
                    }
                    
                    if (hasSpacingViolation) {
                        tracker.recordSpacingRejection();
                        LOGGER.finest(String.format("[STRUCT] Spacing rejection at (%d,%d)", 
                                candidateX, candidateZ));
                        continue;
                    }
                    
                    // CHECK 3: Overlap check (most expensive, done last)
                    boolean hasOverlap = false;
                    for (Footprint occupied : occupiedFootprints) {
                        if (footprintsOverlap(candidateFootprint, occupied)) {
                            hasOverlap = true;
                            break;
                        }
                    }
                    
                    if (hasOverlap) {
                        tracker.recordOverlapRejection();
                        LOGGER.finest(String.format("[STRUCT] Overlap rejection at (%d,%d)", 
                                candidateX, candidateZ));
                        continue;
                    }
                    
                    // SUCCESS: All checks passed
                    LOGGER.fine(String.format("[STRUCT] Found suitable position at (%d,%d) after %d attempts", 
                            candidateX, candidateZ, tracker.totalAttempts));
                    return new PlacementResult(new GridPosition(candidateX, candidateZ), tracker.totalAttempts);
                }
            }
        }
        
        // No suitable position found
        LOGGER.warning(String.format("[STRUCT] No suitable position found after %d attempts (maxRadius=%d)", 
                tracker.totalAttempts, maxRadius));
        return null;
    }
    
    /**
     * Check if two footprints have minimum spacing between them.
     * Spacing is measured as the minimum distance between any edges of the AABBs.
     * 
     * @param a First footprint
     * @param b Second footprint
     * @param minSpacing Minimum required spacing
     * @return true if spacing >= minSpacing, false otherwise
     */
    private boolean hasMinimumSpacing(Footprint a, Footprint b, int minSpacing) {
        // Calculate horizontal and vertical distances between AABBs
        int horizontalDist = Math.max(0, Math.max(a.x - (b.x + b.width), b.x - (a.x + a.width)));
        int verticalDist = Math.max(0, Math.max(a.z - (b.z + b.depth), b.z - (a.z + a.depth)));
        
        // Minimum distance is the smaller of the two (closest edge)
        int minDist = Math.max(horizontalDist, verticalDist);
        
        return minDist >= minSpacing;
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

