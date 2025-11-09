package com.davisodom.villageoverhaul.villages;

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

    // Minimal location metadata for world presence
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;

    public Village(UUID id, String cultureId, String name, String worldName, int x, int y, int z) {
        this.id = id;
        this.cultureId = cultureId;
        this.name = name;
        this.wealthMillz = 0L;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public UUID getId() { return id; }
    public String getCultureId() { return cultureId; }
    public String getName() { return name; }
    public long getWealthMillz() { return wealthMillz; }
    public String getWorldName() { return worldName; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public void addWealth(long millz) {
        if (millz > 0) {
            this.wealthMillz += millz;
        }
    }
}

