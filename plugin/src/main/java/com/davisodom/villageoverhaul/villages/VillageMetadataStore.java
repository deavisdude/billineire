package com.davisodom.villageoverhaul.villages;

import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.model.PathNetwork;
import com.davisodom.villageoverhaul.persistence.JsonStore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory store for village metadata with disk persistence.
 * Tracks buildings, path networks, main building designations, and dynamic borders.
 * Thread-safe for concurrent access.
 */
public class VillageMetadataStore {
    
    private final Plugin plugin;
    private final Logger logger;
    private final File storageDir;
    private final JsonStore jsonStore;
    
    // In-memory caches (thread-safe)
    private final Map<UUID, VillageMetadata> villages = new ConcurrentHashMap<>();
    private final Map<UUID, List<Building>> villageBuildings = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> mainBuildings = new ConcurrentHashMap<>(); // villageId -> mainBuildingId
    private final Map<UUID, PathNetwork> pathNetworks = new ConcurrentHashMap<>();
    
    public VillageMetadataStore(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storageDir = new File(plugin.getDataFolder(), "villages");
        this.jsonStore = new JsonStore(storageDir, logger);
        
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }
    
    /**
     * Register a new village.
     */
    public void registerVillage(UUID villageId, String cultureId, Location origin, long seed) {
        VillageMetadata metadata = new VillageMetadata(villageId, cultureId, origin, seed, System.currentTimeMillis());
        villages.put(villageId, metadata);
        villageBuildings.put(villageId, new ArrayList<>());
        logger.info(String.format("[STRUCT] Registered village %s (culture: %s) at %s", 
            villageId, cultureId, formatLocation(origin)));
    }
    
    /**
     * Add a building to a village.
     */
    public void addBuilding(UUID villageId, Building building) {
        villageBuildings.computeIfAbsent(villageId, k -> new ArrayList<>()).add(building);
        
        // Update village border to include this building
        VillageMetadata metadata = villages.get(villageId);
        if (metadata != null) {
            metadata.expandBorderForBuilding(building);
        }
        
        logger.fine(String.format("[STRUCT] Added building %s to village %s", 
            building.getStructureId(), villageId));
    }
    
    /**
     * Get all buildings for a village.
     */
    public List<Building> getVillageBuildings(UUID villageId) {
        return new ArrayList<>(villageBuildings.getOrDefault(villageId, Collections.emptyList()));
    }
    
    /**
     * Designate a building as the main building for a village.
     */
    public void setMainBuilding(UUID villageId, UUID buildingId) {
        mainBuildings.put(villageId, buildingId);
        logger.info(String.format("[STRUCT] Designated building %s as main building for village %s", 
            buildingId, villageId));
    }
    
    /**
     * Get the main building for a village.
     */
    public Optional<UUID> getMainBuilding(UUID villageId) {
        return Optional.ofNullable(mainBuildings.get(villageId));
    }
    
    /**
     * Store path network for a village.
     */
    public void setPathNetwork(UUID villageId, PathNetwork pathNetwork) {
        pathNetworks.put(villageId, pathNetwork);
        logger.fine(String.format("[STRUCT] Stored path network for village %s", villageId));
    }
    
    /**
     * Get path network for a village.
     */
    public Optional<PathNetwork> getPathNetwork(UUID villageId) {
        return Optional.ofNullable(pathNetworks.get(villageId));
    }
    
    /**
     * Get village metadata.
     */
    public Optional<VillageMetadata> getVillage(UUID villageId) {
        return Optional.ofNullable(villages.get(villageId));
    }
    
    /**
     * Get all registered villages.
     */
    public Collection<VillageMetadata> getAllVillages() {
        return new ArrayList<>(villages.values());
    }
    
    /**
     * Remove a village and all its data.
     */
    public boolean removeVillage(UUID villageId) {
        if (villages.remove(villageId) != null) {
            villageBuildings.remove(villageId);
            mainBuildings.remove(villageId);
            pathNetworks.remove(villageId);
            logger.info(String.format("[STRUCT] Removed village %s", villageId));
            return true;
        }
        return false;
    }
    
