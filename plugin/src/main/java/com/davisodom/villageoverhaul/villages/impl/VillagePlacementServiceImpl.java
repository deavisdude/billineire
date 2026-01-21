package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.cultures.CultureService;
import com.davisodom.villageoverhaul.model.Building;
import com.davisodom.villageoverhaul.model.PlacementReceipt;
import com.davisodom.villageoverhaul.model.VolumeMask;
import com.davisodom.villageoverhaul.npc.CustomVillagerService;
import com.davisodom.villageoverhaul.npc.VillagerAppearanceAdapter;
import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.VillagePlacementService;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.worldgen.PathService;
import com.davisodom.villageoverhaul.worldgen.StructureService;
import com.davisodom.villageoverhaul.worldgen.SurfaceSolver;
import com.davisodom.villageoverhaul.worldgen.TerrainClassifier;
import com.davisodom.villageoverhaul.worldgen.impl.PathEmitter;
import com.davisodom.villageoverhaul.worldgen.impl.PathServiceImpl;
import com.davisodom.villageoverhaul.worldgen.impl.StructureServiceImpl;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Implementation of village placement service with integrated structure seating.
 */
public class VillagePlacementServiceImpl implements VillagePlacementService {
    
    private static final Logger LOGGER = Logger.getLogger(VillagePlacementServiceImpl.class.getName());
    private static final int MAX_COLLISION_DIAGNOSTIC_ENTRIES = 2000;
    
    // Minimum spacing between buildings (blocks)
    // This is applied on BOTH sides, so total gap = 2 * spacing = 4 blocks
    // Default value if no configuration provided
    private static final int DEFAULT_BUILDING_SPACING = 2;
    
    // Default minimum spacing between villages (border-to-border, blocks)
    private static final int DEFAULT_VILLAGE_SPACING = 200;

    // Default max bounds radius for village placement search (blocks)
    private static final int DEFAULT_MAX_BOUNDS_RADIUS = 220;

    // Default villagers per structure ratio
    private static final double DEFAULT_VILLAGERS_PER_STRUCTURE = 2.0;
    private static final int MIN_INITIAL_VILLAGERS = 1;
    private static final int DEFAULT_MAX_CANDIDATES_PER_STRUCTURE = 240;
    
    // Configured spacing values (loaded from plugin config)
    private final int minBuildingSpacing;
    private final int minVillageSpacing;
    private int maxBoundsRadiusBlocks;
    private final double villagersPerStructure;
    
    // Structure service for building placement
    private final StructureService structureService;
    
    // Path service for connecting buildings
    private final PathService pathService;
    
    // Path emitter for block placement
    private final PathEmitter pathEmitter;
    
    // Metadata storage
    private final VillageMetadataStore metadataStore;
    
    // Culture service for structure selection
    private final CultureService cultureService;

    // Optional NPC services for initial villager spawns
    private final CustomVillagerService customVillagerService;
    private final VillagerAppearanceAdapter villagerAppearanceAdapter;
    
    // Main building selector
    private final MainBuildingSelector mainBuildingSelector;
    
    // In-memory cache of villages (villageId -> buildings)
    private final Map<UUID, List<Building>> villageBuildings = new HashMap<>();

    public enum PlacementStatus {
        SUCCESS,
        FAILED,
        FULL
    }

    public static class PlacementOutcome {
        private final PlacementStatus status;
        private final UUID villageId;
        private final int existingBuildings;
        private final int placedBuildings;
        private final int totalStructures;

        public PlacementOutcome(PlacementStatus status, UUID villageId, int existingBuildings,
                                int placedBuildings, int totalStructures) {
            this.status = status;
            this.villageId = villageId;
            this.existingBuildings = existingBuildings;
            this.placedBuildings = placedBuildings;
            this.totalStructures = totalStructures;
        }

        public PlacementStatus getStatus() { return status; }
        public UUID getVillageId() { return villageId; }
        public int getExistingBuildings() { return existingBuildings; }
        public int getPlacedBuildings() { return placedBuildings; }
        public int getTotalStructures() { return totalStructures; }
    }

    /**
     * Compute initial villager spawn count based on structures placed.
     */
    public static int computeInitialVillagerCount(int structureCount, double villagersPerStructure) {
        int computed = (int) Math.round(structureCount * villagersPerStructure);
        return Math.max(MIN_INITIAL_VILLAGERS, computed);
    }
    
    /**
     * Constructor for testing without plugin reference (uses procedural structures).
     */
    public VillagePlacementServiceImpl(VillageMetadataStore metadataStore, CultureService cultureService) {
        this.structureService = new StructureServiceImpl();
        this.pathService = new PathServiceImpl(metadataStore);
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.cultureService = cultureService;
        this.mainBuildingSelector = new MainBuildingSelector(LOGGER, cultureService);
        this.minBuildingSpacing = DEFAULT_BUILDING_SPACING;
        this.minVillageSpacing = DEFAULT_VILLAGE_SPACING;
        this.maxBoundsRadiusBlocks = DEFAULT_MAX_BOUNDS_RADIUS;
        this.villagersPerStructure = DEFAULT_VILLAGERS_PER_STRUCTURE;
        this.customVillagerService = null;
        this.villagerAppearanceAdapter = null;
    }
    
    /**
     * Constructor with plugin reference for loading schematics from disk.
     * 
     * @param plugin Plugin instance (provides data folder)
     * @param metadataStore Metadata storage
     * @param cultureService Culture service for main building selection
     */
    public VillagePlacementServiceImpl(Plugin plugin, VillageMetadataStore metadataStore, CultureService cultureService) {
        this.structureService = new StructureServiceImpl(plugin.getDataFolder());
        this.pathService = new PathServiceImpl(metadataStore);
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.cultureService = cultureService;
        this.mainBuildingSelector = new MainBuildingSelector(LOGGER, cultureService);
        VillageOverhaulPlugin voPlugin = plugin instanceof VillageOverhaulPlugin ? (VillageOverhaulPlugin) plugin : null;
        this.minBuildingSpacing = voPlugin != null
            ? voPlugin.getMinBuildingSpacing()
            : plugin.getConfig().getInt("village.minBuildingSpacing", DEFAULT_BUILDING_SPACING);
        this.minVillageSpacing = voPlugin != null
            ? voPlugin.getMinVillageSpacing()
            : plugin.getConfig().getInt("village.minVillageSpacing", DEFAULT_VILLAGE_SPACING);
        this.maxBoundsRadiusBlocks = voPlugin != null
            ? voPlugin.getMaxBoundsRadiusBlocks()
            : plugin.getConfig().getInt("village.maxBoundsRadiusBlocks", DEFAULT_MAX_BOUNDS_RADIUS);
        this.villagersPerStructure = plugin.getConfig().getDouble("worldgen.spawn.villagersPerStructure", DEFAULT_VILLAGERS_PER_STRUCTURE);
        this.customVillagerService = voPlugin != null ? voPlugin.getCustomVillagerService() : null;
        this.villagerAppearanceAdapter = voPlugin != null ? voPlugin.getVillagerAppearanceAdapter() : null;
    }
    
    /**
     * Constructor with custom structure service (for dependency injection).
     */
    public VillagePlacementServiceImpl(StructureService structureService, VillageMetadataStore metadataStore, CultureService cultureService) {
        this.structureService = structureService;
        this.pathService = new PathServiceImpl(metadataStore);
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.cultureService = cultureService;
        this.mainBuildingSelector = new MainBuildingSelector(LOGGER, cultureService);
        this.minBuildingSpacing = DEFAULT_BUILDING_SPACING;
        this.minVillageSpacing = DEFAULT_VILLAGE_SPACING;
        this.maxBoundsRadiusBlocks = DEFAULT_MAX_BOUNDS_RADIUS;
        this.villagersPerStructure = DEFAULT_VILLAGERS_PER_STRUCTURE;
        this.customVillagerService = null;
        this.villagerAppearanceAdapter = null;
    }

    /**
     * Constructor with custom structure and villager services (for testing).
     */
    public VillagePlacementServiceImpl(StructureService structureService, VillageMetadataStore metadataStore,
                                       CultureService cultureService, CustomVillagerService customVillagerService,
                                       VillagerAppearanceAdapter villagerAppearanceAdapter,
                                       double villagersPerStructure) {
        this.structureService = structureService;
        this.pathService = new PathServiceImpl(metadataStore);
        this.pathEmitter = new PathEmitter();
        this.metadataStore = metadataStore;
        this.cultureService = cultureService;
        this.mainBuildingSelector = new MainBuildingSelector(LOGGER, cultureService);
        this.minBuildingSpacing = DEFAULT_BUILDING_SPACING;
        this.minVillageSpacing = DEFAULT_VILLAGE_SPACING;
        this.maxBoundsRadiusBlocks = DEFAULT_MAX_BOUNDS_RADIUS;
        this.villagersPerStructure = villagersPerStructure;
        this.customVillagerService = customVillagerService;
        this.villagerAppearanceAdapter = villagerAppearanceAdapter;
    }
    
    @Override
    public Optional<UUID> placeVillage(World world, Location origin, String cultureId, long seed) {
        // T026d17: Derive deterministic village UUID from seed instead of random UUID
        // This ensures the same seed always produces the same village ID for reproducibility
        UUID deterministicVillageId = UUID.nameUUIDFromBytes(
            (seed + ":" + origin.getBlockX() + ":" + origin.getBlockZ()).getBytes(StandardCharsets.UTF_8));
        return placeVillage(world, origin, cultureId, seed, deterministicVillageId);
    }
    
