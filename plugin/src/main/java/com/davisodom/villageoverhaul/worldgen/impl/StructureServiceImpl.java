package com.davisodom.villageoverhaul.worldgen.impl;

import com.davisodom.villageoverhaul.worldgen.PlacementResult;
import com.davisodom.villageoverhaul.worldgen.SiteValidator;
import com.davisodom.villageoverhaul.worldgen.StructureService;
import com.davisodom.villageoverhaul.worldgen.TerraformingUtil;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.EditSession;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Implementation of StructureService with deterministic placement.
 * Supports FAWE-backed fast placement and Paper API fallback.
 */
public class StructureServiceImpl implements StructureService {
    
    private static final Logger LOGGER = Logger.getLogger(StructureServiceImpl.class.getName());
    
    // Maximum number of re-seating attempts before aborting
    private static final int MAX_RESEAT_ATTEMPTS = 3;
    
    // Maximum distance to search for alternative placement
    private static final int MAX_SEARCH_RADIUS = 32;
    
    // Loaded structure templates (structureId -> StructureTemplate)
    private final Map<String, StructureTemplate> loadedStructures = new HashMap<>();
    
    // Site validator for foundation checks
    private final SiteValidator siteValidator = new SiteValidator();
    
    // FAWE availability flag
    private boolean faweAvailable = false;
    
    // Plugin data folder for structure files
    private File structuresDirectory;
    
    public StructureServiceImpl() {
        // Check for FAWE availability on initialization
        checkFAWEAvailability();
        
        // Load placeholder structures for testing
        loadPlaceholderStructures();
    }
    
    /**
     * Constructor with plugin data folder for loading schematics from disk.
     * 
     * @param pluginDataFolder Plugin's data folder (typically plugins/VillageOverhaul)
     */
    public StructureServiceImpl(File pluginDataFolder) {
        this.structuresDirectory = new File(pluginDataFolder, "structures");
        
        // Create structures directory if it doesn't exist
        if (!structuresDirectory.exists()) {
            structuresDirectory.mkdirs();
            LOGGER.info(String.format("[STRUCT] Created structures directory: %s", structuresDirectory.getAbsolutePath()));
        }
        
        // Check for FAWE availability on initialization
        checkFAWEAvailability();
        
        // Try to load structures from directory first, fall back to placeholders
        int loadedCount = loadStructuresFromDirectory();
        
        if (loadedCount == 0) {
            LOGGER.info("[STRUCT] No schematic files found, using procedural Roman structures");
            loadPlaceholderStructures();
        } else {
            LOGGER.info(String.format("[STRUCT] Loaded %d structure(s) from %s", loadedCount, structuresDirectory.getPath()));
        }
    }
    
    /**
     * Load all schematic files from the structures directory.
     * 
     * @return Number of structures loaded successfully
     */
    private int loadStructuresFromDirectory() {
        if (structuresDirectory == null || !structuresDirectory.exists()) {
            return 0;
        }
        
        File[] schematicFiles = structuresDirectory.listFiles((dir, name) -> 
                name.endsWith(".schem") || name.endsWith(".schematic"));
        
        if (schematicFiles == null || schematicFiles.length == 0) {
            return 0;
        }
        
        int successCount = 0;
        for (File schematicFile : schematicFiles) {
            if (loadStructure(schematicFile)) {
                successCount++;
            }
        }
        
        return successCount;
    }
    
    /**
     * Load placeholder structures for testing until actual schematics are available.
     */
    private void loadPlaceholderStructures() {
        // Create Roman-style structure templates
        StructureTemplate smallHouse = new StructureTemplate();
        smallHouse.id = "house_roman_small";
        smallHouse.dimensions = new int[]{9, 7, 9}; // 9x7x9 Roman insula (small apartment)
        smallHouse.entranceOffset = BlockVector3.at(4, 1, 0);
        smallHouse.entranceFacing = BlockVector3.at(0, 0, -1);
        loadedStructures.put("house_roman_small", smallHouse);
        loadedStructures.put("house_small", smallHouse); // Fallback alias
        
        StructureTemplate mediumHouse = new StructureTemplate();
        mediumHouse.id = "house_roman_medium";
        mediumHouse.dimensions = new int[]{13, 8, 13}; // 13x8x13 Roman domus (townhouse)
        mediumHouse.entranceOffset = BlockVector3.at(6, 1, 0);
        mediumHouse.entranceFacing = BlockVector3.at(0, 0, -1);
        loadedStructures.put("house_roman_medium", mediumHouse);
        loadedStructures.put("house_medium", mediumHouse); // Fallback alias
        
        StructureTemplate villa = new StructureTemplate();
        villa.id = "house_roman_villa";
        villa.dimensions = new int[]{17, 9, 17}; // 17x9x17 Roman villa
        villa.entranceOffset = BlockVector3.at(8, 1, 0);
        villa.entranceFacing = BlockVector3.at(0, 0, -1);
        loadedStructures.put("house_roman_villa", villa);
        
        StructureTemplate workshop = new StructureTemplate();
        workshop.id = "workshop_roman_forge";
        workshop.dimensions = new int[]{11, 8, 11}; // 11x8x11 Blacksmith forge
        workshop.entranceOffset = BlockVector3.at(5, 1, 0);
        workshop.entranceFacing = BlockVector3.at(0, 0, -1);
        loadedStructures.put("workshop_roman_forge", workshop);
        loadedStructures.put("workshop", workshop); // Fallback alias
        
        StructureTemplate market = new StructureTemplate();
        market.id = "market_roman_stall";
        market.dimensions = new int[]{7, 6, 7}; // 7x6x7 Market stall
        market.entranceOffset = BlockVector3.at(3, 1, 0);
        market.entranceFacing = BlockVector3.at(0, 0, -1);
        loadedStructures.put("market_roman_stall", market);
        
        StructureTemplate bathhouse = new StructureTemplate();
        bathhouse.id = "building_roman_bathhouse";
        bathhouse.dimensions = new int[]{15, 7, 15}; // 15x7x15 Public bathhouse
        bathhouse.entranceOffset = BlockVector3.at(7, 1, 0);
        bathhouse.entranceFacing = BlockVector3.at(0, 0, -1);
        loadedStructures.put("building_roman_bathhouse", bathhouse);
        
        LOGGER.info(String.format("[STRUCT] Loaded %d Roman structure templates", loadedStructures.size()));
    }
    
