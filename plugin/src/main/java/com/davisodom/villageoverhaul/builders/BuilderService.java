package com.davisodom.villageoverhaul.builders;

import com.davisodom.villageoverhaul.model.Builder;
import com.davisodom.villageoverhaul.model.PlacementQueue;
import org.bukkit.Location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for managing NPC builder entities and their construction workflows.
 * Implements the deterministic state machine pattern per Constitution XI.
 * 
 * State machine flow:
 * IDLE → WALKING_TO_BUILDING → REQUESTING_MATERIALS → GATHERING_MATERIALS →
 * CLEARING_SITE → PLACING_BLOCKS → COMPLETING → STUCK
 * 
 * All operations are server-authoritative and deterministic from seeds/state.
 * State transitions are persisted as checkpoints for restart safety.
 */
public interface BuilderService {
    
    /**
     * Create a new builder NPC for a village and assign it to a building project.
     * 
     * @param villageId The village this builder belongs to
     * @param targetBuildingId The building structure to construct
     * @param spawnLocation Where to spawn the builder entity
     * @return CompletableFuture containing the created Builder
     */
    CompletableFuture<Builder> createBuilder(UUID villageId, UUID targetBuildingId, Location spawnLocation);
    
    /**
     * Tick the state machine for a specific builder.
     * Called on the main thread, budgeted per Constitution performance targets.
     * 
     * @param builderId The builder to tick
     * @return true if the builder advanced state, false if waiting/blocked
     */
    boolean tickBuilder(UUID builderId);
    
    /**
     * Tick all active builders for a village.
     * Respects per-tick budget constraints (Constitution: p95 ≤ 8ms aggregate).
     * 
     * @param villageId The village whose builders to tick
     * @return Number of builders that advanced state this tick
     */
    int tickBuildersForVillage(UUID villageId);
    
    /**
     * Get the current state of a builder.
     * 
     * @param builderId The builder to query
     * @return Optional containing the Builder if found
     */
    Optional<Builder> getBuilder(UUID builderId);
    
    /**
     * Get all builders for a village, optionally filtered by state.
     * 
     * @param villageId The village to query
     * @param state Optional state filter (null for all states)
     * @return List of builders matching the criteria
     */
    List<Builder> getBuildersForVillage(UUID villageId, Builder.State state);
    
    /**
     * Force a builder into a specific state (admin/debug use).
     * Persists the checkpoint immediately.
     * 
     * @param builderId The builder to modify
     * @param newState The state to transition to
     * @param reason Debug reason for the transition
     * @return true if transition succeeded, false if invalid
     */
    boolean forceBuilderState(UUID builderId, Builder.State newState, String reason);
    
    /**
     * Mark a builder as stuck and trigger recovery logic.
     * Recovery may include: path re-plan, material re-request, or site re-validation.
     * 
     * @param builderId The builder to mark as stuck
     * @param stuckReason Description of why the builder is stuck
     */
    void markBuilderStuck(UUID builderId, String stuckReason);
    
    /**
     * Persist a state checkpoint for a builder.
     * Called automatically on state transitions; exposed for manual checkpointing.
     * 
     * @param builderId The builder to checkpoint
     * @return CompletableFuture that completes when the checkpoint is written
     */
    CompletableFuture<Void> persistCheckpoint(UUID builderId);
    
    /**
     * Attach a placement queue to a builder for the PLACING_BLOCKS state.
     * The builder will process blocks from this queue row-by-row, layer-by-layer.
     * 
     * @param builderId The builder to attach the queue to
     * @param queue The placement queue to process
     */
    void attachPlacementQueue(UUID builderId, PlacementQueue queue);
    
    /**
     * Remove and despawn a builder (on project completion or cancellation).
     * 
     * @param builderId The builder to remove
     * @return CompletableFuture that completes when the builder is removed
     */
    CompletableFuture<Void> removeBuilder(UUID builderId);
    
    /**
     * Get visible construction progress for a builder.
     * Used for scaffolding/progress indicators.
     * 
     * @param builderId The builder to query
     * @return Progress snapshot (layer, row, percent complete)
     */
    Optional<ConstructionProgress> getProgress(UUID builderId);
    
    /**
     * Snapshot of visible construction progress.
     */
    record ConstructionProgress(int currentLayer, int currentRow, double percentComplete) {}
}
