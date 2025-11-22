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
        boolean isFirst = isFirstVillage(world);
        
        if (isFirst) {
            // First village spawn proximity verified by terrain search
        } else {
            // Subsequent village spacing verified below
        }
        
        InterVillageSpacingResult spacingResult = checkInterVillageSpacingDetailed(origin, minVillageSpacing);
        if (!spacingResult.acceptable) {
            return Optional.empty();
        }
        
        UUID villageId = UUID.randomUUID();
        metadataStore.registerVillage(villageId, cultureId, origin, seed);
        
        List<String> structureIds = getCultureStructures(cultureId);
        List<Building> placedBuildings = new ArrayList<>();
        
        List<VolumeMask> existingMasks = metadataStore.getVolumeMasks(villageId);
        SurfaceSolver surfaceSolver = new SurfaceSolver(world, existingMasks);
        
        // Place buildings one at a time with dynamic collision detection
        // Use grid-based spiral search for each building to find non-overlapping spots
        for (int i = 0; i < structureIds.size(); i++) {
            String structureId = structureIds.get(i);
            
            Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
            if (!dimensions.isPresent()) {
                continue;
            }
            
            int[] dims = dimensions.get();
            int width = dims[0];
            int depth = dims[2];
            int height = dims[1];
            
            long buildingSeed = seed + i;
            
            Optional<Location> placementLocation = findSuitablePlacementPosition(
                    world, origin, width, depth, height, buildingSeed,
                    metadataStore.getVolumeMasks(villageId), surfaceSolver);
            
            if (!placementLocation.isPresent()) {
                continue;
            }
            
            Location buildingLocation = placementLocation.get();
            
            Optional<PlacementReceipt> receiptOpt = structureService.placeStructureAndGetReceipt(
                    structureId, world, buildingLocation, buildingSeed, villageId, existingMasks);
            
            if (receiptOpt.isPresent()) {
                PlacementReceipt receipt = receiptOpt.get();
                metadataStore.addPlacementReceipt(villageId, receipt);
                
                VolumeMask placedMask = VolumeMask.fromReceipt(receipt);
                metadataStore.addVolumeMask(villageId, placedMask);
                surfaceSolver = new SurfaceSolver(world, metadataStore.getVolumeMasks(villageId));
                
                Building building = new Building.Builder()
                        .villageId(villageId)
                        .structureId(structureId)
                        .origin(new Location(world, receipt.getOriginX(), receipt.getOriginY(), receipt.getOriginZ()))
                        .dimensions(receipt.getEffectiveWidth(), receipt.getHeight(), receipt.getEffectiveDepth())
                        .build();
                
                placedBuildings.add(building);
                
                LOGGER.info(String.format("[STRUCT] receipt: id=%s bounds=[%d..%d,%d..%d,%d..%d] rot=%d°", 
                        structureId,
                        receipt.getMinX(), receipt.getMaxX(),
                        receipt.getMinY(), receipt.getMaxY(),
                        receipt.getMinZ(), receipt.getMaxZ(),
                        receipt.getRotation()));
            }
        }
        
        if (placedBuildings.isEmpty()) {
            return Optional.empty();
        }
        
        for (Building building : placedBuildings) {
            metadataStore.addBuilding(villageId, building);
        }
        
        Optional<UUID> mainBuildingId = mainBuildingSelector.selectMainBuilding(cultureId, placedBuildings);
        if (mainBuildingId.isPresent()) {
            metadataStore.setMainBuilding(villageId, mainBuildingId.get());
        }
        
        if (placedBuildings.size() > 1) {
            List<Location> buildingEntrances = new ArrayList<>();
            Location mainBuildingEntrance = null;
            
            List<PlacementReceipt> receipts = metadataStore.getPlacementReceipts(villageId);
            Map<String, PlacementReceipt> receiptMap = new HashMap<>();
            for (PlacementReceipt r : receipts) {
                receiptMap.put(r.getStructureId() + "@" + r.getOriginX() + "," + r.getOriginZ(), r);
            }
            
            for (Building building : placedBuildings) {
                String key = building.getStructureId() + "@" + building.getOrigin().getBlockX() + "," + building.getOrigin().getBlockZ();
                PlacementReceipt receipt = receiptMap.get(key);
                
                if (receipt != null) {
                    Location entrance = new Location(world, receipt.getEntranceX(), receipt.getEntranceY(), receipt.getEntranceZ());
                    buildingEntrances.add(entrance);
                    
                    if (mainBuildingId.isPresent() && building.getBuildingId().equals(mainBuildingId.get())) {
                        mainBuildingEntrance = entrance;
                    }
                }
            }
            
            if (mainBuildingEntrance == null && !buildingEntrances.isEmpty()) {
                mainBuildingEntrance = buildingEntrances.get(0);
            }
            
            boolean pathSuccess = pathService.generatePathNetwork(
                    world, 
                    villageId, 
                    buildingEntrances,
                    mainBuildingEntrance,
                    seed
            );
            
            if (pathSuccess) {
                List<List<Block>> pathNetwork = pathService.getVillagePathNetwork(villageId);
                int totalPathBlocks = 0;
                List<VolumeMask> masks = metadataStore.getVolumeMasks(villageId);
                
                for (List<Block> pathSegment : pathNetwork) {
                    int placed = pathEmitter.emitPathWithSmoothing(world, pathSegment, cultureId, masks);
                    totalPathBlocks += placed;
                }
            }
        }
        
        LOGGER.info(String.format("[STRUCT] village: id=%s buildings=%d",
                villageId, placedBuildings.size()));
        
        return Optional.of(villageId);
    }
    
    @Override
    public boolean validateSite(World world, Location origin, int radius) {
        if (hasCollision(world, origin, radius)) {
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
        return yVariation <= 20;
    }
    
    @Override
    public Optional<Building> placeBuilding(World world, Location location, String structureId, UUID villageId, long seed) {
        Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
        
        if (!dimensions.isPresent()) {
            return Optional.empty();
        }
        
        Optional<com.davisodom.villageoverhaul.worldgen.PlacementResult> placementResult = 
                structureService.placeStructureAndGetResult(structureId, world, location, seed);
        
        if (!placementResult.isPresent()) {
            return Optional.empty();
        }
        
        int[] dims = dimensions.get();
        Building building = new Building.Builder()
                .villageId(villageId)
                .structureId(structureId)
                .origin(placementResult.get().getActualLocation())
                .dimensions(dims[0], dims[1], dims[2])
                .build();
        
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
            return false;
        }
        
        metadataStore.removeVillage(villageId);
        return true;
    }
    
    private List<String> getCultureStructures(String cultureId) {
        Optional<CultureService.Culture> cultureOpt = cultureService.get(cultureId);
        if (!cultureOpt.isPresent()) {
            return Collections.emptyList();
        }
        
        CultureService.Culture culture = cultureOpt.get();
        List<String> structureSet = culture.getStructureSet();
        
        if (structureSet == null || structureSet.isEmpty()) {
            return Collections.emptyList();
        }
        
        String mainBuildingStructureId = culture.getMainBuildingStructureId();
        if (mainBuildingStructureId == null || mainBuildingStructureId.isEmpty()) {
            mainBuildingStructureId = structureSet.get(0);
        }
        
        List<String> result = new ArrayList<>();
        result.add(mainBuildingStructureId);
        
        List<String> otherStructures = new ArrayList<>();
        for (String structureId : structureSet) {
            if (!structureId.equals(mainBuildingStructureId)) {
                otherStructures.add(structureId);
            }
        }
        
        Collections.shuffle(otherStructures, new Random());
        result.addAll(otherStructures);
        
        return result;
    }
    
    /**
     * Find suitable placement position with integrated terrain, spacing, and overlap checks.
     * Uses spiral search pattern from origin.
     * R009: Uses SurfaceSolver for ground finding and VolumeMasks for overlap checks.
     * R011b: Uses rotation-aware collision detection with deterministic rotation.
     */
    private Optional<Location> findSuitablePlacementPosition(
            World world, Location origin, int width, int depth, int height, long buildingSeed,
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
                    
                    // R011b: Determine rotation deterministically from building seed
                    Random rotRandom = new Random(buildingSeed);
                    int rotation = rotRandom.nextInt(4) * 90; // 0, 90, 180, or 270
                    
                    // Compute rotated AABB for this candidate location with determined rotation
                    Location candidateLoc = new Location(world, candidateX, candidateY, candidateZ);
                    int[] candidateAABB = computeRotatedAABB(candidateLoc, width, depth, height, rotation);
                    
                    // Check collision with existing masks (including spacing buffer)
                    boolean overlaps = checkRotatedAABBCollision(candidateAABB, existingMasks, minBuildingSpacing);
                    
                    // R011b: Rotation-aware collision detection implemented
                    
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
     * Compute rotated AABB bounds for a structure at given origin with specified rotation.
     * Used for collision detection BEFORE actual placement.
     * 
     * @param origin Structure origin (SW corner, ground level)
     * @param baseWidth Base structure width (X, before rotation)
     * @param baseDepth Base structure depth (Z, before rotation)
     * @param height Structure height (Y, unchanged by rotation)
     * @param rotation Rotation in degrees (0, 90, 180, or 270)
     * @return int[] {minX, maxX, minY, maxY, minZ, maxZ} - rotated AABB bounds
     */
    private int[] computeRotatedAABB(Location origin, int baseWidth, int baseDepth, int height, int rotation) {
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();
        
        // Calculate the 8 corners of the bounding box in schematic space (origin at 0,0,0)
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
        
        // Rotate each corner around origin (0,0,0) using Y-axis rotation matrix
        int[][] rotatedCorners = new int[8][3];
        for (int i = 0; i < 8; i++) {
            int x = corners[i][0];
            int y = corners[i][1];
            int z = corners[i][2];
            
            // Apply Y-axis rotation (clockwise when viewed from above)
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
            rotatedCorners[i][1] = y; // Y unchanged
        }
        
        // Find min/max of rotated corners
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
        
        // Translate to world coordinates
        int minX = originX + minRotX;
        int maxX = originX + maxRotX - 1; // -1 because size is exclusive
        int minY = originY + minRotY;
        int maxY = originY + maxRotY - 1;
        int minZ = originZ + minRotZ;
        int maxZ = originZ + maxRotZ - 1;
        
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }
    
    /**
     * Check if a rotated AABB intersects with any existing volume mask (with buffer).
     * 
     * @param candidateAABB Candidate structure AABB bounds
     * @param existingMasks List of existing volume masks
     * @param buffer Spacing buffer to apply around existing masks
     * @return true if collision detected, false otherwise
     */
    private boolean checkRotatedAABBCollision(int[] candidateAABB, List<VolumeMask> existingMasks, int buffer) {
        int candMinX = candidateAABB[0];
        int candMaxX = candidateAABB[1];
        int candMinZ = candidateAABB[4];
        int candMaxZ = candidateAABB[5];
        
        for (VolumeMask mask : existingMasks) {
            // Expand mask by buffer
            int maskMinX = mask.getMinX() - buffer;
            int maskMaxX = mask.getMaxX() + buffer;
            int maskMinZ = mask.getMinZ() - buffer;
            int maskMaxZ = mask.getMaxZ() + buffer;
            
            // Check 2D XZ intersection (sufficient for building spacing)
            boolean xOverlap = candMinX <= maskMaxX && candMaxX >= maskMinX;
            boolean zOverlap = candMinZ <= maskMaxZ && candMaxZ >= maskMinZ;
            
            if (xOverlap && zOverlap) {
                return true; // Collision detected
            }
        }
        return false; // No collisions
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
            
            if (proposedBorder.isWithinDistance(existingBorder, minDistance)) {
                int actualDistance = proposedBorder.getDistanceTo(existingBorder);
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
