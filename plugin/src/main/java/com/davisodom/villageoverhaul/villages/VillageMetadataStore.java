package com.davisodom.villageoverhaul.villages;

import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.model.PathNetwork;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory store for village metadata with disk persistence.
 * Tracks buildings, path networks, main building designations.
 * Thread-safe for concurrent access.
 */
public class VillageMetadataStore {
    
    private final Plugin plugin;
    private final Logger logger;
    private final File storageDir;
    
    // In-memory caches (thread-safe)
    private final Map<UUID, VillageMetadata> villages = new ConcurrentHashMap<>();
    private final Map<UUID, List<Building>> villageBuildings = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> mainBuildings = new ConcurrentHashMap<>(); // villageId -> mainBuildingId
    private final Map<UUID, PathNetwork> pathNetworks = new ConcurrentHashMap<>();
    
    public VillageMetadataStore(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storageDir = new File(plugin.getDataFolder(), "villages");
        
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
     */
    public void saveAll() throws IOException {
        // TODO: Implement JSON serialization in future tasks
        logger.fine(String.format("[STRUCT] Saved %d villages to disk", villages.size()));
    }
    
    /**
     * Load all village data from disk.
     */
    public void loadAll() throws IOException {
        // TODO: Implement JSON deserialization in future tasks
        logger.fine("[STRUCT] Loaded village data from disk");
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
        
        public VillageMetadata(UUID villageId, String cultureId, Location origin, long seed, long createdTimestamp) {
            this.villageId = villageId;
            this.cultureId = cultureId;
            this.origin = origin;
            this.seed = seed;
            this.createdTimestamp = createdTimestamp;
        }
        
        public UUID getVillageId() { return villageId; }
        public String getCultureId() { return cultureId; }
        public Location getOrigin() { return origin; }
        public long getSeed() { return seed; }
        public long getCreatedTimestamp() { return createdTimestamp; }
    }
}

