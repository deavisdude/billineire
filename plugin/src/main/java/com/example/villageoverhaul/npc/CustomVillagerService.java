package com.example.villageoverhaul.npc;

import com.example.villageoverhaul.obs.Metrics;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service for managing Custom Villager lifecycle, caps, and village binding
 * 
 * Responsibilities:
 * - Spawn/despawn custom villagers with culture-profession identity
 * - Enforce per-village caps (configurable, default ≤ 10)
 * - Track spawned NPCs and provide lookup by village/entity
 * - Persist NPC state across server restarts (via JSON)
 * - Record NPC metrics (spawns, despawns, counts)
 * 
 * Constitution compliance:
 * - Principle II: Server-authoritative spawning; deterministic
 * - Principle III: Enforce per-village NPC caps; observable counts
 * - Principle VI: Data-driven definitions from custom-villager.json
 * - Principle VII: Observability with NPC metrics
 */
public class CustomVillagerService {
    
    private final Plugin plugin;
    private final Logger logger;
    private final Metrics metrics;
    private final Map<UUID, CustomVillager> villagersByEntityId;
    private final Map<UUID, List<CustomVillager>> villagersByVillageId;
    private final int maxVillagersPerVillage;
    
    public CustomVillagerService(Plugin plugin, Logger logger, Metrics metrics) {
        this(plugin, logger, metrics, 10);
    }
    
    public CustomVillagerService(Plugin plugin, Logger logger, Metrics metrics, int maxPerVillage) {
        this.plugin = plugin;
        this.logger = logger;
        this.metrics = metrics;
        this.villagersByEntityId = new ConcurrentHashMap<>();
        this.villagersByVillageId = new ConcurrentHashMap<>();
        this.maxVillagersPerVillage = maxPerVillage;
        
        logger.info("CustomVillagerService initialized (max per village: " + maxPerVillage + ")");
    }
    
    /**
     * Spawn a custom villager at the given location
     * 
     * @param definitionId Custom villager definition ID
     * @param cultureId Culture ID
     * @param professionId Profession ID
     * @param villageId Village ID this NPC belongs to
     * @param location Spawn location
     * @return The spawned CustomVillager, or null if cap reached or spawn failed
     */
    public CustomVillager spawnVillager(String definitionId, String cultureId, 
                                        String professionId, UUID villageId, 
                                        Location location) {
        // Check village cap
        List<CustomVillager> existing = villagersByVillageId.getOrDefault(villageId, new ArrayList<>());
        if (existing.size() >= maxVillagersPerVillage) {
            logger.warning("Cannot spawn " + definitionId + " at village " + villageId + 
                          ": cap reached (" + maxVillagersPerVillage + ")");
            return null;
        }
        
        // Spawn entity (default to VILLAGER for now; appearance adapter will customize)
        Entity entity = location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        entity.setCustomNameVisible(true);
        entity.setPersistent(true);
        
        // Create CustomVillager wrapper
        CustomVillager villager = new CustomVillager(
            entity.getUniqueId(),
            definitionId,
            cultureId,
            professionId,
            villageId,
            location
        );
        
        // Register
        villagersByEntityId.put(entity.getUniqueId(), villager);
        villagersByVillageId.computeIfAbsent(villageId, k -> new ArrayList<>()).add(villager);
        
        // Metrics
        metrics.increment("npc.spawns");
        metrics.increment("npc.count.total");
        
        logger.info("Spawned custom villager " + definitionId + " (entity: " + entity.getUniqueId() + 
                   ", village: " + villageId + ")");
        
        return villager;
    }
    
    /**
     * Despawn a custom villager by entity ID
     * 
     * @param entityId Entity UUID
     * @return true if despawned, false if not found
     */
    public boolean despawnVillager(UUID entityId) {
        CustomVillager villager = villagersByEntityId.remove(entityId);
        if (villager == null) {
            return false;
        }
        
        // Remove from village index
        List<CustomVillager> villageList = villagersByVillageId.get(villager.getVillageId());
        if (villageList != null) {
            villageList.removeIf(v -> v.getEntityId().equals(entityId));
        }
        
        // Remove entity from world
        Entity entity = plugin.getServer().getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
        
        // Metrics
        metrics.increment("npc.despawns");
        metrics.increment("npc.count.total", -1);
        
        logger.info("Despawned custom villager " + villager.getDefinitionId() + 
                   " (entity: " + entityId + ")");
        
        return true;
    }
    
    /**
     * Find custom villager by entity ID
     * 
     * @param entityId Entity UUID
     * @return CustomVillager or null if not found
     */
    public CustomVillager getVillagerByEntityId(UUID entityId) {
        return villagersByEntityId.get(entityId);
    }
    
    /**
     * Find all custom villagers for a village
     * 
     * @param villageId Village UUID
     * @return List of custom villagers (unmodifiable)
     */
    public List<CustomVillager> getVillagersByVillageId(UUID villageId) {
        return Collections.unmodifiableList(
            villagersByVillageId.getOrDefault(villageId, Collections.emptyList())
        );
    }
    
    /**
     * Get all custom villagers
     * 
     * @return Collection of all custom villagers
     */
    public Collection<CustomVillager> getAllVillagers() {
        return Collections.unmodifiableCollection(villagersByEntityId.values());
    }
    
    /**
     * Get current count for a village
     * 
     * @param villageId Village UUID
     * @return Number of custom villagers in this village
     */
    public int getVillagerCount(UUID villageId) {
        return villagersByVillageId.getOrDefault(villageId, Collections.emptyList()).size();
    }
    
    /**
     * Get the per-village cap
     * 
     * @return Maximum villagers per village
     */
    public int getMaxVillagersPerVillage() {
        return maxVillagersPerVillage;
    }
    
    /**
     * Despawn all custom villagers (e.g., on plugin disable)
     */
    public void despawnAll() {
        List<UUID> entityIds = new ArrayList<>(villagersByEntityId.keySet());
        for (UUID entityId : entityIds) {
            despawnVillager(entityId);
        }
        logger.info("Despawned all custom villagers (" + entityIds.size() + ")");
    }
    
    /**
     * Get total count of all active custom villagers across all villages
     * 
     * @return Total number of active custom villagers
     */
    public int getActiveVillagerCount() {
        return villagersByEntityId.size();
    }
    
    /**
     * Convenience method for spawning a custom villager with simplified parameters for testing
     * 
     * @param location Spawn location
     * @param professionId Profession/definition ID
     * @param villageIdStr Village ID string (will be converted to UUID)
     * @return Entity UUID of spawned villager, or null if spawn failed
     */
    public UUID spawnCustomVillager(Location location, String professionId, String villageIdStr) {
        // Parse or generate village UUID
        UUID villageId;
        try {
            villageId = UUID.fromString(villageIdStr);
        } catch (IllegalArgumentException e) {
            // Generate deterministic UUID from string for test villages
            villageId = UUID.nameUUIDFromBytes(villageIdStr.getBytes());
        }
        
        // Use "test" as culture ID for test spawns
        String cultureId = "test";
        String definitionId = professionId + "_" + cultureId;
        
        CustomVillager villager = spawnVillager(definitionId, cultureId, professionId, villageId, location);
        
        return (villager != null) ? villager.getEntityId() : null;
    }
}
