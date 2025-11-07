package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.villages.VillagePlacementService;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.StructureService;
import com.davisodom.villageoverhaul.worldgen.impl.StructureServiceImpl;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Implementation of village placement service with integrated structure seating.
 */
public class VillagePlacementServiceImpl implements VillagePlacementService {
    
    private static final Logger LOGGER = Logger.getLogger(VillagePlacementServiceImpl.class.getName());
    
    // Minimum spacing between buildings (blocks)
    private static final int DEFAULT_BUILDING_SPACING = 4;
    
    // Structure service for building placement
    private final StructureService structureService;
    
    // Metadata storage
    private final VillageMetadataStore metadataStore;
    
    // In-memory cache of villages (villageId -> buildings)
    private final Map<UUID, List<Building>> villageBuildings = new HashMap<>();
    
    /**
     * Constructor for testing without plugin reference (uses procedural structures).
     */
    public VillagePlacementServiceImpl(VillageMetadataStore metadataStore) {
        this.structureService = new StructureServiceImpl();
        this.metadataStore = metadataStore;
    }
    
    /**
     * Constructor with plugin reference for loading schematics from disk.
     * 
     * @param plugin Plugin instance (provides data folder)
     * @param metadataStore Metadata storage
     */
    public VillagePlacementServiceImpl(Plugin plugin, VillageMetadataStore metadataStore) {
        this.structureService = new StructureServiceImpl(plugin.getDataFolder());
        this.metadataStore = metadataStore;
    }
    
    /**
     * Constructor with custom structure service (for dependency injection).
     */
    public VillagePlacementServiceImpl(StructureService structureService, VillageMetadataStore metadataStore) {
        this.structureService = structureService;
        this.metadataStore = metadataStore;
    }
    
    @Override
    public Optional<UUID> placeVillage(World world, Location origin, String cultureId, long seed) {
        LOGGER.info(String.format("[STRUCT] Begin village placement: culture=%s, origin=%s, seed=%d",
                cultureId, origin, seed));
        
        // NOTE: Site validation already performed by VillageWorldgenAdapter terrain search
        // Skip redundant validation here to avoid false negatives
        
        UUID villageId = UUID.randomUUID();
        
        // TODO: Load culture definition and structure set
        // For now, use culture-appropriate structures based on loaded schematics
        List<String> structureIds = getCultureStructures(cultureId);
        
        List<Building> placedBuildings = new ArrayList<>();
        
        // Use grid-based placement with fixed spacing to prevent overlaps
        // Buildings placed in a spiral pattern outward from center
        List<GridPosition> gridPositions = calculateGridPositions(world, origin, structureIds, seed);
        
        for (int i = 0; i < structureIds.size() && i < gridPositions.size(); i++) {
            String structureId = structureIds.get(i);
            GridPosition gridPos = gridPositions.get(i);
            
            Location buildingLocation = new Location(
                    world,
                    gridPos.x,
                    world.getHighestBlockYAt(gridPos.x, gridPos.z),
                    gridPos.z
            );
            
            // Derive building-specific seed
            long buildingSeed = seed + i;
            
            Optional<Building> building = placeBuilding(world, buildingLocation, structureId, villageId, buildingSeed);
            
            if (building.isPresent()) {
                placedBuildings.add(building.get());
                LOGGER.fine(String.format("[STRUCT] Placed building %s at %s", structureId, buildingLocation));
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
                "workshop_roman_forge"
            );
        }
        
        // Default fallback for unknown cultures
        return Arrays.asList(
            "house_roman_small",
            "house_roman_medium",
            "workshop_roman_forge"
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
            
            // Find closest position to center that doesn't overlap
            GridPosition bestPosition = findNonOverlappingPosition(
                    originX, originZ, width, depth, occupiedFootprints);
            
            if (bestPosition != null) {
                positions.add(bestPosition);
                
                // Mark this footprint as occupied (including spacing buffer)
                occupiedFootprints.add(new Footprint(
                        bestPosition.x,
                        bestPosition.z,
                        width + DEFAULT_BUILDING_SPACING * 2,  // Add spacing on all sides
                        depth + DEFAULT_BUILDING_SPACING * 2
                ));
                
                LOGGER.fine(String.format("[STRUCT] Grid position for '%s': (%d, %d), size: %dx%d",
                        structureId, bestPosition.x, bestPosition.z, width, depth));
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
        int maxRadius = 100; // Maximum search radius in blocks
        
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
     * Check if two footprints overlap (including spacing buffer already added to occupied).
     */
    private boolean footprintsOverlap(int x1, int z1, int w1, int d1, Footprint f2) {
        int x2 = f2.x;
        int z2 = f2.z;
        int w2 = f2.width;
        int d2 = f2.depth;
        
        // Check AABB overlap
        return !(x1 + w1 <= x2 || x1 >= x2 + w2 || z1 + d1 <= z2 || z1 >= z2 + d2);
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

