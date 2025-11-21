package com.davisodom.villageoverhaul.worldgen;

import com.davisodom.villageoverhaul.model.PlacementReceipt;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for loading and placing structure templates.
 * Supports both FAWE (fast) and Paper API (fallback) placement methods.
 */
public interface StructureService {
    
    /**
     * Load a structure template from file.
     * Supports Sponge Schematic v2 format (.schem files).
     * 
     * @param schematicFile Path to schematic file
     * @return true if loaded successfully
     */
    boolean loadStructure(File schematicFile);
    
    /**
     * Place a loaded structure at the specified location.
     * Automatically selects FAWE or Paper API based on availability.
     * 
     * @param structureId Structure identifier (from schema)
     * @param world Target world
     * @param origin Placement origin (southwest corner, ground level)
     * @param seed Deterministic seed for randomized blocks (e.g., vegetation variants)
     * @return true if placement successful
     * @deprecated Use placeStructureAndGetReceipt() for ground-truth placement data
     */
    @Deprecated
    boolean placeStructure(String structureId, World world, Location origin, long seed);
    
    /**
     * Place a loaded structure at the specified location, returning the actual placed location.
     * This method attempts placement with re-seating logic and returns the final location if successful.
     * 
     * @param structureId Structure identifier (from schema)
     * @param world Target world
     * @param origin Requested placement origin (may be adjusted during re-seating)
     * @param seed Deterministic seed for randomized blocks
     * @return Optional containing the actual placed location, empty if placement failed
     * @deprecated Use placeStructureAndGetReceipt() instead
     */
    @Deprecated
    Optional<Location> placeStructureAndGetLocation(String structureId, World world, Location origin, long seed);
    
    /**
     * Place a loaded structure at the specified location, returning complete placement information.
     * This method attempts placement with re-seating logic and returns location + rotation if successful.
     * T021b: Critical for accurate building footprint tracking.
     * 
     * @param structureId Structure identifier (from schema)
     * @param world Target world
     * @param origin Requested placement origin (may be adjusted during re-seating)
     * @param seed Deterministic seed for randomized blocks and rotation
     * @return Optional containing PlacementResult with actual location and rotation, empty if placement failed
     * @deprecated Use placeStructureAndGetReceipt() instead
     */
    @Deprecated
    Optional<PlacementResult> placeStructureAndGetResult(String structureId, World world, Location origin, long seed);

    /**
     * Place structure and return a PlacementReceipt with canonical transform and proof.
     * This is the R001 implementation that replaces ambiguous logs with ground-truth data.
     * 
     * @param structureId Structure ID to place
     * @param world Target world
     * @param origin Initial placement origin
     * @param seed Deterministic seed
     * @param villageId Village ID for receipt
     * @return Optional PlacementReceipt with exact bounds and corner samples
     */
    Optional<PlacementReceipt> placeStructureAndGetReceipt(
            String structureId, World world, Location origin, long seed, UUID villageId);
    
    /**
     * Get the dimensions of a loaded structure.
     * 
     * @param structureId Structure identifier
     * @return [width, height, depth] in blocks, empty if not loaded
     */
    Optional<int[]> getStructureDimensions(String structureId);
    
    /**
     * Validate site foundation for structure placement.
     * Checks for sufficient solid blocks, acceptable slope, and interior clearance.
     * 
     * @param world Target world
     * @param origin Proposed placement origin
     * @param dimensions [width, height, depth] of structure
     * @param solidityPercent Minimum fraction of foundation that must be solid (0.0-1.0)
     * @return true if foundation meets requirements
     */
    boolean validateFoundation(World world, Location origin, int[] dimensions, double solidityPercent);
    
    /**
     * Perform minor terraforming to improve placement site.
     * Light grading/filling within configured radius (default 3 blocks).
     * 
     * @param world Target world
     * @param origin Structure origin
     * @param dimensions [width, height, depth] of structure
     * @param maxRadius Maximum terraforming radius in blocks
     * @return List of blocks modified (for logging/debugging)
     */
    List<Block> performMinorTerraforming(World world, Location origin, int[] dimensions, int maxRadius);
    
    /**
     * Clear vegetation (grass, flowers, saplings) in structure footprint.
     * 
     * @param world Target world
     * @param origin Structure origin
     * @param dimensions [width, height, depth] of structure
     * @return Number of blocks cleared
     */
    int clearVegetation(World world, Location origin, int[] dimensions);
    
    /**
     * Check if FAWE is available for fast placement.
     * Falls back to Paper API block-by-block if false.
     * 
     * @return true if FAWE is loaded and enabled
     */
    boolean isFAWEAvailable();
    
    /**
     * Reload all structure templates from disk.
     * Used for hot-reloading during development/testing.
     * 
     * @return Number of structures reloaded
     */
    int reloadStructures();
}

