package com.example.villageoverhaul.villages;

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
}
