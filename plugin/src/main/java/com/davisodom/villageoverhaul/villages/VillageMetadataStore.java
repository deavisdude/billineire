package com.davisodom.villageoverhaul.villages;

import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.model.PathNetwork;
import com.davisodom.villageoverhaul.persistence.JsonStore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory store for village metadata with disk persistence.
 * Tracks buildings, path networks, main building designations, and dynamic borders.
 * Thread-safe for concurrent access.
 */
public class VillageMetadataStore {
    
    private final Plugin plugin;
    private final Logger logger;
    private final File storageDir;
    private final JsonStore jsonStore;
    private final File diagnosticsDir;
    private final JsonStore diagnosticsStore;
    
    // In-memory caches (thread-safe)
    private final Map<UUID, VillageMetadata> villages = new ConcurrentHashMap<>();
    private final Map<UUID, List<Building>> villageBuildings = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> mainBuildings = new ConcurrentHashMap<>(); // villageId -> mainBuildingId
    private final Map<UUID, PathNetwork> pathNetworks = new ConcurrentHashMap<>();
    private final Map<UUID, List<VillagerRecord>> villageVillagers = new ConcurrentHashMap<>();
    
    // R001: PlacementReceipt storage (villageId -> list of receipts)
    private final Map<UUID, List<com.davisodom.villageoverhaul.model.PlacementReceipt>> placementReceipts = new ConcurrentHashMap<>();
    
    // R002: VolumeMask storage (villageId -> list of volume masks)
    private final Map<UUID, List<com.davisodom.villageoverhaul.model.VolumeMask>> volumeMasks = new ConcurrentHashMap<>();

    // T026d11: Last run placement failure summaries (for diagnostic harvesting)
    // Populated when a village run yields ZERO placements so harness can attach artifacts
    private final Map<UUID, PlacementFailureSummary> lastPlacementFailureSummary = new ConcurrentHashMap<>();
    // T026d12: Persisted per-run placement rejection counters (villageId -> counters)
    private final Map<UUID, PlacementRejectionCounters> placementRejectionCounters = new ConcurrentHashMap<>();
    // T077: Persisted candidate coverage summaries (villageId -> coverage)
    private final Map<UUID, CandidateCoverageSummary> candidateCoverageSummaries = new ConcurrentHashMap<>();
    
    public VillageMetadataStore(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storageDir = new File(plugin.getDataFolder(), "villages");
        this.jsonStore = new JsonStore(storageDir, logger);
        this.diagnosticsDir = new File(plugin.getDataFolder(), "diagnostics");
        this.diagnosticsStore = new JsonStore(diagnosticsDir, logger);
        
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        if (!diagnosticsDir.exists()) {
            diagnosticsDir.mkdirs();
        }
    }
    
    /**
     * Register a new village.
     */
    public void registerVillage(UUID villageId, String cultureId, Location origin, long seed) {
        VillageMetadata metadata = new VillageMetadata(villageId, cultureId, origin, seed, System.currentTimeMillis(), null);
        villages.put(villageId, metadata);
        villageBuildings.put(villageId, new ArrayList<>());
        villageVillagers.putIfAbsent(villageId, new ArrayList<>());
        logger.info(String.format("[STRUCT] Registered village %s (culture: %s) at %s", 
            villageId, cultureId, formatLocation(origin)));

        // Ensure a placement rejection counters artifact exists for this village
        try {
            PlacementRejectionCounters counters = new PlacementRejectionCounters(0,0,0,0,0,0,0,0,0,0);
            recordPlacementRejectionCounters(villageId, counters);
        } catch (Exception e) {
            logger.fine(String.format("[STRUCT][DIAG] failed to write initial placement counters for %s: %s", villageId, e.getMessage()));
        }
    }

    public void setVillageName(UUID villageId, String villageName) {
        VillageMetadata metadata = villages.get(villageId);
        if (metadata != null) {
            metadata.setVillageName(villageName);
        }
    }
    
    /**
     * Add a building to a village.
     */
    public void addBuilding(UUID villageId, Building building) {
        villageBuildings.computeIfAbsent(villageId, k -> new ArrayList<>()).add(building);
        
        // Update village border to include this building
        VillageMetadata metadata = villages.get(villageId);
        if (metadata != null) {
            metadata.expandBorderForBuilding(building);
        }
        
        logger.fine(String.format("[STRUCT] Added building %s to village %s", 
            building.getStructureId(), villageId));
    }
    
    /**
     * Get all buildings for a village.
     */
    public List<Building> getVillageBuildings(UUID villageId) {
        return new ArrayList<>(villageBuildings.getOrDefault(villageId, Collections.emptyList()));
    }

    /**
     * Record candidate coverage summary for a village placement run.
     */
    public void recordCandidateCoverageSummary(UUID villageId, CandidateCoverageSummary summary) {
        if (villageId == null || summary == null) {
            return;
        }
        candidateCoverageSummaries.put(villageId, summary);
    }

    /**
     * Get candidate coverage summary for a village.
     */
    public Optional<CandidateCoverageSummary> getCandidateCoverageSummary(UUID villageId) {
        return Optional.ofNullable(candidateCoverageSummaries.get(villageId));
    }

    /**
     * Record a spawned villager for persistence.
     */
    public void addVillagerRecord(VillagerRecord record) {
        if (record == null || record.villageId == null) return;
        villageVillagers.computeIfAbsent(record.villageId, k -> new ArrayList<>()).add(record);
    }

    /**
     * Remove a villager record by entity ID.
     */
    public void removeVillagerRecord(UUID villageId, UUID entityId) {
        if (villageId == null || entityId == null) return;
        List<VillagerRecord> records = villageVillagers.get(villageId);
        if (records == null) return;
        records.removeIf(r -> entityId.toString().equals(r.entityId));
    }