    /**
     * Place a village with an explicit UUID (for deterministic/test scenarios).
     * T026d14: Allows callers to specify a deterministic UUID derived from seed.
     */
    public Optional<UUID> placeVillage(World world, Location origin, String cultureId, long seed, UUID villageId) {
        boolean isFirst = isFirstVillage(world);
        
        if (isFirst) {
            // First village spawn proximity verified by terrain search
        } else {
            // Subsequent village spacing verified below
        }
        
        InterVillageSpacingResult spacingResult = checkInterVillageSpacingDetailed(origin, minVillageSpacing);
        if (!spacingResult.acceptable) {
            // T071: Improved logging when spacing validation fails
            LOGGER.warning(String.format("[STRUCT][T071] Village placement rejected: location (%d, %d, %d) violates minVillageSpacing=%d. " +
                    "Nearest existing village: %s at distance %d blocks (required: %d blocks)",
                    origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(),
                    minVillageSpacing,
                    spacingResult.violatingVillageId != null ? spacingResult.violatingVillageId : "unknown",
                    spacingResult.actualDistance,
                    minVillageSpacing));
            return Optional.empty();
        }
        
        // T026d1: Deterministic RNG seeding audit
        // Derive placement seed from village seed to ensure reproducible structure ordering
        Random villageRandom = new Random(seed);
        long placementSeed = villageRandom.nextLong();
        // Derive a deterministic base seed for path generation from placement seed
        long pathBaseSeed = new Random(placementSeed).nextLong();
        LOGGER.info(String.format("[STRUCT] seed-chain: %d -> %d", seed, placementSeed));
        
        metadataStore.registerVillage(villageId, cultureId, origin, seed);
        // Diagnostic counters for this placement run (used to emit zero-placement summary)
        PlacementRejectionTracker rejectionTracker = new PlacementRejectionTracker();
        
        List<String> structureIds = getCultureStructures(cultureId, placementSeed);
        List<Building> placedBuildings = new ArrayList<>();
        
        SurfaceSolver surfaceSolver = new SurfaceSolver(world, new ArrayList<>());
        
        // Support a test-only short-circuit for CI: force a zero-placement run
        // when the JVM system property vo.test.forceZeroPlacement=true is present.
        try {
            if (Boolean.parseBoolean(System.getProperty("vo.test.forceZeroPlacement", "false"))) {
                LOGGER.info("[STRUCT][TEST] Forced zero-placement enabled via vo.test.forceZeroPlacement; aborting placements for deterministic test.");

                // Persist an empty failure summary so harness can pick up structured artifacts
                VillageMetadataStore.PlacementFailureSummary summary = new VillageMetadataStore.PlacementFailureSummary(
                    rejectionTracker.totalAttempts,
                    rejectionTracker.fluidRejections,
                    rejectionTracker.steepRejections,
                    rejectionTracker.blockedRejections,
                    rejectionTracker.spacingRejections,
                    rejectionTracker.overlapRejections,
                    rejectionTracker.chunkNotReady,
                    rejectionTracker.siteValidationRejects,
                    rejectionTracker.terraformRejects,
                    seed,
                    placementSeed,
                    rejectionTracker.totalAttempts
                );

                try {
                    metadataStore.recordPlacementFailureSummary(villageId, summary);
                        VillageMetadataStore.PlacementRejectionCounters counters = new VillageMetadataStore.PlacementRejectionCounters(
                            rejectionTracker.totalAttempts,
                            rejectionTracker.fluidRejections,
                            rejectionTracker.steepRejections,
                            rejectionTracker.blockedRejections,
                            rejectionTracker.spacingRejections,
                            rejectionTracker.overlapRejections,
                            rejectionTracker.chunkNotReady,
                            rejectionTracker.siteValidationRejects,
                            rejectionTracker.terraformRejects,
                            rejectionTracker.totalAttempts
                        );
                    metadataStore.recordPlacementRejectionCounters(villageId, counters);
                } catch (Exception e) {
                    LOGGER.warning(String.format("[STRUCT][DIAG] Failed to record forced zero-placement summary: %s", e.getMessage()));
                }

                String diag = String.format("ZERO-PLACEMENT village=%s rootCause=fluid:%d,steep:%d,blocked:%d,spacing:%d,overlap:%d,chunkNotReady:%d attempts=%d placed=0 seedChain=%d:%d candidates=%d",
                        villageId,
                        rejectionTracker.fluidRejections,
                        rejectionTracker.steepRejections,
                        rejectionTracker.blockedRejections,
                        rejectionTracker.spacingRejections,
                        rejectionTracker.overlapRejections,
                    rejectionTracker.chunkNotReady,
                        rejectionTracker.totalAttempts,
                        seed, placementSeed,
                        rejectionTracker.totalAttempts);

                LOGGER.info(diag);
                return Optional.empty();
            }
        } catch (SecurityException se) {
            // In locked-down environments reading system properties may be disallowed; ignore and continue normally
            LOGGER.fine("Unable to read system properties for vo.test.forceZeroPlacement: " + se.getMessage());
        }

        // T070: Maximum number of candidate positions to try per structure before giving up
        // Increased to reduce false zero-placement on rough terrain while keeping attempts bounded.
        final int maxCandidatesPerStructure = DEFAULT_MAX_CANDIDATES_PER_STRUCTURE;
        
        // Place buildings one at a time with dynamic collision detection
        // Use grid-based spiral search for each building to find non-overlapping spots
        // T070: Retry with alternate candidates when terrain validation fails at initial position
        for (int i = 0; i < structureIds.size(); i++) {
            String structureId = structureIds.get(i);
            
            Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
            if (!dimensions.isPresent()) {
                continue;
            }
            
            int[] dims = dimensions.get();
            int width = dims[0];
            int depth = dims[2];
            int height = dims[1];
            
            long buildingSeed = placementSeed + i;
            
            // R011b: Fetch fresh volume masks before each placement to ensure collision detection works
            List<VolumeMask> existingMasks = metadataStore.getVolumeMasks(villageId);
            
            // T070: Get ALL non-overlapping candidate positions (sorted by distance)
            List<CandidateSite> candidatePositions = findCandidatePositions(
                world, origin, width, depth, height, buildingSeed,
                existingMasks, surfaceSolver, rejectionTracker, villageId, structureId);
            
            if (candidatePositions.isEmpty()) {
                LOGGER.info(String.format("[STRUCT][T070] No collision-free candidates for %s, skipping structure", structureId));
                continue;
            }
            
            // T070: Try candidates one by one until terrain validation succeeds
            boolean placed = false;
            int candidatesTried = 0;
            int candidatesToTry = Math.min(candidatePositions.size(), maxCandidatesPerStructure);
            
            for (int candidateIdx = 0; candidateIdx < candidatesToTry && !placed; candidateIdx++) {
                CandidateSite candidate = candidatePositions.get(candidateIdx);
                candidatesTried++;
                
                Location buildingLocation = new Location(world, candidate.x, candidate.y, candidate.z);
                
                java.util.Map<String, Integer> attemptDiagnostics = new java.util.HashMap<>();
                // T057f: Pass minBuildingSpacing from config to StructureService for collision checks
                Optional<PlacementReceipt> receiptOpt = structureService.placeStructureAndGetReceipt(
                    structureId, world, buildingLocation, buildingSeed, villageId, existingMasks,
                    minBuildingSpacing, attemptDiagnostics, candidate.rotationDegrees);
            
                if (receiptOpt.isPresent()) {
                    PlacementReceipt receipt = receiptOpt.get();
                    metadataStore.addPlacementReceipt(villageId, receipt);
                    
                    VolumeMask placedMask = VolumeMask.fromReceipt(receipt);
                    metadataStore.addVolumeMask(villageId, placedMask);
                    surfaceSolver = new SurfaceSolver(world, metadataStore.getVolumeMasks(villageId));
                    
                    // Deterministic building ID derived from village id + structure id + building seed
                    UUID deterministicBuildingId = UUID.nameUUIDFromBytes((villageId.toString() + ":" + structureId + ":" + buildingSeed).getBytes(StandardCharsets.UTF_8));

                    Building building = new Building.Builder()
                        .buildingId(deterministicBuildingId)
                            .villageId(villageId)
                            .structureId(structureId)
                            .origin(new Location(world, receipt.getOriginX(), receipt.getOriginY(), receipt.getOriginZ()))
                            .dimensions(receipt.getEffectiveWidth(), receipt.getHeight(), receipt.getEffectiveDepth())
                            .build();
                    
                    placedBuildings.add(building);
                    placed = true;
                    
                    LOGGER.info(String.format("[STRUCT] receipt: id=%s bounds=[%d..%d,%d..%d,%d..%d] rot=%d° candidatesTried=%d", 
                            structureId,
                            receipt.getMinX(), receipt.getMaxX(),
                            receipt.getMinY(), receipt.getMaxY(),
                            receipt.getMinZ(), receipt.getMaxZ(),
                            receipt.getRotation(),
                            candidatesTried));
                } else {
                    // Aggregate diagnostics from failed attempt
                    if (attemptDiagnostics != null && !attemptDiagnostics.isEmpty()) {
                        rejectionTracker.totalAttempts += attemptDiagnostics.getOrDefault("placementAttempts", 0);
                        int siteValidationRejects = attemptDiagnostics.getOrDefault("siteValidationRejects",
                            attemptDiagnostics.getOrDefault("terrainInvalid", 0));
                        int terraformRejects = attemptDiagnostics.getOrDefault("terraformRejects", 0);
                        if (terraformRejects == 0) {
                            terraformRejects = attemptDiagnostics.getOrDefault("terraformCommitFailed", 0);
                        }
                        rejectionTracker.terrainRejections += siteValidationRejects;
                        rejectionTracker.siteValidationRejects += siteValidationRejects;
                        rejectionTracker.terraformRejects += terraformRejects;
                        rejectionTracker.chunkNotReady += attemptDiagnostics.getOrDefault("chunkNotReady", 0);
                        rejectionTracker.overlapRejections += attemptDiagnostics.getOrDefault("overlap", 0);
                        int fluidCount = attemptDiagnostics.getOrDefault("fluid", attemptDiagnostics.getOrDefault("water", 0));
                        rejectionTracker.fluidRejections += fluidCount;
                        rejectionTracker.steepRejections += attemptDiagnostics.getOrDefault("steep", 0);
                        rejectionTracker.blockedRejections += attemptDiagnostics.getOrDefault("blocked", 0);
                    }
                    // T070: Log candidate rejection and continue to next candidate
                        LOGGER.fine(String.format("[STRUCT][T070] Candidate %d/%d rejected for %s at (%d,%d,%d) rot=%d", 
                            candidateIdx + 1, candidatesToTry, structureId, candidate.x, candidate.y, candidate.z,
                            candidate.rotationDegrees));
                }
            }
            
            // T070: Log summary of candidate search for this structure
            if (!placed) {
                LOGGER.info(String.format("[STRUCT][T070] Failed to place %s after trying %d/%d candidates", 
                        structureId, candidatesTried, candidatePositions.size()));
            }
        }
        
        if (placedBuildings.isEmpty()) {
            // Emit structured zero-placement diagnostic for harness parsing (T026d11)
            // Format required by harness: ZERO-PLACEMENT village=<id> rootCause=fluid:<n>,steep:<n>,blocked:<n>,spacing:<n>,overlap:<n> attempts=<n> placed=0 seedChain=<vSeed>:<pSeed> candidates=<n>
                String diag = String.format("ZERO-PLACEMENT village=%s rootCause=fluid:%d,steep:%d,blocked:%d,spacing:%d,overlap:%d,chunkNotReady:%d attempts=%d placed=0 seedChain=%d:%d candidates=%d",
                    villageId,
                    rejectionTracker.fluidRejections,
                    rejectionTracker.steepRejections,
                    rejectionTracker.blockedRejections,
                    rejectionTracker.spacingRejections,
                    rejectionTracker.overlapRejections,
                    rejectionTracker.chunkNotReady,
                    rejectionTracker.totalAttempts,
                    seed, placementSeed,
                    rejectionTracker.totalAttempts);

            LOGGER.info(diag);

            // Persist lightweight summary so harness/CI can attach structured artifacts later (T026d11)
                VillageMetadataStore.PlacementFailureSummary summary = new VillageMetadataStore.PlacementFailureSummary(
                    rejectionTracker.totalAttempts,
                    rejectionTracker.fluidRejections,
                    rejectionTracker.steepRejections,
                    rejectionTracker.blockedRejections,
                    rejectionTracker.spacingRejections,
                    rejectionTracker.overlapRejections,
                    rejectionTracker.chunkNotReady,
                    rejectionTracker.siteValidationRejects,
                    rejectionTracker.terraformRejects,
                    seed,
                    placementSeed,
                    rejectionTracker.totalAttempts
                );

            try {
                metadataStore.recordPlacementFailureSummary(villageId, summary);
                // Persist per-attempt counters for offline analysis (T026d12)
                VillageMetadataStore.PlacementRejectionCounters counters = new VillageMetadataStore.PlacementRejectionCounters(
                    rejectionTracker.totalAttempts,
                    rejectionTracker.fluidRejections,
                    rejectionTracker.steepRejections,
                    rejectionTracker.blockedRejections,
                    rejectionTracker.spacingRejections,
                    rejectionTracker.overlapRejections,
                    rejectionTracker.chunkNotReady,
                    rejectionTracker.siteValidationRejects,
                    rejectionTracker.terraformRejects,
                    rejectionTracker.totalAttempts
                );

                metadataStore.recordPlacementRejectionCounters(villageId, counters);
            } catch (Exception e) {
                LOGGER.warning(String.format("[STRUCT][DIAG] Failed to record zero-placement summary: %s", e.getMessage()));
            }

            return Optional.empty();
        }
        
        for (Building building : placedBuildings) {
            metadataStore.addBuilding(villageId, building);
        }
        
        Optional<UUID> mainBuildingId = mainBuildingSelector.selectMainBuilding(cultureId, placedBuildings);
        if (mainBuildingId.isPresent()) {
            metadataStore.setMainBuilding(villageId, mainBuildingId.get());
        }

        int spawnedVillagers = spawnInitialVillagers(
            world,
            villageId,
            cultureId,
            origin,
            placedBuildings.size(),
            placementSeed,
            surfaceSolver
        );

        LOGGER.info(String.format("[VILLAGE] spawnedVillagers=%d village=%s structures=%d",
            spawnedVillagers, villageId, placedBuildings.size()));
        
        if (placedBuildings.size() > 1) {
            List<Location> buildingEntrances = new ArrayList<>();
            Location mainBuildingEntrance = null;
            
            List<PlacementReceipt> receipts = metadataStore.getPlacementReceipts(villageId);
            Map<String, PlacementReceipt> receiptMap = new HashMap<>();
            for (PlacementReceipt r : receipts) {
                receiptMap.put(r.getStructureId() + "@" + r.getOriginX() + "," + r.getOriginZ(), r);
            }
            
            for (Building building : placedBuildings) {
                String key = building.getStructureId() + "@" + building.getOrigin().getBlockX() + "," + building.getOrigin().getBlockZ();
                PlacementReceipt receipt = receiptMap.get(key);
                
                if (receipt != null) {
                    Location entrance = new Location(world, receipt.getEntranceX(), receipt.getEntranceY(), receipt.getEntranceZ());
                    buildingEntrances.add(entrance);
                    
                    if (mainBuildingId.isPresent() && building.getBuildingId().equals(mainBuildingId.get())) {
                        mainBuildingEntrance = entrance;
                    }
                }
            }
            
            if (mainBuildingEntrance == null && !buildingEntrances.isEmpty()) {
                mainBuildingEntrance = buildingEntrances.get(0);
            }
            
                boolean pathSuccess = pathService.generatePathNetwork(
                    world, 
                    villageId, 
                    buildingEntrances,
                    mainBuildingEntrance,
                    pathBaseSeed
            );
            
            if (pathSuccess) {
                List<List<Block>> pathNetwork = pathService.getVillagePathNetwork(villageId);
                int totalPathBlocks = 0;
                List<VolumeMask> masks = metadataStore.getVolumeMasks(villageId);
                
                for (List<Block> pathSegment : pathNetwork) {
                    int placed = pathEmitter.emitPathWithSmoothing(world, pathSegment, cultureId, masks);
                    totalPathBlocks += placed;
                }
            }
        }
        
        // Emit a parseable seed-chain summary for harness verification (T026d7)
        LOGGER.info(String.format("[SEED] village=%d placement=%d path=%d", seed, placementSeed, pathBaseSeed));

        int persistedBuildingCount = metadataStore.getPlacementReceipts(villageId).size();
        LOGGER.info(String.format("[STRUCT] village: id=%s buildings=%d",
            villageId, persistedBuildingCount));


        // Persist per-attempt rejection counters so harnesses can analyze placement rejections (T026d12)
        try {
                VillageMetadataStore.PlacementRejectionCounters counters = new VillageMetadataStore.PlacementRejectionCounters(
                    rejectionTracker.totalAttempts,
                    rejectionTracker.fluidRejections,
                    rejectionTracker.steepRejections,
                    rejectionTracker.blockedRejections,
                    rejectionTracker.spacingRejections,
                    rejectionTracker.overlapRejections,
                    rejectionTracker.chunkNotReady,
                    rejectionTracker.siteValidationRejects,
                    rejectionTracker.terraformRejects,
                    rejectionTracker.totalAttempts
                );
            metadataStore.recordPlacementRejectionCounters(villageId, counters);
        } catch (Exception e) {
            LOGGER.warning(String.format("[STRUCT][DIAG] Failed to record placement rejection counters for village %s: %s", villageId, e.getMessage()));
        }
        
        return Optional.of(villageId);
    }

