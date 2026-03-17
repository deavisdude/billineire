package com.davisodom.villageoverhaul.npc;

import com.davisodom.villageoverhaul.obs.Metrics;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Service for managing Custom Villager lifecycle and village binding
 * 
 * Responsibilities:
 * - Spawn/despawn custom villagers with culture-profession identity
 * - Enforce per-village capacity derived from placed structures
 * - Track spawned NPCs and provide lookup by village/entity
 * - Persist NPC state across server restarts (via JSON)
 * - Record NPC metrics (spawns, despawns, counts)
 * 
 * Constitution compliance:
 * - Principle II: Server-authoritative spawning; deterministic
 * - Principle III: Enforce per-village NPC capacity; observable counts
 * - Principle VI: Data-driven definitions from custom-villager.json
 * - Principle VII: Observability with NPC metrics
 */
public class CustomVillagerService {

    private static final double DEFAULT_CAPACITY_PER_STRUCTURE = 2.0;
    
    private final Plugin plugin;
    private final Logger logger;
    private final Metrics metrics;
    private final VillageMetadataStore metadataStore;
    private final Map<UUID, CustomVillager> villagersByEntityId;
    private final Map<UUID, List<CustomVillager>> villagersByVillageId;
    private final double villagerCapacityPerStructure;
    private final Set<UUID> loggedCapInfo = ConcurrentHashMap.newKeySet();
    
    public CustomVillagerService(Plugin plugin, Logger logger, Metrics metrics) {
        this(plugin, logger, metrics, null, DEFAULT_CAPACITY_PER_STRUCTURE);
    }

    public CustomVillagerService(Plugin plugin, Logger logger, Metrics metrics, VillageMetadataStore metadataStore) {
        this(plugin, logger, metrics, metadataStore, DEFAULT_CAPACITY_PER_STRUCTURE);
    }

    public CustomVillagerService(Plugin plugin, Logger logger, Metrics metrics, VillageMetadataStore metadataStore,
                                 double villagerCapacityPerStructure) {
        this.plugin = plugin;
        this.logger = logger;
        this.metrics = metrics;
        this.metadataStore = metadataStore;
        this.villagersByEntityId = new ConcurrentHashMap<>();
        this.villagersByVillageId = new ConcurrentHashMap<>();
        this.villagerCapacityPerStructure = villagerCapacityPerStructure;
        
        if (villagerCapacityPerStructure <= 0) {
            logger.info("CustomVillagerService initialized (villagerCapacityPerStructure=0, no cap)");
        } else {
            logger.info("CustomVillagerService initialized (villagerCapacityPerStructure=" + villagerCapacityPerStructure + ")");
        }
    }
    
    /**
     * Spawn a custom villager at the given location
     * 
     * @param definitionId Custom villager definition ID
     * @param cultureId Culture ID
     * @param professionId Profession ID
     * @param villageId Village ID this NPC belongs to
     * @param location Spawn location
    * @return The spawned CustomVillager, or null if capacity reached or spawn failed
     */
    public CustomVillager spawnVillager(String definitionId, String cultureId, 
                                        String professionId, UUID villageId, 
                                        Location location) {
        return spawnVillagerInternal(definitionId, cultureId, professionId, villageId, location, true);
    }