    /**
     * Save all village data to disk (JSON format).
     * Persists mainBuildingId and pathNetwork for each village.
     */
    public void saveAll() throws IOException {
        int savedCount = 0;
        
        for (VillageMetadata metadata : villages.values()) {
            UUID villageId = metadata.getVillageId();
            
            // Create persistence DTO
            VillageDataDTO dto = new VillageDataDTO();
            dto.villageId = villageId.toString();
            dto.cultureId = metadata.getCultureId();
            dto.worldName = metadata.getOrigin().getWorld().getName();
            dto.originX = metadata.getOrigin().getBlockX();
            dto.originY = metadata.getOrigin().getBlockY();
            dto.originZ = metadata.getOrigin().getBlockZ();
            dto.seed = metadata.getSeed();
            dto.createdTimestamp = metadata.getCreatedTimestamp();
            
            // Persist mainBuildingId
            dto.mainBuildingId = mainBuildings.containsKey(villageId) 
                ? mainBuildings.get(villageId).toString() 
                : null;
            
            // Persist pathNetwork
            if (pathNetworks.containsKey(villageId)) {
                PathNetwork network = pathNetworks.get(villageId);
                dto.pathNetwork = convertPathNetworkToDTO(network);
            }
            
            // Persist border
            VillageBorder border = metadata.getBorder();
            dto.border = new BorderDTO(
                border.getMinX(), border.getMaxX(),
                border.getMinZ(), border.getMaxZ()
            );
            dto.lastBorderUpdateTick = metadata.getLastBorderUpdateTick();
            
            // Save to individual village file
            String filename = "village_" + villageId + ".json";
            jsonStore.saveJson(filename, dto, JsonStore.SCHEMA_VERSION);
            savedCount++;
        }
        
        logger.info(String.format("[STRUCT] Saved %d villages to disk", savedCount));
    }
    
    /**
     * Load all village data from disk.
     */
    public void loadAll() throws IOException {
        File[] files = storageDir.listFiles((dir, name) -> 
            name.startsWith("village_") && name.endsWith(".json"));
        
        if (files == null || files.length == 0) {
            logger.info("[STRUCT] No village data files found");
            return;
        }
        
        int loadedCount = 0;
        
        for (File file : files) {
            try {
                VillageDataDTO dto = jsonStore.loadJson(file.getName(), VillageDataDTO.class);
                if (dto == null) continue;
                
                UUID villageId = UUID.fromString(dto.villageId);
                World world = Bukkit.getWorld(dto.worldName);
                
                if (world == null) {
                    logger.warning(String.format(
                        "[STRUCT] Skipping village %s: world '%s' not loaded", 
                        villageId, dto.worldName));
                    continue;
                }
                
                Location origin = new Location(world, dto.originX, dto.originY, dto.originZ);
                
                // Restore village metadata
                VillageMetadata metadata = new VillageMetadata(
                    villageId, dto.cultureId, origin, dto.seed, dto.createdTimestamp);
                
                // Restore border
                if (dto.border != null) {
                    metadata.getBorder().expand(
                        dto.border.minX, dto.border.maxX,
                        dto.border.minZ, dto.border.maxZ
                    );
                    metadata.lastBorderUpdateTick = dto.lastBorderUpdateTick;
                }
                
                villages.put(villageId, metadata);
                villageBuildings.put(villageId, new ArrayList<>());
                
                // Restore mainBuildingId
                if (dto.mainBuildingId != null) {
                    mainBuildings.put(villageId, UUID.fromString(dto.mainBuildingId));
                    logger.fine(String.format("[STRUCT] Restored main building %s for village %s",
                        dto.mainBuildingId, villageId));
                }
                
                // Restore pathNetwork
                if (dto.pathNetwork != null) {
                    PathNetwork network = convertPathNetworkFromDTO(dto.pathNetwork, villageId, world);
                    pathNetworks.put(villageId, network);
                    logger.fine(String.format("[STRUCT] Restored path network for village %s (%d segments)",
                        villageId, network.getSegments().size()));
                }
                
                loadedCount++;
                
            } catch (Exception e) {
                logger.warning(String.format("[STRUCT] Failed to load %s: %s", 
                    file.getName(), e.getMessage()));
            }
        }
        
        logger.info(String.format("[STRUCT] Loaded %d villages from disk", loadedCount));
    }
    
    /**
     * Clear all in-memory data (for testing).
     */
    public void clearAll() {
        villages.clear();
        villageBuildings.clear();
        mainBuildings.clear();
        pathNetworks.clear();
        logger.info("[STRUCT] Cleared all village metadata");
    }
    
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
    