    private int spawnInitialVillagers(World world, UUID villageId, String cultureId, Location origin,
                                      int structuresPlaced, long placementSeed, SurfaceSolver surfaceSolver) {
        if (structuresPlaced <= 0) {
            return 0;
        }
        if (customVillagerService == null) {
            return 0;
        }

        int targetCount = computeInitialVillagerCount(structuresPlaced, villagersPerStructure);
        List<String> professions = getDefaultProfessions();

        Random random = new Random(placementSeed ^ villageId.getLeastSignificantBits());
        int spawned = 0;
        int attempts = 0;
        int maxAttempts = Math.max(8, targetCount * 8);
        int centerX = origin.getBlockX();
        int centerY = origin.getBlockY();
        int centerZ = origin.getBlockZ();

        while (spawned < targetCount && attempts < maxAttempts) {
            attempts++;

            int dx = random.nextInt(15) - 7;
            int dz = random.nextInt(15) - 7;
            if (dx == 0 && dz == 0) {
                continue;
            }

            int x = centerX + dx;
            int z = centerZ + dz;

            OptionalInt yOpt = surfaceSolver.nearestWalkable(x, z, centerY);
            if (!yOpt.isPresent()) {
                continue;
            }

            int y = yOpt.getAsInt();
            if (!isSafeSpawnLocation(world, x, y, z)) {
                continue;
            }

            String profession = professions.get(spawned % professions.size());
            String definitionId = cultureId + "_" + profession;

            Location spawnLoc = new Location(world, x + 0.5, y, z + 0.5);
            var customVillager = customVillagerService.spawnVillager(
                definitionId,
                cultureId,
                profession,
                villageId,
                spawnLoc
            );

            if (customVillager != null) {
                spawned++;
                if (villagerAppearanceAdapter != null) {
                    org.bukkit.entity.Entity entity = world.getEntity(customVillager.getEntityId());
                    if (entity != null) {
                        villagerAppearanceAdapter.applyAppearance(entity, definitionId);
                    }
                }
            }
        }

        return spawned;
    }

    private List<String> getDefaultProfessions() {
        return Arrays.asList("merchant", "blacksmith", "elder");
    }

    private boolean isSafeSpawnLocation(World world, int x, int y, int z) {
        Block ground = world.getBlockAt(x, y - 1, z);
        Block body = world.getBlockAt(x, y, z);

        Material groundType = ground.getType();
        Material bodyType = body.getType();

        if (!groundType.isSolid()) {
            return false;
        }
        if (isFluid(groundType)) {
            return false;
        }
        if (bodyType.isSolid()) {
            return false;
        }
        return !isFluid(bodyType);
    }

    private boolean isFluid(Material type) {
        return type == Material.WATER || type == Material.LAVA;
    }