    private CustomVillager spawnVillagerInternal(String definitionId, String cultureId,
                                                 String professionId, UUID villageId,
                                                 Location location, boolean persist) {
        // Check village capacity derived from placed structures
        List<CustomVillager> existing = villagersByVillageId.getOrDefault(villageId, new ArrayList<>());
        int derivedCapacity = resolveVillagerCapacity(villageId);
        logCapacityInfoIfNeeded(villageId, derivedCapacity);
        if (derivedCapacity != Integer.MAX_VALUE && existing.size() >= derivedCapacity) {
            logger.warning("Cannot spawn " + definitionId + " at village " + villageId +
                          ": capacity reached (" + derivedCapacity + ")");
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

        if (persist && metadataStore != null) {
            metadataStore.addVillagerRecord(new VillageMetadataStore.VillagerRecord(
                entity.getUniqueId().toString(),
                villageId,
                definitionId,
                cultureId,
                professionId,
                location.getWorld().getName(),
                location.getWorld().getUID().toString(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                System.currentTimeMillis()
            ));
        }

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
        return despawnVillagerInternal(entityId, true);
    }

    private boolean despawnVillagerInternal(UUID entityId, boolean removeRecord) {
        CustomVillager villager = villagersByEntityId.remove(entityId);
        if (villager == null) {
            return false;
        }
        
        // Remove from village index
        List<CustomVillager> villageList = villagersByVillageId.get(villager.getVillageId());
        if (villageList != null) {
            villageList.removeIf(v -> v.getEntityId().equals(entityId));
        }
        
        if (removeRecord && metadataStore != null) {
            metadataStore.removeVillagerRecord(villager.getVillageId(), entityId);
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
     * Get the per-village capacity derived from placed structures.
     *
     * @param villageId Village UUID
     * @return Derived capacity, or Integer.MAX_VALUE when no cap is configured
     */
    public int getVillagerCapacity(UUID villageId) {
        return resolveVillagerCapacity(villageId);
    }

    /**
     * Get the configured capacity ratio per structure.
     *
     * @return Capacity per structure ratio (<= 0 means no cap)
     */
    public double getVillagerCapacityPerStructure() {
        return villagerCapacityPerStructure;
    }

    private int resolveVillagerCapacity(UUID villageId) {
        if (villagerCapacityPerStructure <= 0) {
            return Integer.MAX_VALUE;
        }
        if (metadataStore == null || villageId == null) {
            return Integer.MAX_VALUE;
        }
        int structuresPlaced = metadataStore.getPlacementReceipts(villageId).size();
        if (structuresPlaced <= 0) {
            return 0;
        }
        int computed = (int) Math.round(structuresPlaced * villagerCapacityPerStructure);
        return Math.max(1, computed);
    }

    private void logCapacityInfoIfNeeded(UUID villageId, int derivedCapacity) {
        if (villageId == null || !loggedCapInfo.add(villageId)) {
            return;
        }
        if (derivedCapacity == Integer.MAX_VALUE) {
            logger.info("[NPC] Villager capacity: no cap (villagerCapacityPerStructure="
                + villagerCapacityPerStructure + ") for village " + villageId);
            return;
        }

        int structuresPlaced = metadataStore != null
            ? metadataStore.getPlacementReceipts(villageId).size()
            : 0;
        logger.info("[NPC] Villager capacity: derived=" + derivedCapacity
            + " structures=" + structuresPlaced
            + " ratio=" + villagerCapacityPerStructure
            + " village=" + villageId);
    }
    
    /**
     * Despawn all custom villagers (e.g., on plugin disable)
     */
    public void despawnAll(boolean preserveRecords) {
        List<UUID> entityIds = new ArrayList<>(villagersByEntityId.keySet());
        for (UUID entityId : entityIds) {
            despawnVillagerInternal(entityId, !preserveRecords);
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
     * Restore persisted villagers from metadata store.
     *
     * @return number of villagers restored
     */
    public int restorePersistedVillagers() {
        if (metadataStore == null) {
            return 0;
        }

        int restored = 0;
        for (VillageMetadataStore.VillagerRecord record : metadataStore.getAllVillagerRecords()) {
            if (record == null || record.villageId == null) {
                continue;
            }

            org.bukkit.World world = null;
            if (record.worldUuid != null && !record.worldUuid.isBlank()) {
                try {
                    world = plugin.getServer().getWorld(UUID.fromString(record.worldUuid));
                } catch (IllegalArgumentException ignored) {
                    world = null;
                }
            }
            if (world == null) {
                continue;
            }

            Location location = new Location(world, record.x + 0.5, record.y, record.z + 0.5);
            CustomVillager villager = spawnVillagerInternal(
                record.definitionId,
                record.cultureId,
                record.professionId,
                record.villageId,
                location,
                false
            );

            if (villager != null) {
                if (record.entityId != null) {
                    try {
                        metadataStore.removeVillagerRecord(record.villageId, UUID.fromString(record.entityId));
                    } catch (IllegalArgumentException ignored) {
                        metadataStore.removeVillagerRecord(record.villageId, record.definitionId, record.professionId,
                            record.x, record.y, record.z);
                    }
                } else {
                    metadataStore.removeVillagerRecord(record.villageId, record.definitionId, record.professionId,
                        record.x, record.y, record.z);
                }

                metadataStore.addVillagerRecord(new VillageMetadataStore.VillagerRecord(
                    villager.getEntityId().toString(),
                    record.villageId,
                    record.definitionId,
                    record.cultureId,
                    record.professionId,
                    record.worldName,
                    record.worldUuid,
                    record.x,
                    record.y,
                    record.z,
                    record.createdTimestamp
                ));
                restored++;
            }
        }

        return restored;
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