    /**
     * Inner class representing village metadata.
     */
    public static class VillageMetadata {
        private final UUID villageId;
        private final String cultureId;
        private final Location origin;
        private final long seed;
        private final long createdTimestamp;
        private final VillageBorder border;
        private long lastBorderUpdateTick;
        
        public VillageMetadata(UUID villageId, String cultureId, Location origin, long seed, long createdTimestamp) {
            this.villageId = villageId;
            this.cultureId = cultureId;
            this.origin = origin;
            this.seed = seed;
            this.createdTimestamp = createdTimestamp;
            // Initialize border at origin with minimal size (will expand with buildings)
            this.border = new VillageBorder(origin.getBlockX(), origin.getBlockX(), 
                                           origin.getBlockZ(), origin.getBlockZ());
            this.lastBorderUpdateTick = 0;
        }
        
        public UUID getVillageId() { return villageId; }
        public String getCultureId() { return cultureId; }
        public Location getOrigin() { return origin; }
        public long getSeed() { return seed; }
        public long getCreatedTimestamp() { return createdTimestamp; }
        public VillageBorder getBorder() { return border; }
        public long getLastBorderUpdateTick() { return lastBorderUpdateTick; }
        
        /**
         * Expand border to include a building's footprint.
         * Called deterministically when buildings are placed.
         */
        public void expandBorderForBuilding(Building building) {
            Location buildingOrigin = building.getOrigin();
            int[] dims = building.getDimensions();
            
            // Calculate building footprint bounds
            int minX = buildingOrigin.getBlockX();
            int maxX = minX + dims[0] - 1;
            int minZ = buildingOrigin.getBlockZ();
            int maxZ = minZ + dims[2] - 1;
            
            // Expand border if needed
            border.expand(minX, maxX, minZ, maxZ);
            lastBorderUpdateTick = System.currentTimeMillis(); // Will use tick counter when available
        }
    }
    
    /**
     * Represents axis-aligned border bounds for a village.
     * Mutable to allow deterministic expansion as buildings are added.
     */
    public static class VillageBorder {
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        
        public VillageBorder(int minX, int maxX, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
        
        public int getMinX() { return minX; }
        public int getMaxX() { return maxX; }
        public int getMinZ() { return minZ; }
        public int getMaxZ() { return maxZ; }
        
        public int getWidth() { return maxX - minX + 1; }
        public int getDepth() { return maxZ - minZ + 1; }
        
        /**
         * Expand border to include new bounds.
         * Deterministic and idempotent.
         */
        public void expand(int newMinX, int newMaxX, int newMinZ, int newMaxZ) {
            this.minX = Math.min(this.minX, newMinX);
            this.maxX = Math.max(this.maxX, newMaxX);
            this.minZ = Math.min(this.minZ, newMinZ);
            this.maxZ = Math.max(this.maxZ, newMaxZ);
        }
        
        /**
         * Check if this border is within minDistance of another border.
         * Used for inter-village spacing enforcement.
         */
        public boolean isWithinDistance(VillageBorder other, int minDistance) {
            // Calculate closest distance between borders (not centers)
            int dx = 0;
            if (this.maxX < other.minX) {
                dx = other.minX - this.maxX;
            } else if (other.maxX < this.minX) {
                dx = this.minX - other.maxX;
            }
            
            int dz = 0;
            if (this.maxZ < other.minZ) {
                dz = other.minZ - this.maxZ;
            } else if (other.maxZ < this.minZ) {
                dz = this.minZ - other.maxZ;
            }
            
            // Use Manhattan distance for simplicity and performance
            return (dx + dz) < minDistance;
        }
        
        /**
         * Get border-to-border distance to another village border.
         */
        public int getDistanceTo(VillageBorder other) {
            int dx = 0;
            if (this.maxX < other.minX) {
                dx = other.minX - this.maxX;
            } else if (other.maxX < this.minX) {
                dx = this.minX - other.maxX;
            }
            
            int dz = 0;
            if (this.maxZ < other.minZ) {
                dz = other.minZ - this.maxZ;
            } else if (other.maxZ < this.minZ) {
                dz = this.minZ - other.maxZ;
            }
            
            return dx + dz; // Manhattan distance
        }
        
        @Override
        public String toString() {
            return String.format("Border[x:%d-%d, z:%d-%d, size:%dx%d]", 
                minX, maxX, minZ, maxZ, getWidth(), getDepth());
        }
    }
    
