package com.example.villageoverhaul.villages;

import java.util.UUID;

/**
 * Minimal Village model for Phase 2.5 bootstrap
 * Full implementation will be completed in Phase 3 (US1)
 */
public class Village {
    private final UUID id;
    private final String cultureId;
    private final String name;
    private long wealthMillz;
    
    public Village(UUID id, String cultureId, String name) {
        this.id = id;
        this.cultureId = cultureId;
        this.name = name;
        this.wealthMillz = 0L;
    }
    
    public UUID getId() { return id; }
    public String getCultureId() { return cultureId; }
    public String getName() { return name; }
    public long getWealthMillz() { return wealthMillz; }
    
    public void addWealth(long millz) {
        if (millz > 0) {
            this.wealthMillz += millz;
        }
    }
}
