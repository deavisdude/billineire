package com.davisodom.villageoverhaul.villages;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal VillageService for Phase 2.5 bootstrap
 * Manages village registry and basic operations
 * Full implementation will be completed in Phase 3 (US1)
 */
public class VillageService {
    private final Map<UUID, Village> villages;
    
    public VillageService() {
        this.villages = new ConcurrentHashMap<>();
    }
    
    /**
     * Register a new village
     */
    public Village createVillage(String cultureId, String name, String worldName, int x, int y, int z) {
        UUID id = UUID.randomUUID();
        Village village = new Village(id, cultureId, name, worldName, x, y, z);
        villages.put(id, village);
        return village;
    }
    
    /**
     * Get village by ID
     */
    public Optional<Village> getVillage(UUID id) {
        return Optional.ofNullable(villages.get(id));
    }
    
    /**
     * Get all villages (for persistence/admin API)
     */
    public Collection<Village> getAllVillages() {
        return Collections.unmodifiableCollection(villages.values());
    }
    
    /**
     * Load village from persistence
     */
    public void loadVillage(UUID id, String cultureId, String name, long wealthMillz, String worldName, int x, int y, int z) {
        Village village = new Village(id, cultureId, name, worldName, x, y, z);
        village.addWealth(wealthMillz);
        villages.put(id, village);
    }
    
    /**
     * Find village by name (case-insensitive)
     */
    public Village findVillageByName(String name) {
        return villages.values().stream()
            .filter(v -> v.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Find nearest village to a location
     */
    public Village findNearestVillage(org.bukkit.Location location) {
        if (location == null) return null;
        
        Village nearest = null;
        double minDistSq = Double.MAX_VALUE;
        
        for (Village village : villages.values()) {
            // Only consider villages in same world
            if (!village.getWorldName().equals(location.getWorld().getName())) {
                continue;
            }
            
            double dx = village.getX() - location.getX();
            double dy = village.getY() - location.getY();
            double dz = village.getZ() - location.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearest = village;
            }
        }
        
        return nearest;
    }
}