    /**
     * Conversion methods for PathNetwork serialization.
     */
    private PathNetworkDTO convertPathNetworkToDTO(PathNetwork network) {
        PathNetworkDTO dto = new PathNetworkDTO();
        dto.villageId = network.getVillageId().toString();
        dto.generatedTimestamp = network.getGeneratedTimestamp();
        dto.totalBlocksPlaced = network.getTotalBlocksPlaced();
        dto.segments = new ArrayList<>();
        
        for (PathNetwork.PathSegment segment : network.getSegments()) {
            PathSegmentDTO segmentDTO = new PathSegmentDTO();
            segmentDTO.startX = segment.getStart().getBlockX();
            segmentDTO.startY = segment.getStart().getBlockY();
            segmentDTO.startZ = segment.getStart().getBlockZ();
            segmentDTO.endX = segment.getEnd().getBlockX();
            segmentDTO.endY = segment.getEnd().getBlockY();
            segmentDTO.endZ = segment.getEnd().getBlockZ();
            segmentDTO.blockCount = segment.getBlocks().size();
            
            // Store block locations (for reconstruction)
            segmentDTO.blocks = new ArrayList<>();
            for (Block block : segment.getBlocks()) {
                BlockLocationDTO blockDTO = new BlockLocationDTO();
                blockDTO.x = block.getX();
                blockDTO.y = block.getY();
                blockDTO.z = block.getZ();
                segmentDTO.blocks.add(blockDTO);
            }
            
            dto.segments.add(segmentDTO);
        }
        
        return dto;
    }
    
    private PathNetwork convertPathNetworkFromDTO(PathNetworkDTO dto, UUID villageId, World world) {
        PathNetwork.Builder builder = new PathNetwork.Builder()
            .villageId(villageId)
            .generatedTimestamp(dto.generatedTimestamp);
        
        for (PathSegmentDTO segmentDTO : dto.segments) {
            Location start = new Location(world, segmentDTO.startX, segmentDTO.startY, segmentDTO.startZ);
            Location end = new Location(world, segmentDTO.endX, segmentDTO.endY, segmentDTO.endZ);
            
            List<Block> blocks = new ArrayList<>();
            for (BlockLocationDTO blockDTO : segmentDTO.blocks) {
                blocks.add(world.getBlockAt(blockDTO.x, blockDTO.y, blockDTO.z));
            }
            
            PathNetwork.PathSegment segment = new PathNetwork.PathSegment(start, end, blocks);
            builder.addSegment(segment);
        }
        
        return builder.build();
    }
    
    /**
     * Data Transfer Objects for JSON persistence.
     */
    public static class VillageDataDTO {
        public String villageId;
        public String cultureId;
        public String worldName;
        public int originX;
        public int originY;
        public int originZ;
        public long seed;
        public long createdTimestamp;
        public String mainBuildingId; // nullable
        public PathNetworkDTO pathNetwork; // nullable
        public BorderDTO border;
        public long lastBorderUpdateTick;
        
        public VillageDataDTO() {} // For Jackson
    }
    
    public static class PathNetworkDTO {
        public String villageId;
        public long generatedTimestamp;
        public int totalBlocksPlaced;
        public List<PathSegmentDTO> segments;
        
        public PathNetworkDTO() {} // For Jackson
    }
    
    public static class PathSegmentDTO {
        public int startX;
        public int startY;
        public int startZ;
        public int endX;
        public int endY;
        public int endZ;
        public int blockCount;
        public List<BlockLocationDTO> blocks;
        
        public PathSegmentDTO() {} // For Jackson
    }
    
    public static class BlockLocationDTO {
        public int x;
        public int y;
        public int z;
        
        public BlockLocationDTO() {} // For Jackson
    }
    
    public static class BorderDTO {
        public int minX;
        public int maxX;
        public int minZ;
        public int maxZ;
        
        public BorderDTO() {} // For Jackson
        
        @JsonCreator
        public BorderDTO(
            @JsonProperty("minX") int minX, 
            @JsonProperty("maxX") int maxX,
            @JsonProperty("minZ") int minZ, 
            @JsonProperty("maxZ") int maxZ
        ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }
}

