package com.davisodom.villageoverhaul.villages;

import com.davisodom.villageoverhaul.model.Building;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for village placement and structure generation.
 * Coordinates site validation, structure placement, and village metadata.
 */
public interface VillagePlacementService {
    
    /**
     * Attempt to place a new village at the specified location.
     * 
     * @param world Target world
     * @param origin Village center/spawn point
     * @param cultureId Culture identifier (e.g., "roman", "medieval")
     * @param seed Deterministic seed for structure selection and placement
     * @return Village UUID if successful, empty if placement failed
     */
    Optional<UUID> placeVillage(World world, Location origin, String cultureId, long seed);
    
    /**
     * Validate if a location is suitable for village placement.
     * Checks terrain slope, foundation solidity, collision clearance.
     * 
     * @param world Target world
     * @param origin Proposed village center
     * @param radius Validation radius in blocks
     * @return true if site is valid for placement
     */
    boolean validateSite(World world, Location origin, int radius);
    
    /**
     * Place a single building structure at a specific location.
     * Handles site validation, optional terraforming, and FAWE/Paper API placement.
     * 
     * @param world Target world
     * @param location Building origin (southwest corner, ground level)
     * @param structureId Structure template identifier
     * @param villageId Parent village UUID (for metadata association)
     * @param seed Deterministic seed for placement variations
     * @return Building metadata if successful, empty if placement failed
     */
    Optional<Building> placeBuilding(World world, Location location, String structureId, UUID villageId, long seed);
    
    /**
     * Get all buildings belonging to a specific village.
     * 
     * @param villageId Village UUID
     * @return List of buildings in this village (may be empty)
     */
    List<Building> getVillageBuildings(UUID villageId);
    
    /**
     * Check if a location collides with existing village structures.
     * 
     * @param world Target world
     * @param location Center of proposed structure
     * @param radius Collision check radius
     * @return true if collision detected, false if clear
     */
    boolean hasCollision(World world, Location location, int radius);
    
    /**
     * Remove a village and all its structures.
     * For testing and debugging only.
     * 
     * @param villageId Village UUID to remove
     * @return true if removal successful
     */
    boolean removeVillage(UUID villageId);
}