    /**
     * Remove a villager record by definition and location (fallback for missing entity IDs).
     */
    public void removeVillagerRecord(UUID villageId, String definitionId, String professionId, int x, int y, int z) {
        if (villageId == null) return;
        List<VillagerRecord> records = villageVillagers.get(villageId);
        if (records == null) return;
        records.removeIf(r -> Objects.equals(definitionId, r.definitionId)
            && Objects.equals(professionId, r.professionId)
            && r.x == x && r.y == y && r.z == z);
    }

    /**
     * Get all villager records for a village.
     */
    public List<VillagerRecord> getVillagerRecords(UUID villageId) {
        return new ArrayList<>(villageVillagers.getOrDefault(villageId, Collections.emptyList()));
    }

    /**
     * Get all villager records across villages.
     */
    public List<VillagerRecord> getAllVillagerRecords() {
        List<VillagerRecord> all = new ArrayList<>();
        for (List<VillagerRecord> records : villageVillagers.values()) {
            all.addAll(records);
        }
        return all;
    }
    
    /**
     * Designate a building as the main building for a village.
     */
    public void setMainBuilding(UUID villageId, UUID buildingId) {
        mainBuildings.put(villageId, buildingId);
        logger.info(String.format("[STRUCT] Designated building %s as main building for village %s", 
            buildingId, villageId));
    }
    
    /**
     * Get the main building for a village.
     */
    public Optional<UUID> getMainBuilding(UUID villageId) {
        return Optional.ofNullable(mainBuildings.get(villageId));
    }
    
    /**
     * Store path network for a village.
     */
    public void setPathNetwork(UUID villageId, PathNetwork pathNetwork) {
        pathNetworks.put(villageId, pathNetwork);
        logger.fine(String.format("[STRUCT] Stored path network for village %s", villageId));
    }
    
    /**
     * Get path network for a village.
     */
    public Optional<PathNetwork> getPathNetwork(UUID villageId) {
        return Optional.ofNullable(pathNetworks.get(villageId));
    }
    
    /**
     * R001: Add a placement receipt for a building in a village.
     */
    public void addPlacementReceipt(UUID villageId, com.davisodom.villageoverhaul.model.PlacementReceipt receipt) {
        placementReceipts.computeIfAbsent(villageId, k -> new ArrayList<>()).add(receipt);
        logger.fine(String.format("[STRUCT][RECEIPT] Stored receipt for structure %s in village %s", 
            receipt.getStructureId(), villageId));

        // Ensure placement rejection counters artifact exists when receipts are first added
        // This guards fixed-layout and other flows that may add receipts after registerVillage
        if (!placementRejectionCounters.containsKey(villageId)) {
            try {
                PlacementRejectionCounters counters = new PlacementRejectionCounters(0,0,0,0,0,0,0,0,0,0);
                recordPlacementRejectionCounters(villageId, counters);
            } catch (Exception e) {
                logger.fine(String.format("[STRUCT][DIAG] failed to write initial counters on addPlacementReceipt for %s: %s", villageId, e.getMessage()));
            }
        } else {
            // Ensure on-disk artifact exists even if it was deleted at runtime
            try {
                PlacementRejectionCounters existing = placementRejectionCounters.get(villageId);
                if (existing != null) {
                    recordPlacementRejectionCounters(villageId, existing);
                }
            } catch (Exception e) {
                logger.fine(String.format("[STRUCT][DIAG] failed to refresh counters artifact for %s: %s", villageId, e.getMessage()));
            }
        }
    }
    
    /**
     * R001: Get all placement receipts for a village.
     */
    public List<com.davisodom.villageoverhaul.model.PlacementReceipt> getPlacementReceipts(UUID villageId) {
        return new ArrayList<>(placementReceipts.getOrDefault(villageId, Collections.emptyList()));
    }
    
    /**
     * R002: Add a volume mask for a structure in a village.
     */
    public void addVolumeMask(UUID villageId, com.davisodom.villageoverhaul.model.VolumeMask mask) {
        volumeMasks.computeIfAbsent(villageId, k -> new ArrayList<>()).add(mask);
        // Use INFO level since Paper's console doesn't reliably show FINE when launched via double-click
        logger.info(String.format("[STRUCT][VOLUME] Stored volume mask for structure %s in village %s: %s", 
            mask.getStructureId(), villageId, mask.getSummary()));
    }

    /**
     * T026d11: Record a placement failure summary for a village run that produced zero placements.
     */
    public void recordPlacementFailureSummary(UUID villageId, PlacementFailureSummary summary) {
        if (villageId == null || summary == null) return;
        lastPlacementFailureSummary.put(villageId, summary);
        logger.info(String.format("[STRUCT][DIAG] Recorded zero-placement summary for village %s: %s", villageId, summary));
    }

    /**
     * T026d12: Record per-attempt rejection counters for a village run.
     * This method stores counters in-memory and writes a separate artifact file
     * for offline analysis (village_<id>_placement_rejections.json).
     */
    public void recordPlacementRejectionCounters(UUID villageId, PlacementRejectionCounters counters) {
        if (villageId == null || counters == null) return;
        placementRejectionCounters.put(villageId, counters);

        // Persist an artifact file for external harvesters/harness
        try {
            String filename = String.format("village_%s_placement_rejections.json", villageId);
            jsonStore.saveJson(filename, counters, JsonStore.SCHEMA_VERSION);
            logger.info(String.format("[STRUCT][DIAG] Saved placement rejection counters artifact for village %s: %s", villageId, filename));
        } catch (Exception e) {
            logger.warning(String.format("[STRUCT][DIAG] Failed to persist placement counters for %s: %s", villageId, e.getMessage()));
        }
    }

    /**
     * Get the most recent persisted placement rejection counters for a village, if any.
     */
    public Optional<PlacementRejectionCounters> getPlacementRejectionCounters(UUID villageId) {
        return Optional.ofNullable(placementRejectionCounters.get(villageId));
    }

