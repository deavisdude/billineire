package com.davisodom.villageoverhaul.builders;

import com.davisodom.villageoverhaul.model.MaterialRequest;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for managing material requests, allocations, and consumption
 * for NPC builders. Coordinates inventories between builders and village storage/warehouses.
 * 
 * All operations are server-authoritative and audited per Constitution XI.
 * Material flow: request → allocate → pickup → consume, with full audit trail.
 */
public interface MaterialManager {
    
    /**
     * Create a material request for a builder.
     * Request is queued and will be allocated from available village storage.
     * 
     * @param builderId The builder requesting materials
     * @param villageId The village this builder belongs to
     * @param items Map of material type to quantity needed
     * @return CompletableFuture containing the created MaterialRequest
     */
    CompletableFuture<MaterialRequest> requestMaterials(UUID builderId, UUID villageId, Map<String, Integer> items);
    
    /**
     * Attempt to allocate materials from village storage for a pending request.
     * Marks items as allocated (reserved) but not yet picked up.
     * 
     * @param requestId The material request to allocate for
     * @return CompletableFuture that resolves to true if allocation succeeded, false if insufficient materials
     */
    CompletableFuture<Boolean> allocateMaterials(UUID requestId);
    
    /**
     * Mark materials as picked up by the builder.
     * Transfers items from warehouse/chest to builder's virtual inventory.
     * 
     * @param requestId The material request being fulfilled
     * @param warehouseLocation The location the builder picked up from
     * @return CompletableFuture containing the picked-up ItemStacks
     */
    CompletableFuture<List<ItemStack>> pickupMaterials(UUID requestId, Location warehouseLocation);
    
    /**
     * Record consumption of materials during block placement.
     * Decrements builder's virtual inventory and logs the consumption.
     * 
     * @param builderId The builder consuming materials
     * @param items Map of material type to quantity consumed
     * @return CompletableFuture that completes when consumption is recorded
     */
    CompletableFuture<Void> consumeMaterials(UUID builderId, Map<String, Integer> items);
    
    /**
     * Cancel a pending material request.
     * Releases any allocated materials back to village storage.
     * 
     * @param requestId The request to cancel
     * @param reason Why the request is being cancelled
     * @return CompletableFuture that completes when cancellation is processed
     */
    CompletableFuture<Void> cancelRequest(UUID requestId, String reason);
    
    /**
     * Get the current state of a material request.
     * 
     * @param requestId The request to query
     * @return Optional containing the MaterialRequest if found
     */
    Optional<MaterialRequest> getRequest(UUID requestId);
    
    /**
     * Get all material requests for a builder, optionally filtered by status.
     * 
     * @param builderId The builder to query
     * @param status Optional status filter (null for all statuses)
     * @return List of material requests matching the criteria
     */
    List<MaterialRequest> getRequestsForBuilder(UUID builderId, MaterialRequest.Status status);
    
    /**
     * Get the builder's current virtual inventory.
     * Materials picked up but not yet consumed.
     * 
     * @param builderId The builder to query
     * @return Map of material type to quantity in inventory
     */
    Map<String, Integer> getBuilderInventory(UUID builderId);
    
    /**
     * Find the nearest warehouse/chest with available materials.
     * Used during GATHERING_MATERIALS state for pathfinding target.
     * 
     * @param villageId The village to search
     * @param items Materials needed
     * @param builderLocation Current builder location for distance calculation
     * @return Optional containing the warehouse location if materials are available
     */
    Optional<Location> findNearestWarehouse(UUID villageId, Map<String, Integer> items, Location builderLocation);
    
    /**
     * Check if village storage has sufficient materials for a request.
     * Does not allocate; just queries availability.
     * 
     * @param villageId The village to check
     * @param items Materials to check for
     * @return true if all items are available in sufficient quantity
     */
    boolean hasAvailableMaterials(UUID villageId, Map<String, Integer> items);
    
    /**
     * Get an audit trail of all material operations for a builder.
     * Used for debugging and anti-exploit verification.
     * 
     * @param builderId The builder to audit
     * @param limit Maximum number of entries to return
     * @return List of audit entries, newest first
     */
    List<MaterialAuditEntry> getAuditTrail(UUID builderId, int limit);
    
    /**
     * Audit entry for material operations.
     * Immutable record with timestamp and operation details.
     */
    record MaterialAuditEntry(
        long timestamp,
        UUID builderId,
        UUID requestId,
        String operation, // "REQUEST", "ALLOCATE", "PICKUP", "CONSUME", "CANCEL"
        Map<String, Integer> items,
        String notes
    ) {}
}
