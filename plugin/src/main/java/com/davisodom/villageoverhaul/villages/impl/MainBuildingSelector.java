package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.cultures.CultureService;
import com.davisodom.villageoverhaul.model.Building;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Selects the main building for a village based on culture configuration.
 * 
 * The main building is determined by the culture's mainBuildingStructureId field.
 * If not specified in the culture, defaults to the first structure in the culture's structureSet.
 * 
 * Per Constitution and research.md: "One per village per culture, persisted in village metadata;
 * consistent selection from structure set using seeded choice + constraints (centrality/proximity to paths)."
 * 
 * For now, this implementation uses the culture-defined structure ID only. Future enhancements
 * may add constraints like centrality or proximity to paths.
 */
public class MainBuildingSelector {
    
    private final Logger logger;
    private final CultureService cultureService;
    
    public MainBuildingSelector(Logger logger, CultureService cultureService) {
        this.logger = logger;
        this.cultureService = cultureService;
    }
    
    /**
     * Select the main building from a list of placed buildings for a given culture.
     * 
     * The selection logic:
     * 1. Get the culture's mainBuildingStructureId (or first structure if not specified)
     * 2. Find the first building matching that structure ID
     * 3. If no match found, log warning and return empty
     * 
     * @param cultureId Culture ID for this village
     * @param buildings List of buildings placed in the village
     * @return UUID of the selected main building, or empty if none found
     */
    public Optional<UUID> selectMainBuilding(String cultureId, List<Building> buildings) {
        if (buildings == null || buildings.isEmpty()) {
            logger.fine("[STRUCT] No buildings available for main building selection");
            return Optional.empty();
        }
        
        // Get culture definition
        Optional<CultureService.Culture> cultureOpt = cultureService.get(cultureId);
        if (!cultureOpt.isPresent()) {
            logger.warning(String.format("[STRUCT] Culture '%s' not found for main building selection", cultureId));
            return Optional.empty();
        }
        
        CultureService.Culture culture = cultureOpt.get();
        String targetStructureId = culture.getMainBuildingStructureId();
        
        // If no mainBuildingStructureId specified, use first structure in structureSet
        if (targetStructureId == null || targetStructureId.isEmpty()) {
            List<String> structureSet = culture.getStructureSet();
            if (structureSet == null || structureSet.isEmpty()) {
                logger.warning(String.format("[STRUCT] Culture '%s' has no structures defined", cultureId));
                return Optional.empty();
            }
            targetStructureId = structureSet.get(0);
            logger.fine(String.format("[STRUCT] No mainBuildingStructureId specified for culture '%s', using first structure: %s", 
                cultureId, targetStructureId));
        }
        
        // Find first building matching the target structure ID
        final String finalTargetId = targetStructureId;
        Optional<Building> mainBuilding = buildings.stream()
                .filter(b -> finalTargetId.equals(b.getStructureId()))
                .findFirst();
        
        if (mainBuilding.isPresent()) {
            UUID buildingId = mainBuilding.get().getBuildingId();
            logger.info(String.format("[STRUCT] Selected main building: %s (structure: %s) for culture: %s", 
                buildingId, finalTargetId, cultureId));
            return Optional.of(buildingId);
        } else {
            logger.warning(String.format("[STRUCT] No building found matching main building structure '%s' for culture '%s'", 
                finalTargetId, cultureId));
            return Optional.empty();
        }
    }
    
    /**
     * Verify that a building is the designated main building structure for a culture.
     * 
     * @param cultureId Culture ID to check
     * @param building Building to verify
     * @return true if this building matches the culture's main building structure ID
     */
    public boolean isMainBuildingStructure(String cultureId, Building building) {
        if (building == null) {
            return false;
        }
        
        Optional<CultureService.Culture> cultureOpt = cultureService.get(cultureId);
        if (!cultureOpt.isPresent()) {
            return false;
        }
        
        CultureService.Culture culture = cultureOpt.get();
        String targetStructureId = culture.getMainBuildingStructureId();
        
        // Default to first structure if not specified
        if (targetStructureId == null || targetStructureId.isEmpty()) {
            List<String> structureSet = culture.getStructureSet();
            if (structureSet == null || structureSet.isEmpty()) {
                return false;
            }
            targetStructureId = structureSet.get(0);
        }
        
        return targetStructureId.equals(building.getStructureId());
    }
}
