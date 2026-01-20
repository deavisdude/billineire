package com.davisodom.villageoverhaul.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;

import java.util.UUID;

/**
 * Encapsulates a village generation request initiated from a command.
 * Used by TickBudgetedGenerationQueue to process generation across multiple ticks.
 * 
 * T066: Non-blocking command execution support.
 */
public class CommandGenerationRequest {
    
    private final UUID requestId;
    private final CommandSender sender;
    private final String cultureId;
    private final String villageName;
    private final Long seed; // nullable - will be computed if null
    private final Location origin;
    private final UUID existingVillageId; // optional - set when re-running structures for an existing village
    private final boolean existingVillageRequest;
    private final long enqueuedAt;
    
    // Progress tracking
    private GenerationPhase currentPhase;
    private int structuresPlaced;
    private int structureAttempts;
    private long phaseStartTime;
    
    public enum GenerationPhase {
        QUEUED,          // Waiting to start
        TERRAIN_SEARCH,  // Finding suitable location
        VILLAGE_CREATION,// Creating village entity
        STRUCTURE_PLACEMENT, // Placing structures
        COMPLETED,       // Successfully finished
        FAILED           // Failed with error
    }
    
    public CommandGenerationRequest(CommandSender sender, String cultureId, String villageName,
                                   Long seed, Location origin) {
        this(sender, cultureId, villageName, seed, origin, null);
    }

    public CommandGenerationRequest(CommandSender sender, String cultureId, String villageName,
                                   Long seed, Location origin, UUID existingVillageId) {
        this.requestId = UUID.randomUUID();
        this.sender = sender;
        this.cultureId = cultureId;
        this.villageName = villageName;
        this.seed = seed;
        this.origin = origin;
        this.existingVillageId = existingVillageId;
        this.existingVillageRequest = existingVillageId != null;
        this.enqueuedAt = System.currentTimeMillis();
        this.currentPhase = GenerationPhase.QUEUED;
        this.structuresPlaced = 0;
        this.structureAttempts = 0;
        this.phaseStartTime = enqueuedAt;
    }
    
    public UUID getRequestId() {
        return requestId;
    }
    
    public CommandSender getSender() {
        return sender;
    }
    
    public String getCultureId() {
        return cultureId;
    }
    
    public String getVillageName() {
        return villageName;
    }
    
    public Long getSeed() {
        return seed;
    }
    
    public Location getOrigin() {
        return origin;
    }

    public boolean isExistingVillageRequest() {
        return existingVillageRequest;
    }

    public UUID getExistingVillageId() {
        return existingVillageId;
    }
    
    public long getEnqueuedAt() {
        return enqueuedAt;
    }
    
    public GenerationPhase getCurrentPhase() {
        return currentPhase;
    }
    
    public void setCurrentPhase(GenerationPhase phase) {
        this.currentPhase = phase;
        this.phaseStartTime = System.currentTimeMillis();
    }
    
    public int getStructuresPlaced() {
        return structuresPlaced;
    }
    
    public void setStructuresPlaced(int structuresPlaced) {
        this.structuresPlaced = Math.max(0, structuresPlaced);
    }

    public void incrementStructuresPlaced() {
        this.structuresPlaced++;
    }
    
    public int getStructureAttempts() {
        return structureAttempts;
    }
    
    public void incrementStructureAttempts() {
        this.structureAttempts++;
    }
    
    public long getPhaseElapsedMs() {
        return System.currentTimeMillis() - phaseStartTime;
    }
    
    public long getTotalElapsedMs() {
        return System.currentTimeMillis() - enqueuedAt;
    }
    
    /**
     * Send a message to the command sender if they're still online.
     */
    public void sendMessage(Component message) {
        if (sender != null) {
            sender.sendMessage(message);
        }
    }
    
    /**
     * Send a plain text message to the command sender.
     */
    public void sendMessage(String message) {
        if (sender != null) {
            sender.sendMessage(message);
        }
    }
    
    @Override
    public String toString() {
        return String.format("GenerationRequest[id=%s, village='%s', culture=%s, phase=%s, placed=%d, attempts=%d, existingVillage=%s]",
            requestId, villageName, cultureId, currentPhase, structuresPlaced, structureAttempts,
            existingVillageRequest ? String.valueOf(existingVillageId) : "none");
    }
}