    @Override
    public boolean loadStructure(File schematicFile) {
        if (!schematicFile.exists()) {
            LOGGER.warning(String.format("[STRUCT] Structure file not found: %s", schematicFile.getPath()));
            return false;
        }
        
        String structureId = schematicFile.getName().replace(".schem", "").replace(".schematic", "");
        
        try {
            // Try loading WorldEdit schematic
            ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
            
            if (format == null) {
                LOGGER.warning(String.format("[STRUCT] Unknown schematic format: %s", schematicFile.getName()));
                return false;
            }
            
            try (FileInputStream fis = new FileInputStream(schematicFile);
                 ClipboardReader reader = format.getReader(fis)) {
                Clipboard clipboard = reader.read();
                
                // Normalize clipboard origin to minimum corner for predictable paste behavior
                clipboard = normalizeClipboardOrigin(clipboard);
                
                // Create template with actual dimensions from schematic
                StructureTemplate template = new StructureTemplate();
                template.id = structureId;
                template.clipboard = clipboard;
                
                BlockVector3 dimensions = clipboard.getDimensions();
                template.dimensions = new int[]{dimensions.getX(), dimensions.getY(), dimensions.getZ()};
                
                // Default entrance: center of Z-min face
                template.entranceOffset = BlockVector3.at(dimensions.getX() / 2, 1, 0);
                template.entranceFacing = BlockVector3.at(0, 0, -1);
                
                loadedStructures.put(structureId, template);
                
                LOGGER.info(String.format("[STRUCT] Loaded WorldEdit schematic '%s' (%dx%dx%d) from %s",
                        structureId, 
                        template.dimensions[0], 
                        template.dimensions[1], 
                        template.dimensions[2],
                        schematicFile.getName()));
                
                return true;
            }
            
        } catch (IOException e) {
            LOGGER.warning(String.format("[STRUCT] Failed to load schematic '%s': %s", 
                    schematicFile.getName(), e.getMessage()));
            return false;
        }
    }
    
    @Override
    public boolean placeStructure(String structureId, World world, Location origin, long seed) {
        Optional<PlacementResult> result = placeStructureAndGetResult(structureId, world, origin, seed);
        return result.isPresent();
    }
    
    @Override
    public Optional<PlacementResult> placeStructureAndGetResult(String structureId, World world, Location origin, long seed) {
        StructureTemplate template = loadedStructures.get(structureId);
        
        if (template == null) {
            LOGGER.warning(String.format("[STRUCT] Structure '%s' not loaded", structureId));
            return Optional.empty();
        }
        
        LOGGER.info(String.format("[STRUCT] Begin placement: structureId=%s, origin=%s, seed=%d, world=%s",
                structureId, formatLocation(origin), seed, world.getName()));
        
        // Calculate rotation BEFORE placement (deterministic from seed)
        Random random = new Random(seed);
        int rotationDegrees = random.nextInt(4) * 90; // 0, 90, 180, or 270
        
        // Attempt placement with re-seating logic and get actual location
        // For legacy method (no receipt), pass null for existingMasks
        Optional<Location> actualLocation = attemptPlacementWithReseatingAndGetLocation(template, world, origin, seed, null);
        
        if (actualLocation.isPresent()) {
            PlacementResult result = new PlacementResult(actualLocation.get(), rotationDegrees);
            LOGGER.info(String.format("[STRUCT] Seat successful: structure='%s', origin=%s, rotation=%d°, seed=%d",
                    structureId, formatLocation(actualLocation.get()), rotationDegrees, seed));
            return Optional.of(result);
        } else {
            LOGGER.warning(String.format("[STRUCT] Abort: structure='%s', seed=%d, attempts=%d, reason=no_valid_site",
                    structureId, seed, MAX_RESEAT_ATTEMPTS));
            return Optional.empty();
        }
    }
    
    @Override
    public Optional<com.davisodom.villageoverhaul.model.PlacementReceipt> placeStructureAndGetReceipt(
            String structureId, World world, Location origin, long seed, UUID villageId,
            java.util.List<com.davisodom.villageoverhaul.model.VolumeMask> existingMasks) {
        StructureTemplate template = loadedStructures.get(structureId);
        
        if (template == null) {
            LOGGER.warning(String.format("[STRUCT] Structure '%s' not loaded", structureId));
            return Optional.empty();
        }
        
        LOGGER.info(String.format("[STRUCT] Begin placement (with receipt): structureId=%s, origin=%s, seed=%d, world=%s",
                structureId, formatLocation(origin), seed, world.getName()));
        
        // Calculate rotation BEFORE placement (deterministic from seed)
        Random random = new Random(seed);
        int rotationDegrees = random.nextInt(4) * 90; // 0, 90, 180, or 270
        
        // R011b: Attempt placement with re-seating logic and pass existingMasks for collision detection
        Optional<Location> actualLocation = attemptPlacementWithReseatingAndGetLocation(template, world, origin, seed, existingMasks);
        
        if (!actualLocation.isPresent()) {
            LOGGER.warning(String.format("[STRUCT] Abort: structure='%s', seed=%d, attempts=%d, reason=no_valid_site",
                    structureId, seed, MAX_RESEAT_ATTEMPTS));
            return Optional.empty();
        }
        
        Location placedOrigin = actualLocation.get();
        
        // Compute exact AABB accounting for rotation and clipboard origin
        int baseWidth = template.dimensions[0];
        int baseDepth = template.dimensions[2];
        int height = template.dimensions[1];
        
        int[] bounds = computeAABB(placedOrigin, template.clipboard, baseWidth, baseDepth, height, rotationDegrees);
        
        // Sample foundation corners as proof of paste alignment
        com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[] corners = 
            sampleFoundationCorners(world, bounds);
            
        // Calculate entrance location
        // R003: Transform anchor via T and snap to adjacent walkable ground outside AABB+buffer
        // Pass bounds so calculateEntranceLocation can use SurfaceSolver to find natural ground
        Location entranceLoc = calculateEntranceLocation(world, placedOrigin, template, rotationDegrees, bounds, villageId);
        
        // Calculate effective dimensions after rotation
        int effectiveWidth, effectiveDepth;
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            effectiveWidth = baseDepth;
            effectiveDepth = baseWidth;
        } else {
            effectiveWidth = baseWidth;
            effectiveDepth = baseDepth;
        }
        