    /**
     * Get the most recent placement failure summary for a village, if any.
     */
    public Optional<PlacementFailureSummary> getLastPlacementFailureSummary(UUID villageId) {
        return Optional.ofNullable(lastPlacementFailureSummary.get(villageId));
    }

    /**
     * T087: Persist collision diagnostics for placement candidate analysis.
     */
    public void recordCollisionDiagnostics(UUID villageId, CollisionDiagnostics diagnostics) {
        if (villageId == null || diagnostics == null) return;

        try {
            String safeStructureId = sanitizeFileFragment(diagnostics.structureId);
            String filename = String.format("collision_diag_%s_%s_%d.json",
                villageId, safeStructureId, diagnostics.recordedTimestamp);
            diagnosticsStore.saveJson(filename, diagnostics, JsonStore.SCHEMA_VERSION);
            logger.info(String.format("[STRUCT][DIAG] Saved collision diagnostics for village %s: %s", villageId, filename));
        } catch (Exception e) {
            logger.warning(String.format("[STRUCT][DIAG] Failed to persist collision diagnostics for %s: %s", villageId, e.getMessage()));
        }
    }

    private String sanitizeFileFragment(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
    
    /**
     * R002: Get all volume masks for a village.
     */
    public List<com.davisodom.villageoverhaul.model.VolumeMask> getVolumeMasks(UUID villageId) {
        return new ArrayList<>(volumeMasks.getOrDefault(villageId, Collections.emptyList()));
    }
    
    /**
     * Get village metadata.
     */
    public Optional<VillageMetadata> getVillage(UUID villageId) {
        return Optional.ofNullable(villages.get(villageId));
    }
    
    /**
     * Get all registered villages.
     */
    public Collection<VillageMetadata> getAllVillages() {
        return new ArrayList<>(villages.values());
    }
    
    /**
     * Remove a village and all its data.
     */
    public boolean removeVillage(UUID villageId) {
        if (villages.remove(villageId) != null) {
            villageBuildings.remove(villageId);
            mainBuildings.remove(villageId);
            pathNetworks.remove(villageId);
            villageVillagers.remove(villageId);
            logger.info(String.format("[STRUCT] Removed village %s", villageId));
            return true;
        }
        return false;
    }
    
    /**
     * Save all village data to disk (JSON format).
     * Persists mainBuildingId and pathNetwork for each village.
     */
    public void saveAll() throws IOException {
        int savedCount = 0;
        
        for (VillageMetadata metadata : villages.values()) {
            UUID villageId = metadata.getVillageId();
            
            // Create persistence DTO
            VillageDataDTO dto = new VillageDataDTO();
            dto.villageId = villageId.toString();
            dto.cultureId = metadata.getCultureId();
            dto.villageName = metadata.getVillageName();
            dto.worldName = metadata.getOrigin().getWorld().getName();
            dto.worldUuid = metadata.getOrigin().getWorld().getUID().toString();
            dto.originX = metadata.getOrigin().getBlockX();
            dto.originY = metadata.getOrigin().getBlockY();
            dto.originZ = metadata.getOrigin().getBlockZ();
            dto.seed = metadata.getSeed();
            dto.createdTimestamp = metadata.getCreatedTimestamp();
            
            // Persist mainBuildingId
            dto.mainBuildingId = mainBuildings.containsKey(villageId) 
                ? mainBuildings.get(villageId).toString() 
                : null;
            
            // Persist pathNetwork
            if (pathNetworks.containsKey(villageId)) {
                PathNetwork network = pathNetworks.get(villageId);
                dto.pathNetwork = convertPathNetworkToDTO(network);
            }
            
            // Persist border
            VillageBorder border = metadata.getBorder();
            dto.border = new BorderDTO(
                border.getMinX(), border.getMaxX(),
                border.getMinZ(), border.getMaxZ()
            );
            dto.lastBorderUpdateTick = metadata.getLastBorderUpdateTick();
            
            // R001: Persist placement receipts
            if (placementReceipts.containsKey(villageId)) {
                List<com.davisodom.villageoverhaul.model.PlacementReceipt> receipts = placementReceipts.get(villageId);
                dto.placementReceipts = new ArrayList<>();
                for (com.davisodom.villageoverhaul.model.PlacementReceipt receipt : receipts) {
                    dto.placementReceipts.add(convertReceiptToDTO(receipt));
                }
            }
            
            // R002: Persist volume masks
            if (volumeMasks.containsKey(villageId)) {
                List<com.davisodom.villageoverhaul.model.VolumeMask> masks = volumeMasks.get(villageId);
                dto.volumeMasks = new ArrayList<>();
                for (com.davisodom.villageoverhaul.model.VolumeMask mask : masks) {
                    dto.volumeMasks.add(convertVolumeMaskToDTO(mask));
                }
            }

            // T026d12: Persist placement rejection counters into village DTO
            if (placementRejectionCounters.containsKey(villageId)) {
                PlacementRejectionCounters counters = placementRejectionCounters.get(villageId);
                dto.placementRejectionCounters = counters;
            }

            // T077: Persist candidate coverage summary into village DTO
            if (candidateCoverageSummaries.containsKey(villageId)) {
                dto.candidateCoverage = candidateCoverageSummaries.get(villageId);
            }

            if (villageVillagers.containsKey(villageId)) {
                dto.villagerRecords = new ArrayList<>(villageVillagers.get(villageId));
            }
            
            // Save to individual village file
            String filename = "village_" + villageId + ".json";
            jsonStore.saveJson(filename, dto, JsonStore.SCHEMA_VERSION);
            savedCount++;
        }
        
        logger.info(String.format("[STRUCT] Saved %d villages to disk", savedCount));
    }
    
    /**
     * Load all village data from disk.
     */
    public void loadAll() throws IOException {
        // Filter to only load village data files, excluding:
        // - placement_rejections.json (diagnostic artifacts)
        // - backup files
        File[] files = storageDir.listFiles((dir, name) -> 
            name.startsWith("village_") && 
            name.endsWith(".json") &&
            !name.contains("_placement_rejections") &&
            !name.contains(".backup"));
        
        if (files == null || files.length == 0) {
            logger.info("[STRUCT] No village data files found");
            return;
        }
        
        int loadedCount = 0;
        
        for (File file : files) {
            try {
                VillageDataDTO dto = jsonStore.loadJson(file.getName(), VillageDataDTO.class);
                if (dto == null) continue;
                
                UUID villageId = UUID.fromString(dto.villageId);
                World world = resolveWorldIdentity(dto.worldName, dto.worldUuid, "village " + villageId);
                
                if (world == null) {
                    continue;
                }
                
                Location origin = new Location(world, dto.originX, dto.originY, dto.originZ);
                
                // Restore village metadata
                VillageMetadata metadata = new VillageMetadata(
                    villageId, dto.cultureId, origin, dto.seed, dto.createdTimestamp, dto.villageName);
                
                // Restore border
                if (dto.border != null) {
                    metadata.getBorder().expand(
                        dto.border.minX, dto.border.maxX,
                        dto.border.minZ, dto.border.maxZ
                    );
                    metadata.lastBorderUpdateTick = dto.lastBorderUpdateTick;
                }
                
                villages.put(villageId, metadata);
                villageBuildings.put(villageId, new ArrayList<>());
                
                // Restore mainBuildingId
                if (dto.mainBuildingId != null) {
                    mainBuildings.put(villageId, UUID.fromString(dto.mainBuildingId));
                    logger.fine(String.format("[STRUCT] Restored main building %s for village %s",
                        dto.mainBuildingId, villageId));
                }
                
                // Restore pathNetwork
                if (dto.pathNetwork != null) {
                    PathNetwork network = convertPathNetworkFromDTO(dto.pathNetwork, villageId, world);
                    pathNetworks.put(villageId, network);
                    logger.fine(String.format("[STRUCT] Restored path network for village %s (%d segments)",
                        villageId, network.getSegments().size()));
                }
                
                // R001: Restore placement receipts
                if (dto.placementReceipts != null && !dto.placementReceipts.isEmpty()) {
                    List<com.davisodom.villageoverhaul.model.PlacementReceipt> receipts = new ArrayList<>();
                    for (PlacementReceiptDTO receiptDTO : dto.placementReceipts) {
                        receipts.add(convertReceiptFromDTO(receiptDTO));
                    }
                    placementReceipts.put(villageId, receipts);
                    logger.fine(String.format("[STRUCT][RECEIPT] Restored %d placement receipts for village %s",
                        receipts.size(), villageId));
                }
                
                // R002: Restore volume masks
                if (dto.volumeMasks != null && !dto.volumeMasks.isEmpty()) {
                    List<com.davisodom.villageoverhaul.model.VolumeMask> masks = new ArrayList<>();
                    for (VolumeMaskDTO maskDTO : dto.volumeMasks) {
                        masks.add(convertVolumeMaskFromDTO(maskDTO));
                    }
                    volumeMasks.put(villageId, masks);
                    logger.fine(String.format("[STRUCT][VOLUME] Restored %d volume masks for village %s",
                        masks.size(), villageId));
                }

                // Load persisted placement rejection counters (if present)
                if (dto.placementRejectionCounters != null) {
                    placementRejectionCounters.put(villageId, dto.placementRejectionCounters);
                    logger.fine(String.format("[STRUCT][DIAG] Restored placement rejection counters for village %s", villageId));
                }

                if (dto.candidateCoverage != null) {
                    candidateCoverageSummaries.put(villageId, dto.candidateCoverage);
                    logger.fine(String.format("[STRUCT][BOUNDS] Restored candidate coverage for village %s", villageId));
                }

                if (dto.villagerRecords != null) {
                    villageVillagers.put(villageId, new ArrayList<>(dto.villagerRecords));
                }
                
                loadedCount++;
                
            } catch (Exception e) {
                logger.warning(String.format("[STRUCT] Failed to load %s: %s", 
                    file.getName(), e.getMessage()));
            }
        }
        
        logger.info(String.format("[STRUCT] Loaded %d villages from disk", loadedCount));
    }
    
    /**
     * Clear all in-memory data (for testing).
     */
    public void clearAll() {
        villages.clear();
        villageBuildings.clear();
        mainBuildings.clear();
        pathNetworks.clear();
        villageVillagers.clear();
        placementReceipts.clear(); // R001
        volumeMasks.clear(); // R002
        lastPlacementFailureSummary.clear(); // T026d11
        placementRejectionCounters.clear(); // T026d12
        candidateCoverageSummaries.clear(); // T077
        logger.info("[STRUCT] Cleared all village metadata");
    }
    
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
    
    /**
     * Inner class representing village metadata.
     */
    public static class VillageMetadata {
        private final UUID villageId;
        private final String cultureId;
        private final Location origin;
        private final long seed;
        private final long createdTimestamp;
        private String villageName;
        private final VillageBorder border;
        private long lastBorderUpdateTick;
        
        public VillageMetadata(UUID villageId, String cultureId, Location origin, long seed, long createdTimestamp, String villageName) {
            this.villageId = villageId;
            this.cultureId = cultureId;
            this.origin = origin;
            this.seed = seed;
            this.createdTimestamp = createdTimestamp;
            this.villageName = villageName;
            // Initialize border at origin with minimal size (will expand with buildings)
            this.border = new VillageBorder(origin.getBlockX(), origin.getBlockX(), 
                                           origin.getBlockZ(), origin.getBlockZ());
            this.lastBorderUpdateTick = 0;
        }
        
        public UUID getVillageId() { return villageId; }
        public String getCultureId() { return cultureId; }
        public Location getOrigin() { return origin; }
        public long getSeed() { return seed; }
        public long getCreatedTimestamp() { return createdTimestamp; }
        public String getVillageName() { return villageName; }
        public VillageBorder getBorder() { return border; }
        public long getLastBorderUpdateTick() { return lastBorderUpdateTick; }

        public void setVillageName(String villageName) {
            this.villageName = villageName;
        }
        
        /**
         * Expand border to include a building's footprint.
         * Called deterministically when buildings are placed.
         */
        public void expandBorderForBuilding(Building building) {
            Location buildingOrigin = building.getOrigin();
            int[] dims = building.getDimensions();
            
            // Calculate building footprint bounds
            int minX = buildingOrigin.getBlockX();
            int maxX = minX + dims[0] - 1;
            int minZ = buildingOrigin.getBlockZ();
            int maxZ = minZ + dims[2] - 1;
            
            // Expand border if needed
            border.expand(minX, maxX, minZ, maxZ);
            lastBorderUpdateTick = System.currentTimeMillis(); // Will use tick counter when available
        }
    }
    
    /**
     * Represents axis-aligned border bounds for a village.
     * Mutable to allow deterministic expansion as buildings are added.
     */
    public static class VillageBorder {
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        
        public VillageBorder(int minX, int maxX, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
        
        public int getMinX() { return minX; }
        public int getMaxX() { return maxX; }
        public int getMinZ() { return minZ; }
        public int getMaxZ() { return maxZ; }
        
        public int getWidth() { return maxX - minX + 1; }
        public int getDepth() { return maxZ - minZ + 1; }
        
        /**
         * Expand border to include new bounds.
         * Deterministic and idempotent.
         */
        public void expand(int newMinX, int newMaxX, int newMinZ, int newMaxZ) {
            this.minX = Math.min(this.minX, newMinX);
            this.maxX = Math.max(this.maxX, newMaxX);
            this.minZ = Math.min(this.minZ, newMinZ);
            this.maxZ = Math.max(this.maxZ, newMaxZ);
        }
        
        /**
         * Check if this border is within minDistance of another border.
         * Used for inter-village spacing enforcement.
         */
        public boolean isWithinDistance(VillageBorder other, int minDistance) {
            // Calculate closest distance between borders (not centers)
            int dx = 0;
            if (this.maxX < other.minX) {
                dx = other.minX - this.maxX;
            } else if (other.maxX < this.minX) {
                dx = this.minX - other.maxX;
            }
            
            int dz = 0;
            if (this.maxZ < other.minZ) {
                dz = other.minZ - this.maxZ;
            } else if (other.maxZ < this.minZ) {
                dz = this.minZ - other.maxZ;
            }
            
            // Use Manhattan distance for simplicity and performance
            return (dx + dz) < minDistance;
        }
        
        /**
         * Get border-to-border distance to another village border.
         */
        public int getDistanceTo(VillageBorder other) {
            int dx = 0;
            if (this.maxX < other.minX) {
                dx = other.minX - this.maxX;
            } else if (other.maxX < this.minX) {
                dx = this.minX - other.maxX;
            }
            
            int dz = 0;
            if (this.maxZ < other.minZ) {
                dz = other.minZ - this.maxZ;
            } else if (other.maxZ < this.minZ) {
                dz = this.minZ - other.maxZ;
            }
            
            return dx + dz; // Manhattan distance
        }
        
        @Override
        public String toString() {
            return String.format("Border[x:%d-%d, z:%d-%d, size:%dx%d]", 
                minX, maxX, minZ, maxZ, getWidth(), getDepth());
        }
    }
    
    /**
     * Conversion methods for PathNetwork serialization.
     */
    private PathNetworkDTO convertPathNetworkToDTO(PathNetwork network) {
        PathNetworkDTO dto = new PathNetworkDTO();
        dto.villageId = network.getVillageId().toString();
        dto.generatedTimestamp = network.getGeneratedTimestamp();
        dto.totalBlocksPlaced = network.getTotalBlocksPlaced();
        dto.segments = new ArrayList<>();
        
        for (PathNetwork.PathSegment segment : network.getSegments()) {
            PathSegmentDTO segmentDTO = new PathSegmentDTO();
            segmentDTO.startX = segment.getStart().getBlockX();
            segmentDTO.startY = segment.getStart().getBlockY();
            segmentDTO.startZ = segment.getStart().getBlockZ();
            segmentDTO.endX = segment.getEnd().getBlockX();
            segmentDTO.endY = segment.getEnd().getBlockY();
            segmentDTO.endZ = segment.getEnd().getBlockZ();
            segmentDTO.blockCount = segment.getBlocks().size();
            
            segmentDTO.blocks = new ArrayList<>();
            for (Block block : segment.getBlocks()) {
                BlockLocationDTO blockDTO = new BlockLocationDTO();
                blockDTO.x = block.getX();
                blockDTO.y = block.getY();
                blockDTO.z = block.getZ();
                segmentDTO.blocks.add(blockDTO);
            }
            
            dto.segments.add(segmentDTO);
        }
        
        return dto;
    }
    
    /**
     * R001: Convert PlacementReceipt to DTO for JSON persistence.
     */
    private PlacementReceiptDTO convertReceiptToDTO(com.davisodom.villageoverhaul.model.PlacementReceipt receipt) {
        PlacementReceiptDTO dto = new PlacementReceiptDTO();
        dto.structureId = receipt.getStructureId();
        dto.villageId = receipt.getVillageId().toString();
        dto.worldName = receipt.getWorldName();
        dto.worldUuid = receipt.getWorldUuid() != null ? receipt.getWorldUuid().toString() : null;
        dto.minX = receipt.getMinX();
        dto.maxX = receipt.getMaxX();
        dto.minY = receipt.getMinY();
        dto.maxY = receipt.getMaxY();
        dto.minZ = receipt.getMinZ();
        dto.maxZ = receipt.getMaxZ();
        dto.originX = receipt.getOriginX();
        dto.originY = receipt.getOriginY();
        dto.originZ = receipt.getOriginZ();
        dto.rotation = receipt.getRotation();
        dto.effectiveWidth = receipt.getEffectiveWidth();
        dto.effectiveDepth = receipt.getEffectiveDepth();
        dto.height = receipt.getHeight();
        dto.entranceX = receipt.getEntranceX();
        dto.entranceY = receipt.getEntranceY();
        dto.entranceZ = receipt.getEntranceZ();
        dto.timestamp = receipt.getTimestamp();
        
        dto.foundationCorners = new ArrayList<>();
        for (com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample corner : receipt.getFoundationCorners()) {
            CornerSampleDTO cornerDTO = new CornerSampleDTO();
            cornerDTO.x = corner.getX();
            cornerDTO.y = corner.getY();
            cornerDTO.z = corner.getZ();
            cornerDTO.blockType = corner.getBlockType().name();
            dto.foundationCorners.add(cornerDTO);
        }
        
        return dto;
    }
    
    /**
     * R001: Convert DTO back to PlacementReceipt.
     */
    private com.davisodom.villageoverhaul.model.PlacementReceipt convertReceiptFromDTO(PlacementReceiptDTO dto) {
        com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[] corners = 
            new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[4];
        
        for (int i = 0; i < 4 && i < dto.foundationCorners.size(); i++) {
            CornerSampleDTO cornerDTO = dto.foundationCorners.get(i);
            org.bukkit.Material material = org.bukkit.Material.valueOf(cornerDTO.blockType);
            corners[i] = new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(
                cornerDTO.x, cornerDTO.y, cornerDTO.z, material);
        }
        
        return new com.davisodom.villageoverhaul.model.PlacementReceipt.Builder()
            .structureId(dto.structureId)
            .villageId(UUID.fromString(dto.villageId))
            .worldName(dto.worldName)
            .worldUuid(parseWorldUuid(dto.worldUuid))
            .origin(dto.originX, dto.originY, dto.originZ)
            .rotation(dto.rotation)
            .bounds(dto.minX, dto.maxX, dto.minY, dto.maxY, dto.minZ, dto.maxZ)
            .dimensions(dto.effectiveWidth, dto.height, dto.effectiveDepth)
            .entrance(dto.entranceX, dto.entranceY, dto.entranceZ)
            .foundationCorners(corners)
            .timestamp(dto.timestamp)
            .build();
    }
    
    /**
     * R002: Convert VolumeMask to DTO for JSON persistence.
     */
    private VolumeMaskDTO convertVolumeMaskToDTO(com.davisodom.villageoverhaul.model.VolumeMask mask) {
        VolumeMaskDTO dto = new VolumeMaskDTO();
        dto.structureId = mask.getStructureId(); // Already a string
        dto.villageId = mask.getVillageId().toString();
        dto.minX = mask.getMinX();
        dto.maxX = mask.getMaxX();
        dto.minY = mask.getMinY();
        dto.maxY = mask.getMaxY();
        dto.minZ = mask.getMinZ();
        dto.maxZ = mask.getMaxZ();
        dto.timestamp = mask.getTimestamp();
        
        // Note: Occupancy bitmap serialization deferred for initial implementation
        // Current implementation assumes full occupancy (bitmap = null)
        dto.occupancyBitmap = null;
        
        return dto;
    }
    
    /**
     * R002: Convert DTO back to VolumeMask.
     */
    private com.davisodom.villageoverhaul.model.VolumeMask convertVolumeMaskFromDTO(VolumeMaskDTO dto) {
        return new com.davisodom.villageoverhaul.model.VolumeMask.Builder()
            .structureId(dto.structureId) // Already a string
            .villageId(UUID.fromString(dto.villageId))
            .bounds(dto.minX, dto.maxX, dto.minY, dto.maxY, dto.minZ, dto.maxZ)
            .timestamp(dto.timestamp)
            // Note: Occupancy bitmap deserialization deferred for initial implementation
            // Current implementation assumes full occupancy (bitmap = null)
            .build();
    }
    
    private PathNetwork convertPathNetworkFromDTO(PathNetworkDTO dto, UUID villageId, World world) {
        PathNetwork.Builder builder = new PathNetwork.Builder()
            .villageId(villageId)
            .generatedTimestamp(dto.generatedTimestamp);
        
        for (PathSegmentDTO segmentDTO : dto.segments) {
            Location start = new Location(world, segmentDTO.startX, segmentDTO.startY, segmentDTO.startZ);
            Location end = new Location(world, segmentDTO.endX, segmentDTO.endY, segmentDTO.endZ);
            
            List<Block> blocks = new ArrayList<>();
            for (BlockLocationDTO blockDTO : segmentDTO.blocks) {
                blocks.add(world.getBlockAt(blockDTO.x, blockDTO.y, blockDTO.z));
            }
            
            PathNetwork.PathSegment segment = new PathNetwork.PathSegment(start, end, blocks);
            builder.addSegment(segment);
        }
        
        return builder.build();
    }

    private World resolveWorldIdentity(String worldName, String worldUuid, String contextLabel) {
        UUID parsedUuid = parseWorldUuid(worldUuid);
        if (parsedUuid == null) {
            logger.warning(String.format("[STRUCT] Skipping %s: missing or invalid world UUID for world '%s'", contextLabel, worldName));
            return null;
        }

        World world = Bukkit.getWorld(parsedUuid);
        if (world == null) {
            logger.warning(String.format("[STRUCT] Skipping %s: world UUID %s is not loaded (stored name='%s')",
                contextLabel, parsedUuid, worldName));
            return null;
        }

        if (worldName != null && !worldName.isBlank() && !worldName.equals(world.getName())) {
            logger.warning(String.format("[STRUCT] World identity mismatch for %s: stored name='%s' actual name='%s' uuid=%s",
                contextLabel, worldName, world.getName(), parsedUuid));
        }

        return world;
    }

    private UUID parseWorldUuid(String worldUuid) {
        if (worldUuid == null || worldUuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(worldUuid);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
    
    /**
     * Data Transfer Objects for JSON persistence.
     */
    public static class VillageDataDTO {
        public String villageId;
        public String cultureId;
        public String villageName;
        public String worldName;
        public String worldUuid;
        public int originX;
        public int originY;
        public int originZ;
        public long seed;
        public long createdTimestamp;
        public String mainBuildingId; // nullable
        public PathNetworkDTO pathNetwork; // nullable
        public BorderDTO border;
        public long lastBorderUpdateTick;
        public List<PlacementReceiptDTO> placementReceipts; // R001: Nullable, added for ground-truth persistence
        public List<VolumeMaskDTO> volumeMasks; // R002: Nullable, added for verified 3D volume persistence
        public PlacementRejectionCounters placementRejectionCounters; // T026d12: per-run rejection counters
        public CandidateCoverageSummary candidateCoverage; // T077: candidate coverage summary
        public List<VillagerRecord> villagerRecords; // T074: persisted villagers
        
        public VillageDataDTO() {} // For Jackson
    }
    
    public static class PathNetworkDTO {
        public String villageId;
        public long generatedTimestamp;
        public int totalBlocksPlaced;
        public List<PathSegmentDTO> segments;
        
        public PathNetworkDTO() {} // For Jackson
    }
    
    public static class PathSegmentDTO {
        public int startX;
        public int startY;
        public int startZ;
        public int endX;
        public int endY;
        public int endZ;
        public int blockCount;
        public List<BlockLocationDTO> blocks;
        
        public PathSegmentDTO() {} // For Jackson
    }
    
    public static class BlockLocationDTO {
        public int x;
        public int y;
        public int z;
        
        public BlockLocationDTO() {} // For Jackson
    }
    
    public static class BorderDTO {
        public int minX;
        public int maxX;
        public int minZ;
        public int maxZ;
        
        public BorderDTO() {} // For Jackson
        
        @JsonCreator
        public BorderDTO(
            @JsonProperty("minX") int minX, 
            @JsonProperty("maxX") int maxX,
            @JsonProperty("minZ") int minZ, 
            @JsonProperty("maxZ") int maxZ
        ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }

    /**
     * T074: Persisted villager record.
     */
    public static class VillagerRecord {
        public String entityId;
        public UUID villageId;
        public String definitionId;
        public String cultureId;
        public String professionId;
        public String worldName;
        public String worldUuid;
        public int x;
        public int y;
        public int z;
        public long createdTimestamp;

        public VillagerRecord() {} // For Jackson

        public VillagerRecord(String entityId, UUID villageId, String definitionId, String cultureId,
                              String professionId, String worldName, String worldUuid, int x, int y, int z, long createdTimestamp) {
            this.entityId = entityId;
            this.villageId = villageId;
            this.definitionId = definitionId;
            this.cultureId = cultureId;
            this.professionId = professionId;
            this.worldName = worldName;
            this.worldUuid = worldUuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.createdTimestamp = createdTimestamp;
        }
    }
    
    /**
     * R001: DTO for PlacementReceipt persistence.
     */
    public static class PlacementReceiptDTO {
        public String structureId;
        public String villageId;
        public String worldName;
        public String worldUuid;
        public int minX;
        public int maxX;
        public int minY;
        public int maxY;
        public int minZ;
        public int maxZ;
        public int originX;
        public int originY;
        public int originZ;
        public int rotation;
        public int effectiveWidth;
        public int effectiveDepth;
        public int entranceX;
        public int entranceY;
        public int entranceZ;
        public int height;
        public List<CornerSampleDTO> foundationCorners;
        public long timestamp;
        
        public PlacementReceiptDTO() {} // For Jackson
    }
    
    public static class CornerSampleDTO {
        public int x;
        public int y;
        public int z;
        public String blockType;
        
        public CornerSampleDTO() {} // For Jackson
    }
    
    /**
     * R002: DTO for VolumeMask persistence.
     */
    public static class VolumeMaskDTO {
        public String structureId;
        public String villageId;
        public int minX;
        public int maxX;
        public int minY;
        public int maxY;
        public int minZ;
        public int maxZ;
        public String occupancyBitmap; // Base64-encoded BitSet, null for full occupancy
        public long timestamp;
        
        public VolumeMaskDTO() {} // For Jackson
    }

    /**
     * T026d11: Lightweight DTO capturing a single-run placement failure summary
     * This is kept in-memory for harvest by the harness; persistence handled in follow-up task.
     */
    public static class PlacementFailureSummary {
        public int attempts;
        public int fluid;
        public int steep;
        public int blocked;
        public int spacing;
        public int overlap;
        public int chunkNotReady;
        public int siteValidationRejects;
        public int terraformRejects;
        public long villageSeed;
        public long placementSeed;
        public int candidates;

        public PlacementFailureSummary() {}

        public PlacementFailureSummary(int attempts, int fluid, int steep, int blocked, int spacing, int overlap,
                                       int chunkNotReady, int siteValidationRejects, int terraformRejects,
                                       long villageSeed, long placementSeed, int candidates) {
            this.attempts = attempts;
            this.fluid = fluid;
            this.steep = steep;
            this.blocked = blocked;
            this.spacing = spacing;
            this.overlap = overlap;
            this.chunkNotReady = chunkNotReady;
            this.siteValidationRejects = siteValidationRejects;
            this.terraformRejects = terraformRejects;
            this.villageSeed = villageSeed;
            this.placementSeed = placementSeed;
            this.candidates = candidates;
        }

        @Override
        public String toString() {
            return String.format("fluid:%d,steep:%d,blocked:%d,spacing:%d,overlap:%d,attempts:%d,chunkNotReady:%d,seedChain:%d:%d,candidates:%d",
                    fluid, steep, blocked, spacing, overlap, attempts, chunkNotReady, villageSeed, placementSeed, candidates);
        }
    }

    /**
     * T026d12: Persisted counters for per-attempt placement rejection reasons.
     * Serialized directly into the village JSON file for offline analysis.
     */
    public static class PlacementRejectionCounters {
        public int attempts;
        public int fluid;
        public int steep;
        public int blocked;
        public int spacing;
        public int overlap;
        public int chunkNotReady;
        public int siteValidationRejects;
        public int terraformRejects;
        public int candidates;
        public long recordedTimestamp;

        public PlacementRejectionCounters() {}

        public PlacementRejectionCounters(int attempts, int fluid, int steep, int blocked, int spacing, int overlap,
                                          int chunkNotReady, int siteValidationRejects, int terraformRejects, int candidates) {
            this.attempts = attempts;
            this.fluid = fluid;
            this.steep = steep;
            this.blocked = blocked;
            this.spacing = spacing;
            this.overlap = overlap;
            this.chunkNotReady = chunkNotReady;
            this.siteValidationRejects = siteValidationRejects;
            this.terraformRejects = terraformRejects;
            this.candidates = candidates;
            this.recordedTimestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("attempts=%d,fluid=%d,steep=%d,blocked=%d,spacing=%d,overlap=%d,chunkNotReady=%d,siteValidationRejects=%d,terraformRejects=%d,candidates=%d,timestamp=%d",
                attempts, fluid, steep, blocked, spacing, overlap, chunkNotReady, siteValidationRejects, terraformRejects,
                candidates, recordedTimestamp);
        }
    }

    /**
     * T077: Candidate sampling coverage summary for placement search within bounds.
     */
    public static class CandidateCoverageSummary {
        public String structureId;
        public int originX;
        public int originZ;
        public int radiusBlocks;
        public int boundsMinX;
        public int boundsMaxX;
        public int boundsMinZ;
        public int boundsMaxZ;
        public int gridSize;
        public int rotationCount;
        public int gridPointsTotal;
        public int gridPointsLoaded;
        public int candidatesChecked;
        public int validCandidates;
        public int chunksSkipped;
        public long seed;
        public long recordedTimestamp;

        public CandidateCoverageSummary() {}

        public CandidateCoverageSummary(String structureId, int originX, int originZ, int radiusBlocks,
                                        int boundsMinX, int boundsMaxX, int boundsMinZ, int boundsMaxZ,
                                        int gridSize, int rotationCount, int gridPointsTotal, int gridPointsLoaded,
                                        int candidatesChecked, int validCandidates, int chunksSkipped, long seed) {
            this.structureId = structureId;
            this.originX = originX;
            this.originZ = originZ;
            this.radiusBlocks = radiusBlocks;
            this.boundsMinX = boundsMinX;
            this.boundsMaxX = boundsMaxX;
            this.boundsMinZ = boundsMinZ;
            this.boundsMaxZ = boundsMaxZ;
            this.gridSize = gridSize;
            this.rotationCount = rotationCount;
            this.gridPointsTotal = gridPointsTotal;
            this.gridPointsLoaded = gridPointsLoaded;
            this.candidatesChecked = candidatesChecked;
            this.validCandidates = validCandidates;
            this.chunksSkipped = chunksSkipped;
            this.seed = seed;
            this.recordedTimestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("structure=%s origin=(%d,%d) radius=%d bounds=[%d..%d,%d..%d] gridSize=%d rotations=%d gridPoints=%d loaded=%d candidates=%d valid=%d chunksSkipped=%d seed=%d timestamp=%d",
                    structureId, originX, originZ, radiusBlocks, boundsMinX, boundsMaxX, boundsMinZ, boundsMaxZ,
                    gridSize, rotationCount, gridPointsTotal, gridPointsLoaded, candidatesChecked, validCandidates,
                    chunksSkipped, seed, recordedTimestamp);
        }
    }

    /**
     * T087: Collision diagnostics per candidate and mask check.
     */
    public static class CollisionDiagnostics {
        public String villageId;
        public String structureId;
        public long seed;
        public int minBuildingSpacing;
        public int existingMaskCount;
        public int candidatesChecked;
        public boolean truncated;
        public long recordedTimestamp;
        public List<CollisionCheckEntry> checks = new ArrayList<>();

        public CollisionDiagnostics() {}

        public CollisionDiagnostics(String villageId, String structureId, long seed,
                                    int minBuildingSpacing, int existingMaskCount) {
            this.villageId = villageId;
            this.structureId = structureId;
            this.seed = seed;
            this.minBuildingSpacing = minBuildingSpacing;
            this.existingMaskCount = existingMaskCount;
            this.recordedTimestamp = System.currentTimeMillis();
        }
    }

    public static class CollisionCheckEntry {
        public int candidateIndex;
        public int candidateX;
        public int candidateY;
        public int candidateZ;
        public int rotationDegrees;
        public int buffer;
        public int distanceSquared;
        public int dx;
        public int dz;
        public String phase;
        public int[] candidateAabb;
        public boolean collision;
        public int masksChecked;
        public List<CollisionMaskEntry> overlaps = new ArrayList<>();

        public CollisionCheckEntry() {}
    }

    public static class CollisionMaskEntry {
        public String structureId;
        public int[] maskAabb;
        public int[] expandedAabb;
        public boolean xOverlap;
        public boolean zOverlap;
        public boolean collision;

        public CollisionMaskEntry() {}
    }
}