    /**
     * T072: Attempt to place remaining structures for an existing village.
     * Does not re-register the village or clear existing metadata.
     */
    public PlacementOutcome placeStructuresForExistingVillage(World world, Location origin, String cultureId,
                                                              long seed, UUID villageId) {
        Optional<VillageMetadataStore.VillageMetadata> metadataOpt = metadataStore.getVillage(villageId);
        long effectiveSeed = metadataOpt.map(VillageMetadataStore.VillageMetadata::getSeed).orElse(seed);

        if (metadataOpt.isEmpty()) {
            metadataStore.registerVillage(villageId, cultureId, origin, effectiveSeed);
        }

        List<Building> existingBuildings = metadataStore.getVillageBuildings(villageId);
        Set<String> existingStructureIds = new HashSet<>();
        for (Building building : existingBuildings) {
            existingStructureIds.add(building.getStructureId());
        }

        Random villageRandom = new Random(effectiveSeed);
        long placementSeed = villageRandom.nextLong();
        long pathBaseSeed = new Random(placementSeed).nextLong();

        List<String> structureIds = getCultureStructures(cultureId, placementSeed);
        List<String> remainingStructureIds = new ArrayList<>();
        for (String structureId : structureIds) {
            if (!existingStructureIds.contains(structureId)) {
                remainingStructureIds.add(structureId);
            }
        }

        if (remainingStructureIds.isEmpty()) {
            LOGGER.info(String.format("[STRUCT][T079] Village %s already has all %d structure types; allowing repeats (no cap).",
                    villageId, structureIds.size()));
            remainingStructureIds.addAll(structureIds);
            Collections.shuffle(remainingStructureIds, new Random(placementSeed ^ existingBuildings.size()));
        }

        LOGGER.info(String.format("[STRUCT][T072] Existing village placement: id=%s existingBuildings=%d remaining=%d seedChain=%d:%d",
                villageId, existingBuildings.size(), remainingStructureIds.size(), effectiveSeed, placementSeed));

        PlacementRejectionTracker rejectionTracker = new PlacementRejectionTracker();
        List<Building> placedBuildings = new ArrayList<>();

        SurfaceSolver surfaceSolver = new SurfaceSolver(world, metadataStore.getVolumeMasks(villageId));

        final int maxCandidatesPerStructure = DEFAULT_MAX_CANDIDATES_PER_STRUCTURE;

        for (int i = 0; i < remainingStructureIds.size(); i++) {
            String structureId = remainingStructureIds.get(i);

            Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
            if (!dimensions.isPresent()) {
                continue;
            }

            int[] dims = dimensions.get();
            int width = dims[0];
            int depth = dims[2];
            int height = dims[1];

            long buildingSeed = placementSeed + i;

            List<VolumeMask> existingMasks = metadataStore.getVolumeMasks(villageId);

                List<CandidateSite> candidatePositions = findCandidatePositions(
                    world, origin, width, depth, height, buildingSeed,
                    existingMasks, surfaceSolver, rejectionTracker, villageId, structureId);

            if (candidatePositions.isEmpty()) {
                LOGGER.info(String.format("[STRUCT][T072] No collision-free candidates for %s, skipping structure", structureId));
                continue;
            }

            boolean placed = false;
            int candidatesTried = 0;
            int candidatesToTry = Math.min(candidatePositions.size(), maxCandidatesPerStructure);

            for (int candidateIdx = 0; candidateIdx < candidatesToTry && !placed; candidateIdx++) {
                CandidateSite candidate = candidatePositions.get(candidateIdx);
                candidatesTried++;

                Location buildingLocation = new Location(world, candidate.x, candidate.y, candidate.z);

                java.util.Map<String, Integer> attemptDiagnostics = new java.util.HashMap<>();
                Optional<PlacementReceipt> receiptOpt = structureService.placeStructureAndGetReceipt(
                    structureId, world, buildingLocation, buildingSeed, villageId, existingMasks,
                    minBuildingSpacing, attemptDiagnostics, candidate.rotationDegrees);

                if (receiptOpt.isPresent()) {
                    PlacementReceipt receipt = receiptOpt.get();
                    metadataStore.addPlacementReceipt(villageId, receipt);

                    VolumeMask placedMask = VolumeMask.fromReceipt(receipt);
                    metadataStore.addVolumeMask(villageId, placedMask);
                    surfaceSolver = new SurfaceSolver(world, metadataStore.getVolumeMasks(villageId));

                    UUID deterministicBuildingId = UUID.nameUUIDFromBytes(
                            (villageId.toString() + ":" + structureId + ":" + buildingSeed).getBytes(StandardCharsets.UTF_8));

                    Building building = new Building.Builder()
                            .buildingId(deterministicBuildingId)
                            .villageId(villageId)
                            .structureId(structureId)
                            .origin(new Location(world, receipt.getOriginX(), receipt.getOriginY(), receipt.getOriginZ()))
                            .dimensions(receipt.getEffectiveWidth(), receipt.getHeight(), receipt.getEffectiveDepth())
                            .build();

                    placedBuildings.add(building);
                    placed = true;

                    LOGGER.info(String.format("[STRUCT] receipt: id=%s bounds=[%d..%d,%d..%d,%d..%d] rot=%d° candidatesTried=%d",
                            structureId,
                            receipt.getMinX(), receipt.getMaxX(),
                            receipt.getMinY(), receipt.getMaxY(),
                            receipt.getMinZ(), receipt.getMaxZ(),
                            receipt.getRotation(),
                            candidatesTried));
                } else {
                    if (attemptDiagnostics != null && !attemptDiagnostics.isEmpty()) {
                        rejectionTracker.totalAttempts += attemptDiagnostics.getOrDefault("placementAttempts", 0);
                        int siteValidationRejects = attemptDiagnostics.getOrDefault("siteValidationRejects",
                            attemptDiagnostics.getOrDefault("terrainInvalid", 0));
                        int terraformRejects = attemptDiagnostics.getOrDefault("terraformRejects", 0);
                        if (terraformRejects == 0) {
                            terraformRejects = attemptDiagnostics.getOrDefault("terraformCommitFailed", 0);
                        }
                        rejectionTracker.terrainRejections += siteValidationRejects;
                        rejectionTracker.siteValidationRejects += siteValidationRejects;
                        rejectionTracker.terraformRejects += terraformRejects;
                        rejectionTracker.chunkNotReady += attemptDiagnostics.getOrDefault("chunkNotReady", 0);
                        rejectionTracker.overlapRejections += attemptDiagnostics.getOrDefault("overlap", 0);
                        int fluidCount = attemptDiagnostics.getOrDefault("fluid", attemptDiagnostics.getOrDefault("water", 0));
                        rejectionTracker.fluidRejections += fluidCount;
                        rejectionTracker.steepRejections += attemptDiagnostics.getOrDefault("steep", 0);
                        rejectionTracker.blockedRejections += attemptDiagnostics.getOrDefault("blocked", 0);
                    }
                        LOGGER.fine(String.format("[STRUCT][T072] Candidate %d/%d rejected for %s at (%d,%d,%d) rot=%d",
                            candidateIdx + 1, candidatesToTry, structureId, candidate.x, candidate.y, candidate.z,
                            candidate.rotationDegrees));
                }
            }

            if (!placed) {
                LOGGER.info(String.format("[STRUCT][T072] Failed to place %s after trying %d/%d candidates",
                        structureId, candidatesTried, candidatePositions.size()));
            }
        }

        if (placedBuildings.isEmpty()) {
            LOGGER.warning(String.format("[STRUCT][T072] No additional structures placed for village %s (existing=%d, remaining=%d)",
                    villageId, existingBuildings.size(), remainingStructureIds.size()));

            try {
                VillageMetadataStore.PlacementRejectionCounters counters = new VillageMetadataStore.PlacementRejectionCounters(
                        rejectionTracker.totalAttempts,
                        rejectionTracker.fluidRejections,
                        rejectionTracker.steepRejections,
                        rejectionTracker.blockedRejections,
                        rejectionTracker.spacingRejections,
                        rejectionTracker.overlapRejections,
                        rejectionTracker.chunkNotReady,
                    rejectionTracker.siteValidationRejects,
                    rejectionTracker.terraformRejects,
                        rejectionTracker.totalAttempts
                );
                metadataStore.recordPlacementRejectionCounters(villageId, counters);
            } catch (Exception e) {
                LOGGER.warning(String.format("[STRUCT][DIAG] Failed to record placement rejection counters for village %s: %s", villageId, e.getMessage()));
            }

            return new PlacementOutcome(PlacementStatus.FAILED, villageId, existingBuildings.size(), 0, structureIds.size());
        }

        for (Building building : placedBuildings) {
            metadataStore.addBuilding(villageId, building);
        }

        if (metadataStore.getMainBuilding(villageId).isEmpty()) {
            List<Building> allBuildings = new ArrayList<>(existingBuildings);
            allBuildings.addAll(placedBuildings);
            Optional<UUID> mainBuildingId = mainBuildingSelector.selectMainBuilding(cultureId, allBuildings);
            mainBuildingId.ifPresent(id -> metadataStore.setMainBuilding(villageId, id));
        }

        LOGGER.info(String.format("[SEED] village=%d placement=%d path=%d", effectiveSeed, placementSeed, pathBaseSeed));

        LOGGER.info(String.format("[STRUCT][T072] village: id=%s existing=%d added=%d total=%d",
                villageId, existingBuildings.size(), placedBuildings.size(), existingBuildings.size() + placedBuildings.size()));

        try {
                VillageMetadataStore.PlacementRejectionCounters counters = new VillageMetadataStore.PlacementRejectionCounters(
                    rejectionTracker.totalAttempts,
                    rejectionTracker.fluidRejections,
                    rejectionTracker.steepRejections,
                    rejectionTracker.blockedRejections,
                    rejectionTracker.spacingRejections,
                    rejectionTracker.overlapRejections,
                    rejectionTracker.chunkNotReady,
                    rejectionTracker.siteValidationRejects,
                    rejectionTracker.terraformRejects,
                    rejectionTracker.totalAttempts
                );
            metadataStore.recordPlacementRejectionCounters(villageId, counters);
        } catch (Exception e) {
            LOGGER.warning(String.format("[STRUCT][DIAG] Failed to record placement rejection counters for village %s: %s", villageId, e.getMessage()));
        }

        return new PlacementOutcome(PlacementStatus.SUCCESS, villageId, existingBuildings.size(), placedBuildings.size(), structureIds.size());
    }
    
