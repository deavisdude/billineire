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
    
    // Configured spacing value (loaded from plugin config)
    private final int minBuildingSpacing;
    
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
    }
    
    @Override
    public Optional<UUID> placeVillage(World world, Location origin, String cultureId, long seed) {
        LOGGER.info(String.format("[STRUCT] Begin village placement: culture=%s, origin=%s, seed=%d, minSpacing=%d",
                cultureId, origin, seed, minBuildingSpacing));
        
        // NOTE: Site validation already performed by VillageWorldgenAdapter terrain search
        // Skip redundant validation here to avoid false negatives
        
        UUID villageId = UUID.randomUUID();
        
        // TODO: Load culture definition and structure set
        // For now, use culture-appropriate structures based on loaded schematics
        List<String> structureIds = getCultureStructures(cultureId);
        
        List<Building> placedBuildings = new ArrayList<>();
        
        // Track occupied footprints to prevent overlaps DURING placement
        List<Footprint> occupiedFootprints = new ArrayList<>();
        
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
            
            // Find non-overlapping position for this building
            GridPosition buildingPos = findNonOverlappingPosition(
                    origin.getBlockX(), origin.getBlockZ(), 
                    effectiveWidth, effectiveDepth, 
                    occupiedFootprints);
            
            if (buildingPos == null) {
                LOGGER.warning(String.format("[STRUCT] Could not find non-overlapping position for '%s' (spacing=%d blocks)", 
                        structureId, minBuildingSpacing));
                continue;
            }
            
            Location buildingLocation = new Location(
                    world,
                    buildingPos.x,
                    world.getHighestBlockYAt(buildingPos.x, buildingPos.z),
                    buildingPos.z
            );
            
            // Early terrain classification check to skip unacceptable sites
            TerrainClassifier.ClassificationResult terrainCheck = checkTerrainSuitability(
                    world, buildingLocation, effectiveWidth, effectiveDepth);
            
            if (terrainCheck.getRejected() > 0) {
                LOGGER.fine(String.format("[STRUCT] Skipping position for '%s' due to terrain: %s", 
                        structureId, terrainCheck));
                continue;
            }
            
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
     * Check terrain suitability for a building footprint.
     * Samples foundation area and classifies terrain to detect water, steep slopes, etc.
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
        
        // Sample foundation blocks (every 2 blocks to avoid excessive checks)
        int sampleStep = 2;
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
}