        // Build PlacementReceipt
        com.davisodom.villageoverhaul.model.PlacementReceipt receipt = 
            new com.davisodom.villageoverhaul.model.PlacementReceipt.Builder()
                .structureId(structureId)
                .villageId(villageId)
                .world(world)
                .entrance(entranceLoc.getBlockX(), entranceLoc.getBlockY(), entranceLoc.getBlockZ())
                .origin(placedOrigin.getBlockX(), placedOrigin.getBlockY(), placedOrigin.getBlockZ())
                .rotation(rotationDegrees)
                .bounds(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])
                .dimensions(effectiveWidth, height, effectiveDepth)
                .foundationCorners(corners)
                .build();
        
        // Emit [STRUCT][RECEIPT] log
        LOGGER.info(String.format("[STRUCT][RECEIPT] %s", receipt.getReceiptSummary()));
        
        // Verify corner samples
        boolean cornersValid = receipt.verifyFoundationCorners();
        if (!cornersValid) {
            LOGGER.warning(String.format("[STRUCT][RECEIPT] WARNING: Some foundation corners are not solid blocks: %s",
                    java.util.Arrays.toString(corners)));
        }
        
        LOGGER.info(String.format("[STRUCT] Seat successful: structure='%s', origin=%s, rotation=%d°, seed=%d",
                structureId, formatLocation(placedOrigin), rotationDegrees, seed));
        
        return Optional.of(receipt);
    }
    
    @Override
    public Optional<Location> placeStructureAndGetLocation(String structureId, World world, Location origin, long seed) {
        Optional<PlacementResult> result = placeStructureAndGetResult(structureId, world, origin, seed);
        return result.map(PlacementResult::getActualLocation);
    }
    
    /**
     * Attempt placement with re-seating logic, returning the actual placed location.
     * Tries initial location, then searches nearby if validation fails.
     * R011b: Enhanced with existingMasks for pre-placement collision detection.
     * @return Optional containing the actual placed location, empty if all attempts failed
     */
    private Optional<Location> attemptPlacementWithReseatingAndGetLocation(
            StructureTemplate template, World world, Location origin, long seed,
            java.util.List<com.davisodom.villageoverhaul.model.VolumeMask> existingMasks) {
        Random random = new Random(seed);
        
        for (int attempt = 0; attempt < MAX_RESEAT_ATTEMPTS; attempt++) {
            Location currentOrigin = attempt == 0 ? origin : findAlternativeLocation(world, origin, random, attempt);
            
            LOGGER.info(String.format("[STRUCT] Seat attempt %d/%d: structure='%s', location=%s, seed=%d",
                    attempt + 1, MAX_RESEAT_ATTEMPTS, template.id, formatLocation(currentOrigin), seed));
            
            // T020a: Validate foundation for fluids BEFORE attempting terraforming/placement
            // This prevents placing buildings on water/lava (Constitution v1.5.0 water avoidance)
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Validating site for '%s' at %s", 
                    template.id, formatLocation(currentOrigin)));
            SiteValidator.ValidationResult siteValidation = siteValidator.validateSite(
                    world,
                    currentOrigin,
                    template.dimensions[0],
                    template.dimensions[2],
                    template.dimensions[1]
            );
            
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Validation result - passed=%b, foundationOk=%b, interiorAirOk=%b, entranceOk=%b",
                    siteValidation.passed, siteValidation.foundationOk, 
                    siteValidation.interiorAirOk, siteValidation.entranceOk));
            
            // Hard reject if foundation has fluids or fails validation
            if (!siteValidation.passed) {
                String rejectionReason = buildRejectionReason(siteValidation);
                LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Seat rejected at attempt %d: %s",
                        attempt + 1, rejectionReason));
                continue; // Try next re-seat attempt
            }
            
            LOGGER.info("[STRUCT] DIAGNOSTIC: Validation passed, computing AABB for terraforming");
            
            // Compute exact AABB BEFORE terraforming to ensure alignment with PlacementReceipt
            Random rotRandom = new Random(seed);
            int rotationDegrees = rotRandom.nextInt(4) * 90; // 0, 90, 180, or 270
            
            int baseWidth = template.dimensions[0];
            int baseDepth = template.dimensions[2];
            int height = template.dimensions[1];
            
            int[] bounds = computeAABB(currentOrigin, template.clipboard, baseWidth, baseDepth, height, rotationDegrees);
            
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Computed AABB for terraforming: bounds=(%d..%d, %d..%d, %d..%d) rot=%d°",
                    bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], rotationDegrees));
            
            // R011b: Check collision with existing masks BEFORE terraforming
            if (existingMasks != null && !existingMasks.isEmpty()) {
                int minBuildingSpacing = 10; // Default spacing from config
                boolean hasCollision = checkAABBCollision(bounds, existingMasks, minBuildingSpacing);
                if (hasCollision) {
                    LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Seat rejected at attempt %d: collision with existing structure (rotation-aware check)",
                            attempt + 1));
                    continue; // Try next re-seat attempt WITHOUT wasting placement
                }
            }
            
            // Prepare site with terraforming using exact AABB BEFORE placement
            boolean terraformed = TerraformingUtil.prepareSiteWithBounds(world, bounds);
            
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Terraforming result=%b", terraformed));
            
            // CRITICAL: If terraforming fails (fluid detected), abort this placement attempt
            if (!terraformed) {
                LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Seat rejected at attempt %d: terraforming failed (likely fluid detected during site prep)",
                        attempt + 1));
                continue; // Try next re-seat attempt
            }
            
            // Site prepared - perform actual placement
            // After successful terraforming, placement MUST succeed (already committed to this site)
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Calling performActualPlacement for '%s' at %s",
                    template.id, formatLocation(currentOrigin)));
            boolean placed = performActualPlacement(template, world, currentOrigin, seed);
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: performActualPlacement returned %b for '%s'",
                    placed, template.id));
            
            if (!placed) {
                // This should NEVER happen after successful validation and terraforming
                // Log as ERROR since we've already modified the world
                LOGGER.severe(String.format("[STRUCT] CRITICAL: Placement failed after terraforming for '%s' at %s - site orphaned!",
                        template.id, formatLocation(currentOrigin)));
                // Continue to next attempt, but world is already modified (unavoidable)
                continue;
            }
            
            if (placed) {
                if (attempt > 0) {
                    LOGGER.info(String.format("[STRUCT] Re-seat successful: structure='%s', final_location=%s, attempts=%d, seed=%d",
                            template.id, formatLocation(currentOrigin), attempt + 1, seed));
                }
                return Optional.of(currentOrigin); // Return the actual placed location
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Find an alternative location for re-seating.
     * Uses deterministic search pattern based on seed and attempt number.
     */
    private Location findAlternativeLocation(World world, Location original, Random random, int attempt) {
        // Spiral search pattern with increasing radius
        int searchRadius = Math.min(attempt * 8, MAX_SEARCH_RADIUS);
        
        // Deterministic offset based on seed
        int offsetX = random.nextInt(searchRadius * 2) - searchRadius;
        int offsetZ = random.nextInt(searchRadius * 2) - searchRadius;
        
        int newX = original.getBlockX() + offsetX;
        int newZ = original.getBlockZ() + offsetZ;
        int newY = world.getHighestBlockYAt(newX, newZ);
        
        return new Location(world, newX, newY, newZ);
    }
    
    /**
     * Perform the actual structure placement.
     * Uses FAWE/WorldEdit if available and schematic is loaded, otherwise falls back to Paper API.
     */
    private boolean performActualPlacement(StructureTemplate template, World world, Location origin, long seed) {
        LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: performActualPlacement - clipboard=%s, faweAvailable=%b",
                (template.clipboard != null ? "present" : "null"), faweAvailable));
        
        // If template has a schematic loaded, use WorldEdit/FAWE placement
        if (template.clipboard != null && faweAvailable) {
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Routing to placeWorldEdit for '%s'", template.id));
            return placeWorldEdit(template, world, origin, seed);
        } else if (faweAvailable) {
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Routing to placeFAWE for '%s'", template.id));
            return placeFAWE(template, world, origin, seed);
        } else {
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Routing to placePaperAPI for '%s'", template.id));
            return placePaperAPI(template, world, origin, seed);
        }
    }
    
    /**
     * Place structure using WorldEdit/FAWE with actual schematic data.
     */
    private boolean placeWorldEdit(StructureTemplate template, World world, Location origin, long seed) {
        LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: placeWorldEdit ENTRY for '%s' at %s", 
                template.id, formatLocation(origin)));
        
        try {
            LOGGER.info("[STRUCT] DIAGNOSTIC: Adapting world to WorldEdit");
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            
            // Use the validated and terraformed origin Y coordinate directly
            // (Do NOT recalculate ground level - that would ignore our site preparation)
            BlockVector3 weOrigin = BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
            LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: weOrigin=%s", weOrigin));
            
            LOGGER.info("[STRUCT] DIAGNOSTIC: Creating EditSession");
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                LOGGER.info("[STRUCT] DIAGNOSTIC: Creating ClipboardHolder");
                ClipboardHolder holder = new ClipboardHolder(template.clipboard);
                
                // Apply deterministic rotation based on seed
                Random random = new Random(seed);
                int rotationDegrees = random.nextInt(4) * 90; // 0, 90, 180, or 270
                LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: Rotation=%d degrees", rotationDegrees));
                if (rotationDegrees > 0) {
                    AffineTransform transform = new AffineTransform();
                    holder.setTransform(holder.getTransform().combine(transform.rotateY(rotationDegrees)));
                }
                
                LOGGER.info("[STRUCT] DIAGNOSTIC: Building paste operation");
                // Paste structure
                Operation operation = holder.createPaste(editSession)
                    .to(weOrigin)
                    .ignoreAirBlocks(false)
                    .build();
                
                LOGGER.info("[STRUCT] DIAGNOSTIC: Calling Operations.complete()");
                Operations.complete(operation);
                LOGGER.info("[STRUCT] DIAGNOSTIC: Operations.complete() finished successfully");
                
                LOGGER.info(String.format("[STRUCT] WorldEdit placement successful for '%s'", template.id));
                
                // Foundation backfilling disabled - let structures sit naturally on terrain
                // Previous aggressive backfilling created visible dirt walls and terracing
                /*
                int backfilled = TerraformingUtil.backfillFoundation(
                    world,
                    new Location(world, weOrigin.getX(), weOrigin.getY(), weOrigin.getZ()),
                    template.dimensions[0],
                    template.dimensions[2],
                    Material.DIRT
                );
                
                LOGGER.fine(String.format("[STRUCT] Foundation backfilled for '%s': %d blocks", template.id, backfilled));
                */
                
                LOGGER.info(String.format("[STRUCT] DIAGNOSTIC: About to return TRUE for '%s'", template.id));
                return true;
            }
            
        } catch (Exception e) {
            LOGGER.warning(String.format("[STRUCT] DIAGNOSTIC: Exception caught in placeWorldEdit: %s", 
                    e.getClass().getName()));
            LOGGER.warning(String.format("[STRUCT] WorldEdit placement failed for '%s': %s", 
                    template.id, e.getMessage()));
            e.printStackTrace();
            return placePaperAPI(template, world, origin, seed);
        }
    }
    
    /**
     * Place structure using FAWE (fast async world edit).
     */
    private boolean placeFAWE(StructureTemplate template, World world, Location origin, long seed) {
        LOGGER.fine(String.format("[STRUCT] Using FAWE placement for '%s'", template.id));
        
        try {
            // FAWE integration pattern (requires FAWE dependency):
            // 1. Get WorldEdit World adapter for Bukkit world
            // 2. Create EditSession with block change limit
            // 3. Load schematic from template data
            // 4. Place schematic at origin location with rotation/mirror options
            // 5. Apply deterministic randomization based on seed
            // 6. Flush changes asynchronously
            
            /* Example FAWE code (commented out until FAWE is added as dependency):
            
            WorldEditPlugin worldEdit = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
            if (worldEdit == null) {
                LOGGER.warning("[STRUCT] FAWE unavailable, falling back to Paper API");
                return placePaperAPI(template, world, origin, seed);
            }
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            BlockVector3 weOrigin = BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
            
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                editSession.setFastMode(true);
                
                // Load clipboard from schematic file
                ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
                
                if (format != null) {
                    try (FileInputStream fis = new FileInputStream(schematicFile);
                         ClipboardReader reader = format.getReader(fis)) {
                        Clipboard clipboard = reader.read();
                        
                        // Configure paste operation
                        ClipboardHolder holder = new ClipboardHolder(clipboard);
                        
                        // Apply deterministic rotation based on seed
                        Random random = new Random(seed);
                        int rotationDegrees = random.nextInt(4) * 90; // 0, 90, 180, or 270
                        if (rotationDegrees > 0) {
                            holder.setTransform(holder.getTransform().combine(
                                new AffineTransform().rotateY(rotationDegrees)
                            ));
                        }
                        
                        // Paste structure
                        Operation operation = holder.createPaste(editSession)
                            .to(weOrigin)
                            .ignoreAirBlocks(false)
                            .build();
                        
                        Operations.complete(operation);
                        editSession.flushQueue();
                        
                        LOGGER.fine(String.format("[STRUCT] FAWE placement successful for '%s'", template.id));
                        return true;
                    }
                }   }
                }
            }
            */
            
            // Until FAWE dependency is added, fall back to Paper API
            LOGGER.fine("[STRUCT] FAWE implementation pending, using Paper API fallback");
            return placePaperAPI(template, world, origin, seed);
            
        } catch (Exception e) {
            LOGGER.warning(String.format("[STRUCT] FAWE placement failed for '%s': %s", 
                    template.id, e.getMessage()));
            return placePaperAPI(template, world, origin, seed);
        }
    }
    
    /**
     * Place structure using Paper API block-by-block.
     * Generates Roman-style architecture based on template ID.
     */
    private boolean placePaperAPI(StructureTemplate template, World world, Location origin, long seed) {
        LOGGER.fine(String.format("[STRUCT] Using Paper API placement for '%s'", template.id));
        
        // Create a Roman-style structure based on template dimensions
        int width = template.dimensions[0];
        int height = template.dimensions[1];
        int depth = template.dimensions[2];
        
        Random random = new Random(seed);
        
        // Find actual ground level - search downward from origin to find solid ground
        int groundY = findGroundLevel(world, origin);
        
        LOGGER.fine(String.format("[STRUCT] Ground level found at Y=%d (origin was Y=%d)", 
                groundY, origin.getBlockY()));
        
        // Build structure based on type
        try {
            if (template.id.contains("house")) {
                buildRomanHouse(world, origin, groundY, width, height, depth, random, template.id);
            } else if (template.id.contains("workshop") || template.id.contains("forge")) {
                buildRomanWorkshop(world, origin, groundY, width, height, depth, random);
            } else if (template.id.contains("market")) {
                buildRomanMarket(world, origin, groundY, width, height, depth, random);
            } else if (template.id.contains("bathhouse")) {
                buildRomanBathhouse(world, origin, groundY, width, height, depth, random);
            } else {
                // Fallback to generic building
                buildRomanHouse(world, origin, groundY, width, height, depth, random, "generic");
            }
            
            LOGGER.fine(String.format("[STRUCT] Paper API placement complete for '%s'", template.id));
            return true;
            
        } catch (Exception e) {
            LOGGER.warning(String.format("[STRUCT] Paper API placement failed for '%s': %s", 
                    template.id, e.getMessage()));
            return false;
        }
    }
    
    /**
     * Build a Roman-style house (insula, domus, or villa).
     */
    private void buildRomanHouse(World world, Location origin, int groundY, int width, int height, int depth, Random random, String houseType) {
        int baseX = origin.getBlockX();
        int baseZ = origin.getBlockZ();
        
        // Determine materials based on house type
        Material wallMaterial = Material.STONE_BRICKS;
        Material floorMaterial = Material.SMOOTH_STONE;
        Material roofMaterial = Material.TERRACOTTA;
        Material accentMaterial = Material.CHISELED_STONE_BRICKS;
        
        if (houseType.contains("villa")) {
            wallMaterial = Material.QUARTZ_BLOCK;
            floorMaterial = Material.POLISHED_ANDESITE;
            accentMaterial = Material.CHISELED_QUARTZ_BLOCK;
        } else if (houseType.contains("medium") || houseType.contains("domus")) {
            wallMaterial = Material.STONE_BRICKS;
            floorMaterial = Material.SMOOTH_STONE;
            accentMaterial = Material.CHISELED_STONE_BRICKS;
        }
        
        // Foundation/Floor with mosaic pattern
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                Material floor = ((x + z) % 4 == 0) ? Material.POLISHED_ANDESITE : floorMaterial;
                world.getBlockAt(baseX + x, groundY, baseZ + z).setType(floor);
            }
        }
        
        // Walls with columns
        for (int y = 1; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isPerimeter = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                    boolean isColumn = isPerimeter && ((x % 3 == 0 && (z == 0 || z == depth - 1)) || 
                                                       (z % 3 == 0 && (x == 0 || x == width - 1)));
                    
                    if (isPerimeter) {
                        // Main entrance on south side (z=0)
                        boolean isDoorway = z == 0 && (x == width / 2 || x == width / 2 - 1) && y <= 2;
                        
                        // Windows on upper floor
                        boolean isWindow = y >= 3 && !isDoorway && random.nextInt(5) == 0;
                        
                        if (isColumn) {
                            world.getBlockAt(baseX + x, groundY + y, baseZ + z).setType(accentMaterial);
                        } else if (!isDoorway && !isWindow) {
                            world.getBlockAt(baseX + x, groundY + y, baseZ + z).setType(wallMaterial);
                        } else if (isWindow) {
                            world.getBlockAt(baseX + x, groundY + y, baseZ + z).setType(Material.GLASS_PANE);
                        }
                    }
                }
            }
        }
        
        // Flat Roman roof with terracotta tiles
        int roofY = groundY + height - 1;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                world.getBlockAt(baseX + x, roofY, baseZ + z).setType(roofMaterial);
                
                // Decorative edge
                boolean isEdge = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                if (isEdge) {
                    world.getBlockAt(baseX + x, roofY + 1, baseZ + z).setType(Material.SMOOTH_STONE_SLAB);
                }
            }
        }
        
        // Interior courtyard for larger houses
        if (width >= 13 && depth >= 13) {
            int courtyardStartX = width / 4;
            int courtyardEndX = 3 * width / 4;
            int courtyardStartZ = depth / 4;
            int courtyardEndZ = 3 * depth / 4;
            
            for (int x = courtyardStartX; x < courtyardEndX; x++) {
                for (int z = courtyardStartZ; z < courtyardEndZ; z++) {
                    // Open to sky
                    for (int y = 1; y < height; y++) {
                        world.getBlockAt(baseX + x, groundY + y, baseZ + z).setType(Material.AIR);
                    }
                    // Water feature in center
                    if (x == width / 2 && z == depth / 2) {
                        world.getBlockAt(baseX + x, groundY, baseZ + z).setType(Material.WATER);
                    }
                }
            }
        }
        
        // Interior lighting with wall-mounted torches
        for (int x = 2; x < width - 2; x += 3) {
            world.getBlockAt(baseX + x, groundY + 2, baseZ + 1).setType(Material.WALL_TORCH);
            if (depth > 5) {
                world.getBlockAt(baseX + x, groundY + 2, baseZ + depth - 2).setType(Material.WALL_TORCH);
            }
        }
    }
    
    /**
     * Build a Roman workshop/forge.
     */
    private void buildRomanWorkshop(World world, Location origin, int groundY, int width, int height, int depth, Random random) {
        int baseX = origin.getBlockX();
        int baseZ = origin.getBlockZ();
        
        // Stone and brick construction
        Material wallMaterial = Material.STONE_BRICKS;
        Material floorMaterial = Material.COBBLESTONE;
        
        // Floor
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                world.getBlockAt(baseX + x, groundY, baseZ + z).setType(floorMaterial);
            }
        }
        
        // Walls with large opening for forge heat
        for (int y = 1; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isWall = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                    
                    if (isWall) {
                        // Large entrance
                        boolean isDoorway = z == 0 && x >= width / 3 && x <= 2 * width / 3 && y <= 3;
                        
                        if (!isDoorway) {
                            world.getBlockAt(baseX + x, groundY + y, baseZ + z).setType(wallMaterial);
                        }
                    }
                }
            }
        }
        
        // Forge in center
        int forgeX = baseX + width / 2;
        int forgeZ = baseZ + depth / 2;
        world.getBlockAt(forgeX, groundY, forgeZ).setType(Material.FURNACE);
        world.getBlockAt(forgeX - 1, groundY, forgeZ).setType(Material.ANVIL);
        world.getBlockAt(forgeX + 1, groundY, forgeZ).setType(Material.CRAFTING_TABLE);
        
        // Chimney
        for (int y = 1; y < height + 3; y++) {
            world.getBlockAt(forgeX, groundY + y, forgeZ).setType(Material.STONE_BRICKS);
        }
        
        // Flat roof
        int roofY = groundY + height - 1;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!(x == width / 2 && z == depth / 2)) { // Leave chimney hole
                    world.getBlockAt(baseX + x, roofY, baseZ + z).setType(Material.STONE_BRICK_SLAB);
                }
            }
        }
        
        // Work lights
        world.getBlockAt(baseX + 2, groundY + 3, baseZ + 2).setType(Material.LANTERN);
        world.getBlockAt(baseX + width - 3, groundY + 3, baseZ + 2).setType(Material.LANTERN);
    }
    
    /**
     * Build a Roman market stall.
     */
    private void buildRomanMarket(World world, Location origin, int groundY, int width, int height, int depth, Random random) {
        int baseX = origin.getBlockX();
        int baseZ = origin.getBlockZ();
        
        // Stone platform
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                world.getBlockAt(baseX + x, groundY, baseZ + z).setType(Material.SMOOTH_STONE);
            }
        }
        
        // Corner posts
        Material postMaterial = Material.OAK_FENCE;
        for (int y = 1; y < height; y++) {
            world.getBlockAt(baseX, groundY + y, baseZ).setType(postMaterial);
            world.getBlockAt(baseX + width - 1, groundY + y, baseZ).setType(postMaterial);
            world.getBlockAt(baseX, groundY + y, baseZ + depth - 1).setType(postMaterial);
            world.getBlockAt(baseX + width - 1, groundY + y, baseZ + depth - 1).setType(postMaterial);
        }
        
        // Canvas awning
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                Material awning = ((x + z) % 2 == 0) ? Material.WHITE_WOOL : Material.RED_WOOL;
                world.getBlockAt(baseX + x, groundY + height - 1, baseZ + z).setType(awning);
            }
        }
        
        // Market goods
        world.getBlockAt(baseX + 2, groundY + 1, baseZ + 2).setType(Material.BARREL);
        world.getBlockAt(baseX + width - 3, groundY + 1, baseZ + 2).setType(Material.CHEST);
        world.getBlockAt(baseX + 2, groundY + 1, baseZ + depth - 3).setType(Material.COMPOSTER);
    }
    
    /**
     * Build a Roman bathhouse.
     */
    private void buildRomanBathhouse(World world, Location origin, int groundY, int width, int height, int depth, Random random) {
        int baseX = origin.getBlockX();
        int baseZ = origin.getBlockZ();
        
        // Marble-like floor
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                Material floor = ((x + z) % 3 == 0) ? Material.QUARTZ_BLOCK : Material.SMOOTH_QUARTZ;
                world.getBlockAt(baseX + x, groundY, baseZ + z).setType(floor);
            }
        }
        
        // Walls with arched windows
        for (int y = 1; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isWall = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                    
                    if (isWall) {
                        boolean isDoorway = z == 0 && x >= width / 2 - 1 && x <= width / 2 + 1 && y <= 3;
                        boolean isWindow = y == 3 && (x % 4 == 0 || z % 4 == 0) && !isDoorway;
                        
                        if (!isDoorway && !isWindow) {
                            world.getBlockAt(baseX + x, groundY + y, baseZ + z).setType(Material.SMOOTH_QUARTZ);
                        } else if (isWindow) {
                            world.getBlockAt(baseX + x, groundY + y, baseZ + z).setType(Material.GLASS_PANE);
                        }
                    }
                }
            }
        }
        
        // Central bath pool
        int poolStartX = width / 3;
        int poolEndX = 2 * width / 3;
        int poolStartZ = depth / 3;
        int poolEndZ = 2 * depth / 3;
        
        for (int x = poolStartX; x < poolEndX; x++) {
            for (int z = poolStartZ; z < poolEndZ; z++) {
                // Pool basin
                world.getBlockAt(baseX + x, groundY - 1, baseZ + z).setType(Material.SMOOTH_QUARTZ);
                world.getBlockAt(baseX + x, groundY, baseZ + z).setType(Material.WATER);
            }
        }
        
        // Domed roof
        int roofY = groundY + height - 1;
        int centerX = width / 2;
        int centerZ = depth / 2;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int distFromCenter = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                if (distFromCenter <= Math.min(centerX, centerZ)) {
                    int domeHeight = Math.min(centerX, centerZ) - distFromCenter;
                    for (int y = 0; y <= domeHeight; y++) {
                        world.getBlockAt(baseX + x, roofY + y, baseZ + z).setType(Material.SMOOTH_QUARTZ);
                    }
                }
            }
        }
        
        // Braziers for warmth
        world.getBlockAt(baseX + 2, groundY + 1, baseZ + 2).setType(Material.CAMPFIRE);
        world.getBlockAt(baseX + width - 3, groundY + 1, baseZ + 2).setType(Material.CAMPFIRE);
        world.getBlockAt(baseX + 2, groundY + 1, baseZ + depth - 3).setType(Material.CAMPFIRE);
        world.getBlockAt(baseX + width - 3, groundY + 1, baseZ + depth - 3).setType(Material.CAMPFIRE);
    }
    
    @Override
    public Optional<int[]> getStructureDimensions(String structureId) {
        StructureTemplate template = loadedStructures.get(structureId);
        
        if (template == null) {
            return Optional.empty();
        }
        
        return Optional.of(template.dimensions);
    }
    
    @Override
    public boolean validateFoundation(World world, Location origin, int[] dimensions, double solidityPercent) {
        SiteValidator.ValidationResult result = siteValidator.validateSite(
                world,
                origin,
                dimensions[0],
                dimensions[2],
                dimensions[1]
        );
        
        return result.foundationOk;
    }
    
    @Override
    public List<Block> performMinorTerraforming(World world, Location origin, int[] dimensions, int maxRadius) {
        List<Block> modifiedBlocks = new ArrayList<>();
        
        boolean success = TerraformingUtil.prepareSite(
                world,
                origin,
                dimensions[0],
                dimensions[2],
                dimensions[1]
        );
        
        if (!success) {
            LOGGER.warning(String.format("[STRUCT] Terraforming failed at %s", origin));
        }
        
        return modifiedBlocks;
    }
    
    @Override
    public int clearVegetation(World world, Location origin, int[] dimensions) {
        return TerraformingUtil.trimVegetation(
                world,
                origin,
                dimensions[0],
                dimensions[2],
                dimensions[1]
        );
    }
    
    @Override
    public boolean isFAWEAvailable() {
        return faweAvailable;
    }
    
    @Override
    public int reloadStructures() {
        int previousCount = loadedStructures.size();
        loadedStructures.clear();
        
        // TODO: Reload structures from configured directory
        
        LOGGER.info(String.format("[STRUCT] Reloaded %d structures", previousCount));
        return previousCount;
    }
    
    /**
     * Check if FAWE is available in the server.
     */
    private void checkFAWEAvailability() {
        try {
            Class.forName("com.sk89q.worldedit.bukkit.WorldEditPlugin");
            faweAvailable = true;
            LOGGER.info("[STRUCT] FAWE detected and available");
        } catch (ClassNotFoundException e) {
            faweAvailable = false;
            LOGGER.info("[STRUCT] FAWE not available, using Paper API fallback");
        }
    }
    
    /**
     * Normalize clipboard origin to its minimum corner.
     * This standardizes paste behavior: origin becomes the structure's SW-bottom corner.
     * After normalization, paste point = world location of minimum corner.
     * 
     * @param clipboard Original clipboard with arbitrary origin
     * @return New clipboard with origin shifted to minimum corner
     */
    private Clipboard normalizeClipboardOrigin(Clipboard clipboard) {
        BlockVector3 currentOrigin = clipboard.getOrigin();
        BlockVector3 minPoint = clipboard.getRegion().getMinimumPoint();
        
        // If already normalized, return as-is
        if (currentOrigin.equals(minPoint)) {
            return clipboard;
        }
        
        // Shift origin to minimum corner
        clipboard.setOrigin(minPoint);
        LOGGER.fine(String.format("[STRUCT] Normalized clipboard origin from %s to %s", 
                currentOrigin, minPoint));
        
        return clipboard;
    }
    
    /**
     * Compute exact AABB bounds for a structure placement, accounting for rotation.
     * Assumes clipboard origin has been normalized to minimum corner.
     * Returns int array: [minX, maxX, minY, maxY, minZ, maxZ]
     * 
     * @param origin Paste origin location (where clipboard origin is placed)
     * @param clipboard WorldEdit clipboard (may be null for procedural structures)
     * @param baseWidth Original structure width (X-axis before rotation, fallback if no clipboard)
     * @param baseDepth Original structure depth (Z-axis before rotation, fallback if no clipboard)
     * @param height Structure height (Y-axis, fallback if no clipboard)
     * @param rotation Rotation angle in degrees (0, 90, 180, 270)
     * @return int[] {minX, maxX, minY, maxY, minZ, maxZ}
     */
    private int[] computeAABB(Location origin, Clipboard clipboard, int baseWidth, int baseDepth, int height, int rotation) {
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();
        
        if (clipboard != null) {
            // Clipboard origin is normalized to minimum corner, so we can work directly with dimensions
            BlockVector3 dimensions = clipboard.getDimensions();
            int sizeX = dimensions.getX();
            int sizeY = dimensions.getY();
            int sizeZ = dimensions.getZ();
            
            // Calculate the 8 corners of the bounding box in schematic space (origin is at 0,0,0)
            // Since origin = minPoint after normalization, corners are just (0,0,0) to (sizeX, sizeY, sizeZ)
            int[][] corners = new int[8][3];
            int idx = 0;
            for (int x : new int[]{0, sizeX}) {
                for (int y : new int[]{0, sizeY}) {
                    for (int z : new int[]{0, sizeZ}) {
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
            
            // Translate to world coordinates (paste origin + rotated offsets)
            // Note: rotated corners are already inclusive bounds (0 to size), so no adjustment needed
            int minX = originX + minRotX;
            int maxX = originX + maxRotX - 1; // -1 because size is exclusive (0 to N = N blocks = indices 0..N-1)
            int minY = originY + minRotY;
            int maxY = originY + maxRotY - 1;
            int minZ = originZ + minRotZ;
            int maxZ = originZ + maxRotZ - 1;
            
            return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
        }
        
        // Fallback for procedural structures (no clipboard)
        int effectiveWidth, effectiveDepth;
        if (rotation == 90 || rotation == 270) {
            effectiveWidth = baseDepth;
            effectiveDepth = baseWidth;
        } else {
            effectiveWidth = baseWidth;
            effectiveDepth = baseDepth;
        }
        
        // Simple bounds assuming origin is minimum corner
        int minX = originX;
        int maxX = originX + effectiveWidth - 1;
        int minY = originY;
        int maxY = originY + height - 1;
        int minZ = originZ;
        int maxZ = originZ + effectiveDepth - 1;
        
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }
    
    /**
     * Sample the four foundation corners of a placed structure.
     * Samples at y=minY (foundation level).
     * Order: NW, NE, SE, SW (clockwise from top-left when viewed from above, Z+ is south)
     * 
     * @param world World containing the structure
     * @param bounds AABB bounds [minX, maxX, minY, maxY, minZ, maxZ]
     * @return Array of 4 CornerSamples
     */
    private com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[] sampleFoundationCorners(
            World world, int[] bounds) {
        int minX = bounds[0];
        int maxX = bounds[1];
        int minY = bounds[2];
        int minZ = bounds[4];
        int maxZ = bounds[5];
        
        // Sample at foundation level (minY)
        int y = minY;
        
        // Corner order: NW (minX, minZ), NE (maxX, minZ), SE (maxX, maxZ), SW (minX, maxZ)
        // In Minecraft coords: Z+ is south, X+ is east
        // So NW = min X, min Z (north-west corner)
        com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample nw = 
            new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(
                minX, y, minZ, world.getBlockAt(minX, y, minZ).getType());
        
        com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample ne = 
            new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(
                maxX, y, minZ, world.getBlockAt(maxX, y, minZ).getType());
        
        com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample se = 
            new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(
                maxX, y, maxZ, world.getBlockAt(maxX, y, maxZ).getType());
        
        com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample sw = 
            new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample(
                minX, y, maxZ, world.getBlockAt(minX, y, maxZ).getType());
        
        return new com.davisodom.villageoverhaul.model.PlacementReceipt.CornerSample[]{nw, ne, se, sw};
    }
    
    /**
     * Format a location for logging.
     */
    private String formatLocation(Location loc) {
        return String.format("(%d,%d,%d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
    
    /**
     * Find the actual ground level by searching downward from origin.
     * Checks multiple points to find median ground level for better placement.
     * Avoids placing structures floating in air or inside trees.
     */
    private int findGroundLevel(World world, Location origin) {
        // For better accuracy, check 5 points: center and 4 corners
        int[] groundLevels = new int[5];
        int index = 0;
        
        // Center point
        groundLevels[index++] = findGroundLevelAtPoint(world, origin.getBlockX(), origin.getBlockZ());
        
        // Four corners (estimate 8 blocks out from origin)
        groundLevels[index++] = findGroundLevelAtPoint(world, origin.getBlockX() + 4, origin.getBlockZ() + 4);
        groundLevels[index++] = findGroundLevelAtPoint(world, origin.getBlockX() - 4, origin.getBlockZ() + 4);
        groundLevels[index++] = findGroundLevelAtPoint(world, origin.getBlockX() + 4, origin.getBlockZ() - 4);
        groundLevels[index++] = findGroundLevelAtPoint(world, origin.getBlockX() - 4, origin.getBlockZ() - 4);
        
        // Sort to find median
        java.util.Arrays.sort(groundLevels);
        int medianY = groundLevels[2]; // Middle value
        
        LOGGER.fine(String.format("[STRUCT] Ground levels checked: %d,%d,%d,%d,%d -> median=%d", 
                groundLevels[0], groundLevels[1], groundLevels[2], groundLevels[3], groundLevels[4], medianY));
        
        return medianY;
    }
    
    /**
     * Find ground level at a specific point by searching downward.
     */
    private int findGroundLevelAtPoint(World world, int x, int z) {
        // Start from world highest block
        int startY = world.getHighestBlockYAt(x, z);
        int searchY = startY;
        
        // Search downward up to 10 blocks to find solid ground
        for (int i = 0; i < 10; i++) {
            Block block = world.getBlockAt(x, searchY, z);
            Block below = world.getBlockAt(x, searchY - 1, z);
            
            // Found solid ground if current block is air and block below is solid
            if ((block.getType() == Material.AIR || TRIMMABLE_VEGETATION.contains(block.getType())) 
                    && below.getType().isSolid() && !isUnsuitableFoundation(below.getType())) {
                return searchY;
            }
            
            searchY--;
        }
        
        // Fallback to highest block Y if no solid ground found nearby
        return startY;
    }
    
    /**
     * Check if a block type is unsuitable for building foundation (e.g., ice).
     */
    private boolean isUnsuitableFoundation(Material material) {
        return material == Material.ICE || 
               material == Material.PACKED_ICE || 
               material == Material.BLUE_ICE ||
               material == Material.FROSTED_ICE ||
               material == Material.WATER ||
               material == Material.LAVA;
    }
    
    // Vegetation materials for ground-finding
    private static final Set<Material> TRIMMABLE_VEGETATION = new HashSet<>();
    static {
        TRIMMABLE_VEGETATION.add(Material.SHORT_GRASS);
        TRIMMABLE_VEGETATION.add(Material.TALL_GRASS);
        TRIMMABLE_VEGETATION.add(Material.FERN);
        TRIMMABLE_VEGETATION.add(Material.LARGE_FERN);
        TRIMMABLE_VEGETATION.add(Material.DEAD_BUSH);
        TRIMMABLE_VEGETATION.add(Material.DANDELION);
        TRIMMABLE_VEGETATION.add(Material.POPPY);
        TRIMMABLE_VEGETATION.add(Material.OAK_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.BIRCH_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.SPRUCE_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.JUNGLE_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.ACACIA_LEAVES);
        TRIMMABLE_VEGETATION.add(Material.DARK_OAK_LEAVES);
    }
    
    /**
     * Build a human-readable rejection reason from validation result.
     * 
     * @param result Site validation result
     * @return String describing why the site was rejected
     */
    private String buildRejectionReason(SiteValidator.ValidationResult result) {
        List<String> reasons = new ArrayList<>();
        
        if (!result.foundationOk) {
            // Check classification result for specific terrain issues
            if (result.classificationResult != null) {
                if (result.classificationResult.fluid > 0) {
                    reasons.add(String.format("fluid (water/lava: %d tiles)", result.classificationResult.fluid));
                }
                if (result.classificationResult.steep > 0) {
                    reasons.add(String.format("steep (%d tiles)", result.classificationResult.steep));
                }
                if (result.classificationResult.blocked > 0) {
                    reasons.add(String.format("blocked (%d tiles)", result.classificationResult.blocked));
                }
            }
            if (reasons.isEmpty()) {
                reasons.add("foundation (solidity/slope)");
            }
        }
        
        if (!result.interiorAirOk) {
            reasons.add("interior (insufficient air space)");
        }
        
        if (!result.entranceOk) {
            reasons.add("entrance (no clear access)");
        }
        
        return String.join(", ", reasons);
    }
    
    /**
     * Internal structure template representation.
     */
    private static class StructureTemplate {
        String id;
        int[] dimensions; // [width, height, depth]
        Clipboard clipboard; // WorldEdit clipboard (null for placeholder structures)
        // Entrance anchor relative to origin (0,0,0)
        BlockVector3 entranceOffset; 
        // Vector pointing OUT of the entrance
        BlockVector3 entranceFacing;
    }
    
    /**
     * Calculate the world location for the structure entrance.
     * Transforms the relative anchor and snaps to ground outside the structure.
     */
    private Location calculateEntranceLocation(World world, Location origin, StructureTemplate template, int rotation, int[] bounds, UUID villageId) {
        BlockVector3 offset = template.entranceOffset;
        BlockVector3 facing = template.entranceFacing;
        
        // Rotate offset and facing
        int offX = offset.getX();
        int offY = offset.getY();
        int offZ = offset.getZ();
        
        int faceX = facing.getX();
        int faceY = facing.getY();
        int faceZ = facing.getZ();
        
        int rotOffX = offX, rotOffZ = offZ;
        int rotFaceX = faceX, rotFaceZ = faceZ;
        
        switch (rotation) {
            case 90:
                rotOffX = -offZ;
                rotOffZ = offX;
                rotFaceX = -faceZ;
                rotFaceZ = faceX;
                break;
            case 180:
                rotOffX = -offX;
                rotOffZ = -offZ;
                rotFaceX = -faceX;
                rotFaceZ = -faceZ;
                break;
            case 270:
                rotOffX = offZ;
                rotOffZ = -offX;
                rotFaceX = faceZ;
                rotFaceZ = -faceX;
                break;
        }
        
        // Calculate door position in world
        int doorX = origin.getBlockX() + rotOffX;
        int doorY = origin.getBlockY() + offY; // Y offset usually doesn't rotate
        int doorZ = origin.getBlockZ() + rotOffZ;
        
        // Project outwards to be safe from buffer
        // Buffer is 2, so we need to be at least 3 blocks away from the face
        int targetX = doorX + (rotFaceX * 3);
        int targetZ = doorZ + (rotFaceZ * 3);
        
        // R003: Use SurfaceSolver to find ground level OUTSIDE the structure
        // Create temporary VolumeMask for this structure
        com.davisodom.villageoverhaul.model.VolumeMask tempMask = 
            new com.davisodom.villageoverhaul.model.VolumeMask.Builder()
                .structureId(template.id)
                .villageId(villageId)
                .bounds(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])
                .build();
        
        // Create SurfaceSolver with this mask (so it ignores structure blocks)
        java.util.List<com.davisodom.villageoverhaul.model.VolumeMask> masks = 
            java.util.Collections.singletonList(tempMask);
        com.davisodom.villageoverhaul.worldgen.SurfaceSolver solver = 
            new com.davisodom.villageoverhaul.worldgen.SurfaceSolver(world, masks);
        
        // Find walkable ground at target (ignoring structure blocks)
        int targetY = solver.getSurfaceHeight(targetX, targetZ) + 1; // +1 for walking surface
        
        Location entranceLoc = new Location(world, targetX, targetY, targetZ);
        LOGGER.info(String.format("[STRUCT][ENTRANCE] Calculated entrance for '%s' at %s (rotation=%d°, offset=%s, facing=%s)",
                template.id, formatLocation(entranceLoc), rotation, offset, facing));
        
        return entranceLoc;
    }
    
    /**
     * Check if an AABB collides with any existing VolumeMasks (with spacing buffer).
     * R011b: Pre-placement collision detection to prevent wasted placements.
     * 
     * @param bounds AABB bounds {minX, maxX, minY, maxY, minZ, maxZ}
     * @param existingMasks List of existing VolumeMasks to check against
     * @param spacingBuffer Minimum spacing distance between structures
     * @return true if collision detected, false if clear
     */
    private boolean checkAABBCollision(int[] bounds, java.util.List<com.davisodom.villageoverhaul.model.VolumeMask> existingMasks, int spacingBuffer) {
        for (com.davisodom.villageoverhaul.model.VolumeMask mask : existingMasks) {
            // Expand existing mask with spacing buffer
            com.davisodom.villageoverhaul.model.VolumeMask expandedMask = mask.expand(spacingBuffer);
            
            // Check AABB intersection
            // Two AABBs intersect if they overlap on ALL three axes
            boolean xOverlap = bounds[0] <= expandedMask.getMaxX() && bounds[1] >= expandedMask.getMinX();
            boolean yOverlap = bounds[2] <= expandedMask.getMaxY() && bounds[3] >= expandedMask.getMinY();
            boolean zOverlap = bounds[4] <= expandedMask.getMaxZ() && bounds[5] >= expandedMask.getMinZ();
            
            if (xOverlap && yOverlap && zOverlap) {
                LOGGER.fine(String.format("[STRUCT] Collision detected: candidate bounds=(%d..%d, %d..%d, %d..%d) vs mask %s (with %d spacing)",
                        bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
                        mask.getStructureId(), spacingBuffer));
                return true;
            }
        }
        
        return false;
    }
}