    @Override
    public boolean validateSite(World world, Location origin, int radius) {
        if (hasCollision(world, origin, radius)) {
            return false;
        }
        
        // Check terrain flatness within the radius
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        
        for (int x = -radius; x <= radius; x += 8) {
            for (int z = -radius; z <= radius; z += 8) {
                int y = world.getHighestBlockYAt(origin.getBlockX() + x, origin.getBlockZ() + z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        
        int yVariation = maxY - minY;
        return yVariation <= 20;
    }
    
    @Override
    public Optional<Building> placeBuilding(World world, Location location, String structureId, UUID villageId, long seed) {
        Optional<int[]> dimensions = structureService.getStructureDimensions(structureId);
        
        if (!dimensions.isPresent()) {
            return Optional.empty();
        }
        
        Optional<com.davisodom.villageoverhaul.worldgen.PlacementResult> placementResult = 
                structureService.placeStructureAndGetResult(structureId, world, location, seed);
        
        if (!placementResult.isPresent()) {
            return Optional.empty();
        }
        
        int[] dims = dimensions.get();
        UUID deterministicBuildingId = UUID.nameUUIDFromBytes((villageId.toString() + ":" + structureId + ":" + seed).getBytes(StandardCharsets.UTF_8));

        Building building = new Building.Builder()
            .buildingId(deterministicBuildingId)
                .villageId(villageId)
                .structureId(structureId)
                .origin(placementResult.get().getActualLocation())
                .dimensions(dims[0], dims[1], dims[2])
                .build();
        
        return Optional.of(building);
    }
    
    @Override
    public List<Building> getVillageBuildings(UUID villageId) {
        // Try cache first
        List<Building> buildings = villageBuildings.get(villageId);
        
        if (buildings != null) {
            return new ArrayList<>(buildings);
        }
        
        // Load from metadata store
        buildings = metadataStore.getVillageBuildings(villageId);
        
        if (!buildings.isEmpty()) {
            villageBuildings.put(villageId, buildings);
        }
        
        return buildings;
    }
    
    @Override
    public boolean hasCollision(World world, Location location, int radius) {
        // Check all loaded villages for collision
        for (Map.Entry<UUID, List<Building>> entry : villageBuildings.entrySet()) {
            for (Building building : entry.getValue()) {
                Location buildingLoc = building.getOrigin();
                
                if (buildingLoc.getWorld().equals(world)) {
                    double distance = buildingLoc.distance(location);
                    
                    if (distance < radius) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    @Override
    public boolean removeVillage(UUID villageId) {
        List<Building> buildings = villageBuildings.remove(villageId);
        
        if (buildings == null) {
            return false;
        }
        
        metadataStore.removeVillage(villageId);
        return true;
    }
    
    private List<String> getCultureStructures(String cultureId, long seed) {
        Optional<CultureService.Culture> cultureOpt = cultureService.get(cultureId);
        if (!cultureOpt.isPresent()) {
            return Collections.emptyList();
        }
        
        CultureService.Culture culture = cultureOpt.get();
        List<String> structureSet = culture.getStructureSet();
        
        if (structureSet == null || structureSet.isEmpty()) {
            return Collections.emptyList();
        }
        
        String mainBuildingStructureId = culture.getMainBuildingStructureId();
        if (mainBuildingStructureId == null || mainBuildingStructureId.isEmpty()) {
            mainBuildingStructureId = structureSet.get(0);
        }
        
        List<String> result = new ArrayList<>();
        result.add(mainBuildingStructureId);
        
        List<String> otherStructures = new ArrayList<>();
        for (String structureId : structureSet) {
            if (!structureId.equals(mainBuildingStructureId)) {
                otherStructures.add(structureId);
            }
        }
        
        // T026d1: Use seeded Random for deterministic shuffling
        Collections.shuffle(otherStructures, new Random(seed));
        result.addAll(otherStructures);
        
        return result;
    }
    
    /**
     * Find suitable placement position with integrated terrain, spacing, and overlap checks.
     * Uses spiral search pattern from origin.
     * R009: Uses SurfaceSolver for ground finding and VolumeMasks for overlap checks.
     * R011b: Uses rotation-aware collision detection with deterministic rotation.
     * T026d1: Deterministic candidate ordering using buildingSeed for consistent spiral iteration.
     * T026d2: Stable candidate site ordering & filtering - candidates sorted by deterministic key,
     *         filters applied in fixed sequence.
     * T052a: Time-budgeted chunk loading to prevent main thread blocking.
     * @deprecated Use findCandidatePositions for T070 multi-candidate retry support
     */
        @Deprecated
        private Optional<Location> findSuitablePlacementPosition(
            World world, Location origin, int width, int depth, int height, long buildingSeed,
            List<VolumeMask> existingMasks, SurfaceSolver surfaceSolver, PlacementRejectionTracker tracker,
            UUID villageId, String structureId) {

        List<CandidateSite> candidates = findCandidatePositions(world, origin, width, depth, height,
                buildingSeed, existingMasks, surfaceSolver, tracker, villageId, structureId);
        
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        
        CandidateSite first = candidates.get(0);
        return Optional.of(new Location(world, first.x, first.y, first.z));
    }
    
    /**
     * T070: Find ALL collision-free candidate positions for structure placement.
     * Returns a sorted list of candidates that pass collision checks (no overlap with existing masks).
     * Terrain validation (steep/blocked/fluid) is NOT done here - that's done by StructureService.
     * The caller should iterate through candidates and retry placement if terrain validation fails.
     * 
     * Uses spiral search pattern from origin.
     * R009: Uses SurfaceSolver for ground finding and VolumeMasks for overlap checks.
     * R011b: Uses rotation-aware collision detection with deterministic rotation.
     * T026d1: Deterministic candidate ordering using buildingSeed for consistent spiral iteration.
     * T026d2: Stable candidate site ordering & filtering - candidates sorted by deterministic key,
     *         filters applied in fixed sequence.
     * T052a: Time-budgeted chunk loading to prevent main thread blocking.
     * 
     * @return Sorted list of collision-free candidate sites (may be empty if no valid positions found)
     */
    private List<CandidateSite> findCandidatePositions(
            World world, Location origin, int width, int depth, int height, long buildingSeed,
            List<VolumeMask> existingMasks, SurfaceSolver surfaceSolver, PlacementRejectionTracker tracker,
            UUID villageId, String structureId) {

        // T077: Enforce candidate search within configured max village bounds
        final int maxRadius = maxBoundsRadiusBlocks;
        final int gridSize = 4;
        final int boundsMinX = origin.getBlockX() - maxRadius;
        final int boundsMaxX = origin.getBlockX() + maxRadius;
        final int boundsMinZ = origin.getBlockZ() - maxRadius;
        final int boundsMaxZ = origin.getBlockZ() + maxRadius;

        // T071: Track chunks that were skipped (not loaded) for diagnostics
        int chunksSkipped = 0;
        int gridPointsVisited = 0;
        int gridPointsLoaded = 0;

        int steps = maxRadius / gridSize;
        int gridPointsTotal = (steps * 2 + 1);
        gridPointsTotal = gridPointsTotal * gridPointsTotal;

        // T026d2: Collect ALL candidate sites first, then sort deterministically
        List<CandidateSite> allCandidates = new ArrayList<>();

        VillageMetadataStore.CollisionDiagnostics collisionDiagnostics = null;
        if (villageId != null) {
            collisionDiagnostics = new VillageMetadataStore.CollisionDiagnostics(
                villageId.toString(), structureId, buildingSeed, minBuildingSpacing,
                existingMasks != null ? existingMasks.size() : 0);
        }

        // T077: Enumerate rotations deterministically per building seed
        int[] rotationOrder = getRotationOrder(buildingSeed);

        // Spiral search pattern: start at origin, expand outward
        for (int radius = 0; radius <= maxRadius; radius += gridSize) {
            // For each ring, collect all candidate positions
            for (int dx = -radius; dx <= radius; dx += gridSize) {
                for (int dz = -radius; dz <= radius; dz += gridSize) {
                    // Skip interior points (already checked in previous rings)
                    if (radius > 0 && Math.abs(dx) < radius && Math.abs(dz) < radius) {
                        continue;
                    }

                    int candidateX = origin.getBlockX() + dx;
                    int candidateZ = origin.getBlockZ() + dz;

                    gridPointsVisited++;

                    int chunkX = candidateX >> 4;
                    int chunkZ = candidateZ >> 4;

                    // T071: Skip unloaded chunks instead of loading them synchronously
                    // Previous approach (T057h) loaded 700+ chunks synchronously causing 19s freezes.
                    // Now we only consider already-loaded chunks for placement candidates.
                    // For command-based placement, the player's loaded chunks provide sufficient
                    // candidates. For async village generation, chunks should be pre-loaded
                    // asynchronously before calling this method.
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        chunksSkipped++; // Track skipped chunks for diagnostics
                        continue; // Skip this candidate - chunk not ready
                    }

                    gridPointsLoaded++;

                    // Calculate distance from origin for sorting
                    int distanceSquared = dx * dx + dz * dz;

                    for (int rotationIndex = 0; rotationIndex < rotationOrder.length; rotationIndex++) {
                        int rotation = rotationOrder[rotationIndex];

                        // R009: Use SurfaceSolver to find footprint-aware ground level
                        // Compute the minimum surface height under the rotated footprint
                        int[] xzBounds = computeRotatedXZBounds(candidateX, candidateZ, width, depth, rotation);
                        if (!isFootprintChunkReady(world, xzBounds[0], xzBounds[1], xzBounds[2], xzBounds[3], 1)) {
                            if (tracker != null) tracker.recordChunkNotReady();
                            continue;
                        }
                        int baseY = computeFootprintBaseY(surfaceSolver, xzBounds[0], xzBounds[1], xzBounds[2], xzBounds[3]);
                        int candidateY = baseY + 1;

                        allCandidates.add(new CandidateSite(candidateX, candidateY, candidateZ,
                                distanceSquared, dx, dz, rotation, rotationIndex));
                    }
                }
            }
        }
        
        // T071: Log chunk skip stats for diagnostics (no longer loading chunks synchronously)
        if (chunksSkipped > 0) {
            LOGGER.info(String.format("[STRUCT][CHUNK-DIAG] Skipped %d unloaded chunks during candidate search", chunksSkipped));
        }
        
        // T026d2: Sort candidates by deterministic key: distance, then X, then Z, then rotation order
        // This ensures same-seed runs produce identical candidate sequences
        allCandidates.sort((a, b) -> {
            // Primary: distance from origin (closer sites first)
            int distCompare = Integer.compare(a.distanceSquared, b.distanceSquared);
            if (distCompare != 0) return distCompare;
            
            // Secondary: X coordinate (stable tie-breaker)
            int xCompare = Integer.compare(a.x, b.x);
            if (xCompare != 0) return xCompare;
            
            // Tertiary: Z coordinate (final tie-breaker)
            int zCompare = Integer.compare(a.z, b.z);
            if (zCompare != 0) return zCompare;

            return Integer.compare(a.rotationOrderIndex, b.rotationOrderIndex);
        });
        
        // T070: Collect all collision-free candidates instead of returning first one
        List<CandidateSite> validCandidates = new ArrayList<>();
        
        // T026d2: Apply filters in fixed sequence to each candidate
        // Fixed sequence: 1) Collision check, 2) Spacing relaxation (if needed)
        int collisionRejections = 0;
        
        int candidateIndex = 0;
        for (CandidateSite candidate : allCandidates) {
            candidateIndex++;
            if (tracker != null) tracker.recordAttempt();
            
            // Compute rotated AABB for this candidate location with determined rotation
            Location candidateLoc = new Location(world, candidate.x, candidate.y, candidate.z);
            int[] candidateAABB = computeRotatedAABB(candidateLoc, width, depth, height, candidate.rotationDegrees);
            
            // Filter 1: Check collision with existing masks (including spacing buffer)
            boolean overlaps = checkRotatedAABBCollision(candidateAABB, existingMasks, minBuildingSpacing,
                collisionDiagnostics, candidate, candidateIndex, "spacing");

            // Filter 2: If blocked by spacing, try a progressive relaxation (half spacing, then zero)
            if (overlaps && minBuildingSpacing > 0) {
                int half = Math.max(0, minBuildingSpacing / 2);
                if (half != minBuildingSpacing) {
                    boolean overlapsHalf = checkRotatedAABBCollision(candidateAABB, existingMasks, half,
                        collisionDiagnostics, candidate, candidateIndex, "relaxedHalf");
                    if (!overlapsHalf) {
                        LOGGER.fine(String.format("[STRUCT] findCandidates: relaxing spacing %d->%d for candidate (%d,%d)", 
                                minBuildingSpacing, half, candidate.dx, candidate.dz));
                        overlaps = false;
                    }
                }
            }

            if (overlaps && minBuildingSpacing > 1) {
                // Final attempt without spacing
                boolean overlapsZero = checkRotatedAABBCollision(candidateAABB, existingMasks, 0,
                    collisionDiagnostics, candidate, candidateIndex, "relaxedZero");
                if (!overlapsZero) {
                    LOGGER.fine(String.format("[STRUCT] findCandidates: relaxing spacing %d->0 for candidate (%d,%d)", 
                            minBuildingSpacing, candidate.dx, candidate.dz));
                    overlaps = false;
                }
            }
            
            if (overlaps) {
                if (tracker != null) tracker.recordOverlapRejection();
                collisionRejections++;
                continue;
            }
            
            // T070: Add valid candidate to list instead of returning immediately
            validCandidates.add(candidate);
            
            // Log deterministic candidate sequence info
            LOGGER.fine(String.format("[STRUCT] findCandidates: Valid candidate at offset=(%d,%d) " +
                "pos=(%d,%d,%d) rot=%d dist²=%d validCount=%d rejected=%d seed=%d", 
                candidate.dx, candidate.dz, candidate.x, candidate.y, candidate.z, candidate.rotationDegrees,
                candidate.distanceSquared, validCandidates.size(), collisionRejections, buildingSeed));
        }
        
        // T070: Log summary of candidate search
        if (validCandidates.isEmpty()) {
            // T026d3: Log retry sequence hash for determinism verification
            String retryHash = computeRetrySequenceHash(allCandidates);
            
            LOGGER.warning(String.format("[STRUCT] findCandidates: No collision-free candidates found within radius=%d " +
                "checked=%d rejected=%d seed=%d retryHash=%s",
                maxRadius, tracker != null ? tracker.totalAttempts : 0, collisionRejections, buildingSeed, retryHash));
        } else {
            LOGGER.info(String.format("[STRUCT][T070] Found %d collision-free candidates (checked=%d, rejected=%d, seed=%d)",
                validCandidates.size(), allCandidates.size(), collisionRejections, buildingSeed));
        }

        // T077: Log and persist candidate sampling coverage
        int rotationCount = rotationOrder.length;
        int candidatesChecked = allCandidates.size();
        String boundsLog = String.format("[STRUCT][BOUNDS] structure=%s origin=(%d,%d,%d) radius=%d bounds=[%d..%d,%d..%d] " +
                        "gridSize=%d gridPoints=%d loaded=%d rotations=%d candidates=%d valid=%d skippedChunks=%d",
                structureId,
                origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(),
                maxRadius, boundsMinX, boundsMaxX, boundsMinZ, boundsMaxZ,
                gridSize, gridPointsTotal, gridPointsLoaded, rotationCount,
                candidatesChecked, validCandidates.size(), chunksSkipped);
        LOGGER.info(boundsLog);

        if (villageId != null) {
            VillageMetadataStore.CandidateCoverageSummary coverage =
                    new VillageMetadataStore.CandidateCoverageSummary(
                            structureId,
                            origin.getBlockX(), origin.getBlockZ(), maxRadius,
                            boundsMinX, boundsMaxX, boundsMinZ, boundsMaxZ,
                            gridSize, rotationCount, gridPointsTotal, gridPointsLoaded,
                            candidatesChecked, validCandidates.size(), chunksSkipped, buildingSeed);
            metadataStore.recordCandidateCoverageSummary(villageId, coverage);
        }

        if (collisionDiagnostics != null) {
            collisionDiagnostics.candidatesChecked = allCandidates.size();
            metadataStore.recordCollisionDiagnostics(villageId, collisionDiagnostics);
        }

        return validCandidates;
    }
    
    /**
     * Compute rotated AABB bounds for a structure at given origin with specified rotation.
     * Used for collision detection BEFORE actual placement.
     * 
     * @param origin Structure origin (SW corner, ground level)
     * @param baseWidth Base structure width (X, before rotation)
     * @param baseDepth Base structure depth (Z, before rotation)
     * @param height Structure height (Y, unchanged by rotation)
     * @param rotation Rotation in degrees (0, 90, 180, or 270)
     * @return int[] {minX, maxX, minY, maxY, minZ, maxZ} - rotated AABB bounds
     */
    private int[] computeRotatedAABB(Location origin, int baseWidth, int baseDepth, int height, int rotation) {
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();
        
        // Calculate the 8 corners of the bounding box in schematic space (origin at 0,0,0)
        int[][] corners = new int[8][3];
        int idx = 0;
        for (int x : new int[]{0, baseWidth}) {
            for (int y : new int[]{0, height}) {
                for (int z : new int[]{0, baseDepth}) {
                    corners[idx][0] = x;
                    corners[idx][1] = y;
                    corners[idx][2] = z;
                    idx++;
                }
            }
        }
        
        // Rotate each corner around origin (0,0,0) using Y-axis rotation matrix
        int[][] rotatedCorners = new int[8][3];
        for (int i = 0; i < 8; i++) {
            int x = corners[i][0];
            int y = corners[i][1];
            int z = corners[i][2];
            
            // Apply Y-axis rotation (clockwise when viewed from above)
            switch (rotation) {
                case 0:
                    rotatedCorners[i][0] = x;
                    rotatedCorners[i][2] = z;
                    break;
                case 90:
                    rotatedCorners[i][0] = -z;
                    rotatedCorners[i][2] = x;
                    break;
                case 180:
                    rotatedCorners[i][0] = -x;
                    rotatedCorners[i][2] = -z;
                    break;
                case 270:
                    rotatedCorners[i][0] = z;
                    rotatedCorners[i][2] = -x;
                    break;
            }
            rotatedCorners[i][1] = y; // Y unchanged
        }
        
        // Find min/max of rotated corners
        int minRotX = Integer.MAX_VALUE, maxRotX = Integer.MIN_VALUE;
        int minRotY = Integer.MAX_VALUE, maxRotY = Integer.MIN_VALUE;
        int minRotZ = Integer.MAX_VALUE, maxRotZ = Integer.MIN_VALUE;
        
        for (int i = 0; i < 8; i++) {
            minRotX = Math.min(minRotX, rotatedCorners[i][0]);
            maxRotX = Math.max(maxRotX, rotatedCorners[i][0]);
            minRotY = Math.min(minRotY, rotatedCorners[i][1]);
            maxRotY = Math.max(maxRotY, rotatedCorners[i][1]);
            minRotZ = Math.min(minRotZ, rotatedCorners[i][2]);
            maxRotZ = Math.max(maxRotZ, rotatedCorners[i][2]);
        }
        
        // Translate to world coordinates
        int minX = originX + minRotX;
        int maxX = originX + maxRotX - 1; // -1 because size is exclusive
        int minY = originY + minRotY;
        int maxY = originY + maxRotY - 1;
        int minZ = originZ + minRotZ;
        int maxZ = originZ + maxRotZ - 1;
        
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    /**
     * Compute rotated XZ bounds for a footprint without requiring a specific Y.
     *
     * @param originX Origin X
     * @param originZ Origin Z
     * @param baseWidth Base structure width (X, before rotation)
     * @param baseDepth Base structure depth (Z, before rotation)
     * @param rotation Rotation in degrees (0, 90, 180, or 270)
     * @return int[] {minX, maxX, minZ, maxZ}
     */
    private int[] computeRotatedXZBounds(int originX, int originZ, int baseWidth, int baseDepth, int rotation) {
        int[][] corners = new int[4][2];
        int idx = 0;
        for (int x : new int[]{0, baseWidth}) {
            for (int z : new int[]{0, baseDepth}) {
                corners[idx][0] = x;
                corners[idx][1] = z;
                idx++;
            }
        }

        int minRotX = Integer.MAX_VALUE, maxRotX = Integer.MIN_VALUE;
        int minRotZ = Integer.MAX_VALUE, maxRotZ = Integer.MIN_VALUE;

        for (int i = 0; i < corners.length; i++) {
            int x = corners[i][0];
            int z = corners[i][1];
            int rotX = 0;
            int rotZ = 0;

            switch (rotation) {
                case 0:
                    rotX = x;
                    rotZ = z;
                    break;
                case 90:
                    rotX = -z;
                    rotZ = x;
                    break;
                case 180:
                    rotX = -x;
                    rotZ = -z;
                    break;
                case 270:
                    rotX = z;
                    rotZ = -x;
                    break;
            }

            minRotX = Math.min(minRotX, rotX);
            maxRotX = Math.max(maxRotX, rotX);
            minRotZ = Math.min(minRotZ, rotZ);
            maxRotZ = Math.max(maxRotZ, rotZ);
        }

        int minX = originX + minRotX;
        int maxX = originX + maxRotX - 1;
        int minZ = originZ + minRotZ;
        int maxZ = originZ + maxRotZ - 1;

        return new int[]{minX, maxX, minZ, maxZ};
    }

    /**
     * Compute the reference surface height across a footprint bounds in XZ.
     * Uses MEDIAN height to avoid outliers (like frozen water over deep pools) 
     * dragging structures underground. Samples corners and a grid of intermediate 
     * points to balance accuracy and performance.
     * 
     * Previous approach used MIN height which caused structures to be placed at the
     * lowest corner's ground level, burying them if one corner was over water/ice.
     */
    private int computeFootprintBaseY(SurfaceSolver surfaceSolver, int minX, int maxX, int minZ, int maxZ) {
        java.util.List<Integer> samples = new java.util.ArrayList<>();
        
        // Sample corners
        samples.add(surfaceSolver.getSurfaceHeight(minX, minZ));
        samples.add(surfaceSolver.getSurfaceHeight(maxX, minZ));
        samples.add(surfaceSolver.getSurfaceHeight(minX, maxZ));
        samples.add(surfaceSolver.getSurfaceHeight(maxX, maxZ));
        
        // Sample a 3x3 grid of intermediate points for larger footprints
        int width = maxX - minX;
        int depth = maxZ - minZ;
        if (width > 2 || depth > 2) {
            int midX = minX + width / 2;
            int midZ = minZ + depth / 2;
            
            // Sample midpoints along edges
            samples.add(surfaceSolver.getSurfaceHeight(midX, minZ));
            samples.add(surfaceSolver.getSurfaceHeight(maxX, midZ));
            samples.add(surfaceSolver.getSurfaceHeight(midX, maxZ));
            samples.add(surfaceSolver.getSurfaceHeight(minX, midZ));
            
            // Sample center
            samples.add(surfaceSolver.getSurfaceHeight(midX, midZ));
        }

        if (samples.isEmpty()) {
            return surfaceSolver.getSurfaceHeight(minX, minZ);
        }
        
        // Sort and return median value
        // Median is more robust against outliers than min or mean
        java.util.Collections.sort(samples);
        int medianIndex = samples.size() / 2;
        return samples.get(medianIndex);
    }

    /**
     * Check if all chunks covering a footprint (with optional buffer) are ready.
     * Avoids synchronous chunk loads during candidate search.
     */
    private boolean isFootprintChunkReady(World world, int minX, int maxX, int minZ, int maxZ, int buffer) {
        int bufferedMinX = minX - buffer;
        int bufferedMaxX = maxX + buffer;
        int bufferedMinZ = minZ - buffer;
        int bufferedMaxZ = maxZ + buffer;

        int minChunkX = bufferedMinX >> 4;
        int maxChunkX = bufferedMaxX >> 4;
        int minChunkZ = bufferedMinZ >> 4;
        int maxChunkZ = bufferedMaxZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // Accept chunks that are EITHER generated (saved) OR loaded (in memory)
                boolean chunkReady = world.isChunkGenerated(cx, cz) || world.isChunkLoaded(cx, cz);
                if (!chunkReady) {
                    return false;
                }
            }
        }

        return true;
    }
    
    /**
     * Check if a rotated AABB intersects with any existing volume mask (with buffer).
     * 
     * @param candidateAABB Candidate structure AABB bounds
     * @param existingMasks List of existing volume masks
     * @param buffer Spacing buffer to apply around existing masks
     * @return true if collision detected, false otherwise
     */
    private boolean checkRotatedAABBCollision(int[] candidateAABB, List<VolumeMask> existingMasks, int buffer,
                                              VillageMetadataStore.CollisionDiagnostics diagnostics,
                                              CandidateSite candidate, int candidateIndex, String phase) {
        int candMinX = candidateAABB[0];
        int candMaxX = candidateAABB[1];
        int candMinZ = candidateAABB[4];
        int candMaxZ = candidateAABB[5];

        boolean collision = false;
        VillageMetadataStore.CollisionCheckEntry entry = null;
        if (diagnostics != null && diagnostics.checks.size() < MAX_COLLISION_DIAGNOSTIC_ENTRIES) {
            entry = new VillageMetadataStore.CollisionCheckEntry();
            entry.candidateIndex = candidateIndex;
            entry.candidateX = candidate.x;
            entry.candidateY = candidate.y;
            entry.candidateZ = candidate.z;
            entry.rotationDegrees = candidate.rotationDegrees;
            entry.buffer = buffer;
            entry.distanceSquared = candidate.distanceSquared;
            entry.dx = candidate.dx;
            entry.dz = candidate.dz;
            entry.phase = phase;
            entry.candidateAabb = candidateAABB;
        } else if (diagnostics != null && diagnostics.checks.size() >= MAX_COLLISION_DIAGNOSTIC_ENTRIES) {
            diagnostics.truncated = true;
        }

        int masksChecked = 0;
        if (existingMasks != null) {
            for (VolumeMask mask : existingMasks) {
                masksChecked++;
                // Expand mask by buffer
                int maskMinX = mask.getMinX() - buffer;
                int maskMaxX = mask.getMaxX() + buffer;
                int maskMinZ = mask.getMinZ() - buffer;
                int maskMaxZ = mask.getMaxZ() + buffer;

                // Check 2D XZ intersection (sufficient for building spacing)
                boolean xOverlap = candMinX <= maskMaxX && candMaxX >= maskMinX;
                boolean zOverlap = candMinZ <= maskMaxZ && candMaxZ >= maskMinZ;
                boolean overlap = xOverlap && zOverlap;

                if (entry != null && overlap) {
                    VillageMetadataStore.CollisionMaskEntry maskEntry = new VillageMetadataStore.CollisionMaskEntry();
                    maskEntry.structureId = mask.getStructureId();
                    maskEntry.maskAabb = new int[]{mask.getMinX(), mask.getMaxX(), mask.getMinY(), mask.getMaxY(), mask.getMinZ(), mask.getMaxZ()};
                    maskEntry.expandedAabb = new int[]{maskMinX, maskMaxX, mask.getMinY(), mask.getMaxY(), maskMinZ, maskMaxZ};
                    maskEntry.xOverlap = xOverlap;
                    maskEntry.zOverlap = zOverlap;
                    maskEntry.collision = overlap;
                    entry.overlaps.add(maskEntry);

                    String candidateStructureId = diagnostics != null ? diagnostics.structureId : "unknown";
                    LOGGER.info(String.format("[STRUCT][COLLISION-DIAG] structure=%s candidate=(%d,%d,%d) rot=%d buffer=%d phase=%s candidateAABB=(%d..%d,%d..%d,%d..%d) mask=%s expanded=(%d..%d,%d..%d,%d..%d) overlap=true",
                        candidateStructureId, candidate.x, candidate.y, candidate.z, candidate.rotationDegrees, buffer, phase,
                        candMinX, candMaxX, candidateAABB[2], candidateAABB[3], candMinZ, candMaxZ,
                        mask.getStructureId(), maskMinX, maskMaxX, mask.getMinY(), mask.getMaxY(), maskMinZ, maskMaxZ));
                }

                if (overlap) {
                    collision = true;
                }
            }
        }

        if (entry != null) {
            entry.collision = collision;
            entry.masksChecked = masksChecked;
            diagnostics.checks.add(entry);
        }

        return collision;
    }
    
    /**
     * Check if a proposed village location violates inter-village spacing requirements.
     * 
     * @param proposedOrigin Proposed village origin (center)
     * @param minDistance Minimum border-to-border distance required
     * @return true if spacing is acceptable, false if too close to an existing village
     */
    private boolean checkInterVillageSpacing(Location proposedOrigin, int minDistance) {
        // Create a temporary border for the proposed village at its origin (point)
        // We check the origin point against all existing village borders
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
                proposedOrigin.getBlockX(), proposedOrigin.getBlockX(),
                proposedOrigin.getBlockZ(), proposedOrigin.getBlockZ()
        );
        
        // Check against all existing villages in the same world
        for (VillageMetadataStore.VillageMetadata existingVillage : metadataStore.getAllVillages()) {
            // Skip villages in different worlds
            if (!existingVillage.getOrigin().getWorld().equals(proposedOrigin.getWorld())) {
                continue;
            }
            
            VillageMetadataStore.VillageBorder existingBorder = existingVillage.getBorder();
            
            // Check if borders are within minimum distance
            if (proposedBorder.isWithinDistance(existingBorder, minDistance)) {
                int actualDistance = proposedBorder.getDistanceTo(existingBorder);
                LOGGER.fine(String.format("[STRUCT] Inter-village spacing violation: proposed=%s, existing=%s (village=%s), distance=%d, required=%d",
                        formatLocation(proposedOrigin), existingBorder, existingVillage.getVillageId(), actualDistance, minDistance));
                return false;
            }
        }
        
        return true; // No violations found
    }
    
    /**
     * Check inter-village spacing with detailed metrics for observability.
     * (Constitution v1.5.0, Principle XII - Observability)
     * 
     * @param proposedOrigin Proposed village origin (center)
     * @param minDistance Minimum border-to-border distance required
     * @return InterVillageSpacingResult with acceptance status and metrics
     */
    private InterVillageSpacingResult checkInterVillageSpacingDetailed(Location proposedOrigin, int minDistance) {
        // Create a temporary border for the proposed village at its origin (point)
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
                proposedOrigin.getBlockX(), proposedOrigin.getBlockX(),
                proposedOrigin.getBlockZ(), proposedOrigin.getBlockZ()
        );
        
        // Check against all existing villages in the same world
        for (VillageMetadataStore.VillageMetadata existingVillage : metadataStore.getAllVillages()) {
            // Skip villages in different worlds
            if (!existingVillage.getOrigin().getWorld().equals(proposedOrigin.getWorld())) {
                continue;
            }
            
            VillageMetadataStore.VillageBorder existingBorder = existingVillage.getBorder();
            
            if (proposedBorder.isWithinDistance(existingBorder, minDistance)) {
                int actualDistance = proposedBorder.getDistanceTo(existingBorder);
                return new InterVillageSpacingResult(false, actualDistance, existingVillage.getVillageId());
            }
        }
        
        return new InterVillageSpacingResult(true, Integer.MAX_VALUE, null); // No violations
    }
    
    /**
     * Check if this is the first village in the world.
     * 
     * @param world Target world
     * @return true if no villages exist in this world yet
     */
    private boolean isFirstVillage(World world) {
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (village.getOrigin().getWorld().equals(world)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Check if a location is within spawn proximity radius.
     * 
     * @param location Location to check
     * @param spawnProximityRadius Maximum radius from spawn
     * @return true if within radius (or radius is 0 to disable check)
     */
    private boolean isWithinSpawnProximity(Location location, int spawnProximityRadius) {
        if (spawnProximityRadius <= 0) {
            return true; // Spawn proximity disabled
        }
        
        Location spawn = location.getWorld().getSpawnLocation();
        int dx = Math.abs(location.getBlockX() - spawn.getBlockX());
        int dz = Math.abs(location.getBlockZ() - spawn.getBlockZ());
        int distance = dx + dz; // Manhattan distance
        
        return distance <= spawnProximityRadius;
    }
    
    /**
     * Calculate distance from a location to the nearest existing village border.
     * 
     * @param location Location to check
     * @return Distance to nearest village, or Integer.MAX_VALUE if no villages exist
     */
    private int getDistanceToNearestVillage(Location location) {
        VillageMetadataStore.VillageBorder proposedBorder = new VillageMetadataStore.VillageBorder(
                location.getBlockX(), location.getBlockX(),
                location.getBlockZ(), location.getBlockZ()
        );
        
        int minDistance = Integer.MAX_VALUE;
        
        for (VillageMetadataStore.VillageMetadata village : metadataStore.getAllVillages()) {
            if (!village.getOrigin().getWorld().equals(location.getWorld())) {
                continue;
            }
            
            int distance = proposedBorder.getDistanceTo(village.getBorder());
            minDistance = Math.min(minDistance, distance);
        }
        
        return minDistance;
    }
    
    /**
     * Format location for logging.
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
    
    /**
     * Calculate entrance point for a building (T021c).
     * Returns a ground-level location outside the building footprint based on rotation.
     * Entrance is positioned 1-2 blocks away from the building face in the direction the entrance faces.
     * CRITICAL: Accounts for WorldEdit rotation behavior where origin point represents different corners.
     * 
     * @param building Building with origin, dimensions, and rotation
     * @param placement PlacementResult containing actual rotation applied
     * @param world World to check ground level
     * @return Entrance location (ground level, outside footprint)
     */
    private Location calculateEntrancePoint(Building building, 
            com.davisodom.villageoverhaul.worldgen.PlacementResult placement, World world) {
        Location origin = building.getOrigin();
        int[] dims = building.getDimensions();
        int width = dims[0];
        int depth = dims[2];
        int rotationDegrees = placement.getRotationDegrees();
        
        // Calculate effective dimensions after rotation
        int effectiveWidth = placement.getEffectiveWidth(width, depth);
        int effectiveDepth = placement.getEffectiveDepth(width, depth);
        
        // CRITICAL: Calculate actual structure bounds based on WorldEdit rotation behavior
        // WorldEdit rotates clipboard around origin, changing which corner origin represents:
        // 0°: origin = NW corner (minX, minZ), extends +X, +Z
        // 90°: origin = NE corner (minX, maxZ), extends +X, -Z
        // 180°: origin = SE corner (maxX, maxZ), extends -X, -Z
        // 270°: origin = SW corner (maxX, minZ), extends -X, +Z
        
        int originX = origin.getBlockX();
        int originZ = origin.getBlockZ();
        
        int structureMinX, structureMaxX, structureMinZ, structureMaxZ;
        
        switch (rotationDegrees) {
            case 0: // Origin is NW corner
                structureMinX = originX;
                structureMaxX = originX + effectiveWidth - 1;
                structureMinZ = originZ;
                structureMaxZ = originZ + effectiveDepth - 1;
                break;
            case 90: // Origin is NE corner
                structureMinX = originX;
                structureMaxX = originX + effectiveWidth - 1;
                structureMinZ = originZ - effectiveDepth + 1;
                structureMaxZ = originZ;
                break;
            case 180: // Origin is SE corner
                structureMinX = originX - effectiveWidth + 1;
                structureMaxX = originX;
                structureMinZ = originZ - effectiveDepth + 1;
                structureMaxZ = originZ;
                break;
            case 270: // Origin is SW corner
                structureMinX = originX - effectiveWidth + 1;
                structureMaxX = originX;
                structureMinZ = originZ;
                structureMaxZ = originZ + effectiveDepth - 1;
                break;
            default:
                LOGGER.warning(String.format("[PATH] Invalid rotation %d° for building %s, using origin",
                        rotationDegrees, building.getBuildingId()));
                return origin.clone();
        }
        
        // Calculate entrance at center of appropriate face, 2 blocks outside structure bounds
        // Default entrance faces SOUTH (positive Z) at 0° rotation
        // Rotation transforms: 0°=South, 90°=West, 180°=North, 270°=East
        
        int entranceX, entranceZ;
        
        switch (rotationDegrees) {
            case 0: // South face (positive Z) - outside maxZ
                entranceX = (structureMinX + structureMaxX) / 2;
                entranceZ = structureMaxZ + 2;
                break;
            case 90: // West face (negative X) - outside minX
                entranceX = structureMinX - 2;
                entranceZ = (structureMinZ + structureMaxZ) / 2;
                break;
            case 180: // North face (negative Z) - outside minZ
                entranceX = (structureMinX + structureMaxX) / 2;
                entranceZ = structureMinZ - 2;
                break;
            case 270: // East face (positive X) - outside maxX
                entranceX = structureMaxX + 2;
                entranceZ = (structureMinZ + structureMaxZ) / 2;
                break;
            default:
                LOGGER.warning(String.format("[PATH] Invalid rotation %d° for building %s, using origin",
                        rotationDegrees, building.getBuildingId()));
                return origin.clone();
        }
        
        // CRITICAL: Find actual ground level OUTSIDE the building footprint
        // Scan DOWN from building base to find actual terrain
        // Since entrance X/Z is already outside footprint (2 blocks away),
        // we just need to find the first solid block below building base
        int buildingMinY = origin.getBlockY();
        int groundY = buildingMinY - 1; // Start scan from just below building
        
        // Scan down to find first solid block (actual terrain)
        for (int checkY = groundY; checkY >= buildingMinY - 10 && checkY >= world.getMinHeight(); checkY--) {
            Block block = world.getBlockAt(entranceX, checkY, entranceZ);
            if (block.getType().isSolid()) {
                groundY = checkY;
                break;
            }
        }
        
        Location entranceLocation = new Location(world, entranceX, groundY, entranceZ);
        
        return entranceLocation;
    }
    
    /**
     * Find ground level at given X,Z coordinates, starting from hint Y.
     * Scans down to find first solid block suitable for path placement.
     * 
     * DEPRECATED: Use findGroundLevelBelowBuilding for entrance calculations to avoid
     * finding building floor blocks.
     * 
     * @param world World to scan
     * @param x X coordinate
     * @param z Z coordinate
     * @param hintY Starting Y coordinate (typically building origin Y)
     * @return Ground level Y coordinate
     */
    private int findGroundLevel(World world, int x, int z, int hintY) {
        // Scan down from hint Y to find solid ground
        for (int y = hintY; y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            // Found solid ground that's suitable for walking
            if (type.isSolid() && !type.isAir()) {
                // Return Y+1 (on top of the solid block)
                return y + 1;
            }
        }
        
        // Fallback to hint Y if no solid ground found
        return hintY;
    }
    
    /**
     * Find ground level BELOW a building footprint.
     * Scans down from maxY to find natural terrain, skipping all building/terraformed blocks.
     * CRITICAL: Must skip foundation/grading blocks that extend outside footprint.
     * 
     * @param world World to scan
     * @param x X coordinate
     * @param z Z coordinate
     * @param maxY Maximum Y to start scan (should be buildingMinY - 5 to skip foundation)
     * @return Ground level Y coordinate on natural terrain, guaranteed <= buildingMinY
     */
    private int findGroundLevelBelowBuilding(World world, int x, int z, int maxY) {
        int firstNaturalY = -1;
        
        // Scan down from maxY to find natural solid ground (not building blocks)
        for (int y = maxY; y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            // Skip non-solid blocks (air, water, etc.)
            if (!type.isSolid() || type.isAir()) {
                continue;
            }
            
            // Check if this is natural ground (not building materials)
            if (isNaturalGroundForEntrance(type)) {
                firstNaturalY = y + 1; // Y+1 = standing on top of block
                break;
            }
            
            // If we hit a non-natural solid block, continue scanning down
            // (might be building foundation blocks or terraformed grading)
        }
        
        // If we found natural terrain, return it
        if (firstNaturalY > 0) {
            return firstNaturalY;
        }
        
        // Fallback: scan up from bedrock if we somehow missed natural terrain
        for (int y = world.getMinHeight(); y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            if (type.isSolid() && !type.isAir() && isNaturalGroundForEntrance(type)) {
                return y + 1;
            }
        }
        
        // Last resort: return maxY (should rarely happen)
        LOGGER.warning(String.format("[PATH] Could not find natural ground at (%d,%d), using fallback Y=%d",
                x, z, maxY));
        return maxY;
    }
    
    /**
     * Check if a material is natural ground suitable for building entrances.
     * More restrictive than path traversal - excludes building materials.
     * 
     * @param material Material to check
     * @return true if natural ground, false if building material or unsuitable
     */
    private boolean isNaturalGroundForEntrance(Material material) {
        // Only allow natural terrain blocks for entrance ground
        return material == Material.GRASS_BLOCK ||
               material == Material.DIRT ||
               material == Material.COARSE_DIRT ||
               material == Material.PODZOL ||
               material == Material.MYCELIUM ||
               material == Material.SAND ||
               material == Material.RED_SAND ||
               material == Material.GRAVEL ||
               material == Material.CLAY;
        // NOTE: Excludes STONE, COBBLESTONE, PLANKS, etc. (building materials)
    }
    
    /**
     * Get direction name for rotation angle.
     */
    private String getDirectionName(int rotationDegrees) {
        switch (rotationDegrees) {
            case 0: return "South";
            case 90: return "West";
            case 180: return "North";
            case 270: return "East";
            default: return "Unknown";
        }
    }

    private int[] getRotationOrder(long buildingSeed) {
        List<Integer> rotations = new ArrayList<>(Arrays.asList(0, 90, 180, 270));
        Collections.shuffle(rotations, new Random(buildingSeed));
        int[] order = new int[rotations.size()];
        for (int i = 0; i < rotations.size(); i++) {
            order[i] = rotations.get(i);
        }
        return order;
    }
    
    // ==================== Inner Classes ====================
    
    /**
     * Candidate site for structure placement with deterministic sorting keys.
     * T026d2: Used for stable candidate ordering.
     */
    private static class CandidateSite {
        final int x;           // World X coordinate
        final int y;           // World Y coordinate (ground level)
        final int z;           // World Z coordinate
        final int distanceSquared;  // Distance² from origin (for sorting)
        final int dx;          // X offset from origin
        final int dz;          // Z offset from origin
        final int rotationDegrees; // Rotation to apply at this candidate
        final int rotationOrderIndex; // Rotation ordering key for determinism
        
        CandidateSite(int x, int y, int z, int distanceSquared, int dx, int dz,
                      int rotationDegrees, int rotationOrderIndex) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distanceSquared = distanceSquared;
            this.dx = dx;
            this.dz = dz;
            this.rotationDegrees = rotationDegrees;
            this.rotationOrderIndex = rotationOrderIndex;
        }
    }
    
    /**
     * Result of inter-village spacing check with observability metrics.
     */
    private static class InterVillageSpacingResult {
        final boolean acceptable;
        final int actualDistance;
        final UUID violatingVillageId;
        
        InterVillageSpacingResult(boolean acceptable, int actualDistance, UUID violatingVillageId) {
            this.acceptable = acceptable;
            this.actualDistance = actualDistance;
            this.violatingVillageId = violatingVillageId;
        }
    }
    
    private static class GridPosition {
        final int x;
        final int z;
        
        GridPosition(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
    
    /**
     * Footprint of a placed structure (including spacing buffer).
     */
    private static class Footprint {
        final int x;
        final int z;
        final int width;
        final int depth;
        
        Footprint(int x, int z, int width, int depth) {
            this.x = x;
            this.z = z;
            this.width = width;
            this.depth = depth;
        }
    }
    
    /**
     * Tracks rejection reasons for placement attempts.
     * Used to provide detailed debug logging per Constitution v1.4.0, Principle XII.
     */
    private static class PlacementRejectionTracker {
        int terrainRejections = 0;
        int spacingRejections = 0;
        int overlapRejections = 0;
        int chunkNotReady = 0;
        int totalAttempts = 0;

        int siteValidationRejects = 0;
        int terraformRejects = 0;
        
        // Detailed terrain breakdown
        int fluidRejections = 0;
        int steepRejections = 0;
        int blockedRejections = 0;
        
        void recordAttempt() {
            totalAttempts++;
        }
        
        void recordTerrainRejection(TerrainClassifier.ClassificationResult terrainResult) {
            terrainRejections++;
            fluidRejections += terrainResult.fluid;
            steepRejections += terrainResult.steep;
            blockedRejections += terrainResult.blocked;
        }
        
        void recordSpacingRejection() {
            spacingRejections++;
        }
        
        void recordOverlapRejection() {
            overlapRejections++;
        }
        void recordChunkNotReady() { chunkNotReady++; }
        
        @Override
        public String toString() {
            return String.format("attempts=%d, rejected: siteValidation=%d, terraform=%d, spacing=%d, overlap=%d, chunkNotReady=%d, terrainBreakdown=(fluid=%d, steep=%d, blocked=%d)",
                totalAttempts, siteValidationRejects, terraformRejects, spacingRejections, overlapRejections,
                chunkNotReady, fluidRejections, steepRejections, blockedRejections);
        }
        
        /**
         * Calculate average rejected attempts.
         */
        double getAverageRejectedAttempts() {
            int totalRejections = siteValidationRejects + terraformRejects + spacingRejections + overlapRejections;
            return totalAttempts > 0 ? (double) totalRejections / totalAttempts : 0.0;
        }
    }
    
    /**
     * Compute MD5 hash of retry candidate sequence for T026d3 determinism verification.
     * Hash is based on ordered (x, y, z) coordinates of all candidate sites.
     * Identical seeds should produce identical hashes; different seeds should differ.
     * 
     * @param candidates Ordered list of candidate sites (already sorted deterministically)
     * @return 32-character hex hash string, or "ERROR" if hash computation fails
     */
    private String computeRetrySequenceHash(List<CandidateSite> candidates) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder coordString = new StringBuilder();
            
            // Build ordered coordinate string: "x1,y1,z1,rot1;x2,y2,z2,rot2;..."
            for (int i = 0; i < candidates.size(); i++) {
                CandidateSite c = candidates.get(i);
                if (i > 0) {
                    coordString.append(";");
                }
                coordString.append(c.x).append(",").append(c.y).append(",").append(c.z)
                    .append(",").append(c.rotationDegrees);
            }
            
            // Compute MD5 hash
            byte[] hashBytes = md.digest(coordString.toString().getBytes());
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warning("[STRUCT] Failed to compute retry sequence hash: " + e.getMessage());
            return "ERROR";
        }
    }
}
