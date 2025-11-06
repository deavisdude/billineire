package com.davisodom.villageoverhaul;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Debug flags and structured logging utilities.
 * Provides [STRUCT] logging for structure placement observability.
 */
public class DebugFlags {
    
    private static boolean debugStructures = false;
    private static boolean debugPaths = false;
    private static boolean debugTerraforming = false;
    private static boolean debugPerformance = false;
    private static Logger logger;
    
    /**
     * Initialize debug flags from plugin configuration.
     */
    public static void initialize(Plugin plugin) {
        logger = plugin.getLogger();
        FileConfiguration config = plugin.getConfig();
        
        debugStructures = config.getBoolean("debug.structures", false);
        debugPaths = config.getBoolean("debug.paths", false);
        debugTerraforming = config.getBoolean("debug.terraforming", false);
        debugPerformance = config.getBoolean("debug.performance", false);
        
        if (isAnyDebugEnabled()) {
            logger.info("[STRUCT] Debug flags initialized: structures=" + debugStructures + 
                       ", paths=" + debugPaths + 
                       ", terraforming=" + debugTerraforming + 
                       ", performance=" + debugPerformance);
        }
    }
    
    public static boolean isDebugStructures() { return debugStructures; }
    public static boolean isDebugPaths() { return debugPaths; }
    public static boolean isDebugTerraforming() { return debugTerraforming; }
    public static boolean isDebugPerformance() { return debugPerformance; }
    
    public static boolean isAnyDebugEnabled() {
        return debugStructures || debugPaths || debugTerraforming || debugPerformance;
    }
    
    /**
     * Log structure placement event with [STRUCT] marker.
     */
    public static void logStructure(String message) {
        if (logger != null) {
            logger.info("[STRUCT] " + message);
        }
    }
    
    /**
     * Log structure placement error with [STRUCT] marker.
     */
    public static void logStructureError(String message) {
        if (logger != null) {
            logger.warning("[STRUCT] ERROR: " + message);
        }
    }
    
    /**
     * Log structure placement event if debug enabled.
     */
    public static void debugStructure(String message) {
        if (debugStructures && logger != null) {
            logger.info("[STRUCT] DEBUG: " + message);
        }
    }
    
    /**
     * Log path generation event with [STRUCT] marker.
     */
    public static void logPath(String message) {
        if (logger != null) {
            logger.info("[STRUCT] Path: " + message);
        }
    }
    
    /**
     * Log path generation event if debug enabled.
     */
    public static void debugPath(String message) {
        if (debugPaths && logger != null) {
            logger.info("[STRUCT] Path DEBUG: " + message);
        }
    }
    
    /**
     * Log terraforming event if debug enabled.
     */
    public static void debugTerraforming(String message) {
        if (debugTerraforming && logger != null) {
            logger.info("[STRUCT] Terraforming: " + message);
        }
    }
    
    /**
     * Log performance metric with [STRUCT] marker.
     */
    public static void logPerformance(String operation, long durationMs) {
        if (logger != null) {
            logger.info(String.format("[STRUCT] Performance: %s took %dms", operation, durationMs));
        }
    }
    
    /**
     * Log performance metric if debug enabled.
     */
    public static void debugPerformance(String operation, long durationMs) {
        if (debugPerformance && logger != null) {
            logger.info(String.format("[STRUCT] Performance DEBUG: %s took %dms", operation, durationMs));
        }
    }
    
    /**
     * Log site validation result.
     */
    public static void logSiteValidation(String result, String reason) {
        if (logger != null) {
            logger.info(String.format("[STRUCT] Site validation: %s (%s)", result, reason));
        }
    }
    
    /**
     * Log building placement summary.
     */
    public static void logBuildingPlaced(String structureId, String location, int blocksPlaced) {
        if (logger != null) {
            logger.info(String.format("[STRUCT] Building placed: %s at %s (%d blocks)", 
                structureId, location, blocksPlaced));
        }
    }
    
    /**
     * Log village generation summary.
     */
    public static void logVillageGenerated(String villageId, String cultureId, int buildingCount, long durationMs) {
        if (logger != null) {
            logger.info(String.format("[STRUCT] Village generated: %s (culture: %s, buildings: %d, took %dms)", 
                villageId, cultureId, buildingCount, durationMs));
        }
    }
}
