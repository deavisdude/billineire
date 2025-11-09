package com.davisodom.villageoverhaul.projects;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.Village;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.logging.Logger;

/**
 * Executes building upgrades when projects complete.
 * 
 * For US1 MVP, this places a simple visual marker (beacon + blocks).
 * Production version will integrate with:
 * - FAWE for efficient structure placement
 * - WorldGuard for region protection
 * - Structure templates from datapacks
 * 
 * Thread-safe: upgrade execution is scheduled on main thread.
 */
public class UpgradeExecutor {
    
    private final VillageOverhaulPlugin plugin;
    private final Logger logger;
    
    public UpgradeExecutor(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }
    
    /**
     * Execute an upgrade for a completed project
     * 
     * @param project The completed project
     * @param village The village that owns the project
     */
    public void executeUpgrade(Project project, Village village) {
        if (project.getStatus() != Project.Status.COMPLETE) {
            logger.warning("Cannot execute upgrade for non-complete project: " + project.getId());
            return;
        }
        
        // Schedule on main thread (Bukkit API requirement)
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                performUpgrade(project, village);
            } catch (Exception e) {
                logger.severe("Failed to execute upgrade for project " + project.getId() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Perform the actual upgrade (main thread)
     */
    private void performUpgrade(Project project, Village village) {
        logger.info("Executing upgrade for project: " + project.getBuildingRef() + 
                " in village: " + village.getName());
        
        World world = Bukkit.getWorld(village.getWorldName());
        if (world == null) {
            logger.severe("Cannot execute upgrade: world not found: " + village.getWorldName());
            return;
        }
        
        // Get village center
        int vx = village.getX();
        int vy = village.getY();
        int vz = village.getZ();
        
        // For US1 MVP: place a simple upgrade marker
        // Production: load structure template and place with FAWE
        placeUpgradeMarker(world, vx, vy, vz, project.getBuildingRef());
        
        // Apply unlock effects
        for (String effect : project.getUnlockEffects()) {
            applyUnlockEffect(village, effect);
        }
        
    logger.info("OK Upgrade completed: " + project.getBuildingRef() + 
        " at (" + vx + "," + vy + "," + vz + ")");
    }
    
    /**
     * Place a visual marker for the upgrade (MVP implementation)
     * 
     * Production version will:
     * - Load structure from datapack
     * - Use FAWE for efficient async placement
     * - Handle undo/rollback
     * - Respect WorldGuard regions
     */
    private void placeUpgradeMarker(World world, int x, int y, int z, String buildingRef) {
        // Offset slightly from village center
        int buildX = x + 5;
        int buildY = world.getHighestBlockYAt(buildX, z);
        int buildZ = z;
        
        // Place a beacon with a gold block base (visible upgrade marker)
        safeSet(world, buildX, buildY, buildZ, Material.GOLD_BLOCK);
        safeSet(world, buildX, buildY + 1, buildZ, Material.BEACON);
        
        // Add decorative blocks in a small cross pattern
        safeSet(world, buildX + 1, buildY, buildZ, Material.IRON_BLOCK);
        safeSet(world, buildX - 1, buildY, buildZ, Material.IRON_BLOCK);
        safeSet(world, buildX, buildY, buildZ + 1, Material.IRON_BLOCK);
        safeSet(world, buildX, buildY, buildZ - 1, Material.IRON_BLOCK);
        
        logger.info("Placed upgrade marker for: " + buildingRef + 
                " at (" + buildX + "," + buildY + "," + buildZ + ")");
    }
    
    /**
     * Apply an unlock effect from project completion
     * 
     * Effects format: "type:value"
     * Examples:
     * - "trade_slots:+2" → unlock additional trade slots
     * - "profession:master_blacksmith" → unlock profession
     * - "defense:+1" → increase village defense rating
     */
    private void applyUnlockEffect(Village village, String effect) {
        String[] parts = effect.split(":", 2);
        if (parts.length != 2) {
            logger.warning("Invalid unlock effect format: " + effect);
            return;
        }
        
        String type = parts[0];
        String value = parts[1];
        
        logger.info("Applied unlock effect: " + type + " = " + value + " to village: " + village.getName());
        
        // Effects will be fully implemented in subsequent phases
        // For now, just log them
        switch (type) {
            case "trade_slots":
                logger.info("  → Trade slots increased by: " + value);
                break;
            case "profession":
                logger.info("  → Unlocked profession: " + value);
                break;
            case "defense":
                logger.info("  → Defense rating increased by: " + value);
                break;
            default:
                logger.warning("  → Unknown effect type: " + type);
        }
    }
    
    private void safeSet(World world, int x, int y, int z, Material material) {
        try {
            world.getBlockAt(x, y, z).setType(material, false);
        } catch (Throwable t) {
            logger.warning("Failed to place block at (" + x + "," + y + "," + z + "): " + t.getMessage());
        }
    }
}

