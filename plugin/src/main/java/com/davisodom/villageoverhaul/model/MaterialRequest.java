package com.davisodom.villageoverhaul.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a material request from a builder to village storage.
 * Tracks the lifecycle: PENDING → ALLOCATED → PICKED_UP → CONSUMED.
 * Immutable value object with server-authoritative audit trail.
 */
public class MaterialRequest {
    
    /**
     * Lifecycle status of a material request.
     */
    public enum Status {
        PENDING,    // Request created, awaiting allocation
        ALLOCATED,  // Materials reserved in storage, awaiting pickup
        PICKED_UP,  // Materials in builder's inventory, awaiting consumption
        CONSUMED,   // Materials used during construction
        CANCELLED   // Request cancelled before completion
    }
    
    private final UUID requestId;
    private final UUID builderId;
    private final UUID villageId;
    private final Map<String, Integer> items; // materialType -> quantity
    private final Status status;
    private final long createdAt;
    private final long lastUpdatedAt;
    private final String warehouseRef; // chest/location identifier for pickup
    private final String cancellationReason;
    
    private MaterialRequest(UUID requestId, UUID builderId, UUID villageId, Map<String, Integer> items,
                           Status status, long createdAt, long lastUpdatedAt, String warehouseRef,
                           String cancellationReason) {
        this.requestId = Objects.requireNonNull(requestId, "requestId cannot be null");
        this.builderId = Objects.requireNonNull(builderId, "builderId cannot be null");
        this.villageId = Objects.requireNonNull(villageId, "villageId cannot be null");
        this.items = Collections.unmodifiableMap(new HashMap<>(items));
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.warehouseRef = warehouseRef;
        this.cancellationReason = cancellationReason;
        
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
        if (status == Status.CANCELLED && cancellationReason == null) {
            throw new IllegalArgumentException("cancellationReason required when status is CANCELLED");
        }
    }
    
    public UUID getRequestId() { return requestId; }
    public UUID getBuilderId() { return builderId; }
    public UUID getVillageId() { return villageId; }
    public Map<String, Integer> getItems() { return items; }
    public Status getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }
    public long getLastUpdatedAt() { return lastUpdatedAt; }
    public String getWarehouseRef() { return warehouseRef; }
    public String getCancellationReason() { return cancellationReason; }
    
    /**
     * Check if this request is still active (not consumed or cancelled).
     */
    public boolean isActive() {
        return status != Status.CONSUMED && status != Status.CANCELLED;
    }
    
    /**
     * Check if this request is ready for pickup.
     */
    public boolean isReadyForPickup() {
        return status == Status.ALLOCATED && warehouseRef != null;
    }
    
    /**
     * Calculate total quantity of all items.
     */
    public int getTotalQuantity() {
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }
    
    /**
     * Create a new request with updated status.
     * Immutable pattern: returns a new instance.
     */
    public MaterialRequest withStatus(Status newStatus, long timestamp) {
        return new MaterialRequest(requestId, builderId, villageId, items, newStatus,
                                   createdAt, timestamp, warehouseRef, cancellationReason);
    }
    
    /**
     * Create a new request with warehouse reference (on allocation).
     */
    public MaterialRequest withWarehouse(String warehouseRef, long timestamp) {
        return new MaterialRequest(requestId, builderId, villageId, items, Status.ALLOCATED,
                                   createdAt, timestamp, warehouseRef, cancellationReason);
    }
    
    /**
     * Create a new request marked as cancelled.
     */
    public MaterialRequest asCancelled(String reason, long timestamp) {
        return new MaterialRequest(requestId, builderId, villageId, items, Status.CANCELLED,
                                   createdAt, timestamp, warehouseRef, reason);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MaterialRequest that = (MaterialRequest) o;
        return requestId.equals(that.requestId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(requestId);
    }
    
    @Override
    public String toString() {
        return "MaterialRequest{id=" + requestId + ", builder=" + builderId + ", status=" + status +
               ", items=" + items + ", warehouse=" + warehouseRef + "}";
    }
    
    /**
     * Builder pattern for creating new MaterialRequest instances.
     */
    public static class MaterialRequestBuilder {
        private UUID requestId;
        private UUID builderId;
        private UUID villageId;
        private Map<String, Integer> items = new HashMap<>();
        private Status status = Status.PENDING;
        private long createdAt = System.currentTimeMillis();
        private long lastUpdatedAt = System.currentTimeMillis();
        private String warehouseRef = null;
        private String cancellationReason = null;
        
        public MaterialRequestBuilder requestId(UUID requestId) {
            this.requestId = requestId;
            return this;
        }
        
        public MaterialRequestBuilder builderId(UUID builderId) {
            this.builderId = builderId;
            return this;
        }
        
        public MaterialRequestBuilder villageId(UUID villageId) {
            this.villageId = villageId;
            return this;
        }
        
        public MaterialRequestBuilder items(Map<String, Integer> items) {
            this.items = items;
            return this;
        }
        
        public MaterialRequestBuilder addItem(String materialType, int quantity) {
            this.items.put(materialType, quantity);
            return this;
        }
        
        public MaterialRequestBuilder status(Status status) {
            this.status = status;
            return this;
        }
        
        public MaterialRequestBuilder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }
        
        public MaterialRequestBuilder lastUpdatedAt(long lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
            return this;
        }
        
        public MaterialRequestBuilder warehouseRef(String warehouseRef) {
            this.warehouseRef = warehouseRef;
            return this;
        }
        
        public MaterialRequestBuilder cancellationReason(String cancellationReason) {
            this.cancellationReason = cancellationReason;
            return this;
        }
        
        public MaterialRequest build() {
            return new MaterialRequest(requestId, builderId, villageId, items, status,
                                      createdAt, lastUpdatedAt, warehouseRef, cancellationReason);
        }
    }
    
    public static MaterialRequestBuilder builder() {
        return new MaterialRequestBuilder();
    }
}
