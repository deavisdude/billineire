package com.example.villageoverhaul.npc;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.UUID;

/**
 * Represents a spawned Custom Villager with culture-profession identity
 * 
 * Custom Villagers are distinct from vanilla villagers and use custom
 * appearance and interaction flows. They are bound to a village and
 * profession, and their lifecycle is managed by CustomVillagerService.
 * 
 * Constitution compliance:
 * - Principle I: Cross-edition compatible (no client mods)
 * - Principle III: Performance tracked per-NPC
 */
public class CustomVillager {
    
    private final UUID entityId;
    private final String definitionId;
    private final String cultureId;
    private final String professionId;
    private final UUID villageId;
    private Location location;
    private long lastInteractionAt;
    private long lastTickNanos;
    
    public CustomVillager(UUID entityId, String definitionId, String cultureId, 
                          String professionId, UUID villageId, Location location) {
        this.entityId = entityId;
        this.definitionId = definitionId;
        this.cultureId = cultureId;
        this.professionId = professionId;
        this.villageId = villageId;
        this.location = location;
        this.lastInteractionAt = 0;
        this.lastTickNanos = 0;
    }
    
    public UUID getEntityId() {
        return entityId;
    }
    
    public String getDefinitionId() {
        return definitionId;
    }
    
    public String getCultureId() {
        return cultureId;
    }
    
    public String getProfessionId() {
        return professionId;
    }
    
    public UUID getVillageId() {
        return villageId;
    }
    
    public Location getLocation() {
        return location;
    }
    
    public void setLocation(Location location) {
        this.location = location;
    }
    
    public long getLastInteractionAt() {
        return lastInteractionAt;
    }
    
    public void setLastInteractionAt(long timestamp) {
        this.lastInteractionAt = timestamp;
    }
    
    public long getLastTickNanos() {
        return lastTickNanos;
    }
    
    public void setLastTickNanos(long nanos) {
        this.lastTickNanos = nanos;
    }
}
