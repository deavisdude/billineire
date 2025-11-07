package com.davisodom.villageoverhaul.worldgen.impl;

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
        loadedStructures.put("house_roman_small", smallHouse);
        loadedStructures.put("house_small", smallHouse); // Fallback alias
        
        StructureTemplate mediumHouse = new StructureTemplate();
        mediumHouse.id = "house_roman_medium";
        mediumHouse.dimensions = new int[]{13, 8, 13}; // 13x8x13 Roman domus (townhouse)
        loadedStructures.put("house_roman_medium", mediumHouse);
        loadedStructures.put("house_medium", mediumHouse); // Fallback alias
        
        StructureTemplate villa = new StructureTemplate();
        villa.id = "house_roman_villa";
        villa.dimensions = new int[]{17, 9, 17}; // 17x9x17 Roman villa
        loadedStructures.put("house_roman_villa", villa);
        
        StructureTemplate workshop = new StructureTemplate();
        workshop.id = "workshop_roman_forge";
        workshop.dimensions = new int[]{11, 8, 11}; // 11x8x11 Blacksmith forge
        loadedStructures.put("workshop_roman_forge", workshop);
        loadedStructures.put("workshop", workshop); // Fallback alias
        
        StructureTemplate market = new StructureTemplate();
        market.id = "market_roman_stall";
        market.dimensions = new int[]{7, 6, 7}; // 7x6x7 Market stall
        loadedStructures.put("market_roman_stall", market);
        
        StructureTemplate bathhouse = new StructureTemplate();
        bathhouse.id = "building_roman_bathhouse";
        bathhouse.dimensions = new int[]{15, 7, 15}; // 15x7x15 Public bathhouse
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
                
                // Create template with actual dimensions from schematic
                StructureTemplate template = new StructureTemplate();
                template.id = structureId;
                template.clipboard = clipboard;
                
                BlockVector3 dimensions = clipboard.getDimensions();
                template.dimensions = new int[]{dimensions.getX(), dimensions.getY(), dimensions.getZ()};
                
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
        StructureTemplate template = loadedStructures.get(structureId);
        
        if (template == null) {
            LOGGER.warning(String.format("[STRUCT] Structure '%s' not loaded", structureId));
            return false;
        }
        
        LOGGER.info(String.format("[STRUCT] Begin placement: structureId=%s, origin=%s, seed=%d, world=%s",
                structureId, formatLocation(origin), seed, world.getName()));
        
        // Attempt placement with re-seating logic
        boolean placed = attemptPlacementWithReseating(template, world, origin, seed);
        
        if (placed) {
            LOGGER.info(String.format("[STRUCT] Seat successful: structure='%s', origin=%s, seed=%d",
                    structureId, formatLocation(origin), seed));
        } else {
            LOGGER.warning(String.format("[STRUCT] Abort: structure='%s', seed=%d, attempts=%d, reason=no_valid_site",
                    structureId, seed, MAX_RESEAT_ATTEMPTS));
        }
        
        return placed;
    }
    
    /**
     * Attempt placement with re-seating logic.
     * Tries initial location, then searches nearby if validation fails.
     */
    private boolean attemptPlacementWithReseating(StructureTemplate template, World world, Location origin, long seed) {
        Random random = new Random(seed);
        
        for (int attempt = 0; attempt < MAX_RESEAT_ATTEMPTS; attempt++) {
            Location currentOrigin = attempt == 0 ? origin : findAlternativeLocation(world, origin, random, attempt);
            
            LOGGER.info(String.format("[STRUCT] Seat attempt %d/%d: structure='%s', location=%s, seed=%d",
                    attempt + 1, MAX_RESEAT_ATTEMPTS, template.id, formatLocation(currentOrigin), seed));
            
            // Attempt minor terraforming to prepare site
            boolean terraformed = TerraformingUtil.prepareSite(
                    world,
                    currentOrigin,
                    template.dimensions[0],
                    template.dimensions[2],
                    template.dimensions[1]
            );
            
            if (!terraformed) {
                LOGGER.info(String.format("[STRUCT] Re-seat required: structure='%s', attempt=%d/%d, location=%s, seed=%d, reason=terraforming_failed",
                        template.id, attempt + 1, MAX_RESEAT_ATTEMPTS, formatLocation(currentOrigin), seed));
                continue;
            }
            
            // Site prepared - perform actual placement
            boolean placed = performActualPlacement(template, world, currentOrigin, seed);
            
            if (placed) {
                if (attempt > 0) {
                    LOGGER.info(String.format("[STRUCT] Re-seat successful: structure='%s', final_location=%s, attempts=%d, seed=%d",
                            template.id, formatLocation(currentOrigin), attempt + 1, seed));
                }
                return true;
            }
        }
        
        return false;
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
        // If template has a schematic loaded, use WorldEdit/FAWE placement
        if (template.clipboard != null && faweAvailable) {
            return placeWorldEdit(template, world, origin, seed);
        } else if (faweAvailable) {
            return placeFAWE(template, world, origin, seed);
        } else {
            return placePaperAPI(template, world, origin, seed);
        }
    }
    
    /**
     * Place structure using WorldEdit/FAWE with actual schematic data.
     */
    private boolean placeWorldEdit(StructureTemplate template, World world, Location origin, long seed) {
        LOGGER.fine(String.format("[STRUCT] Using WorldEdit placement for '%s'", template.id));
        
        try {
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            
            // Find ground level
            int groundY = findGroundLevel(world, origin);
            BlockVector3 weOrigin = BlockVector3.at(origin.getBlockX(), groundY, origin.getBlockZ());
            
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                ClipboardHolder holder = new ClipboardHolder(template.clipboard);
                
                // Apply deterministic rotation based on seed
                Random random = new Random(seed);
                int rotationDegrees = random.nextInt(4) * 90; // 0, 90, 180, or 270
                if (rotationDegrees > 0) {
                    AffineTransform transform = new AffineTransform();
                    holder.setTransform(holder.getTransform().combine(transform.rotateY(rotationDegrees)));
                }
                
                // Paste structure
                Operation operation = holder.createPaste(editSession)
                    .to(weOrigin)
                    .ignoreAirBlocks(false)
                    .build();
                
                Operations.complete(operation);
                
                LOGGER.fine(String.format("[STRUCT] WorldEdit placement successful for '%s'", template.id));
                
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
                
                return true;
            }
            
        } catch (Exception e) {
            LOGGER.warning(String.format("[STRUCT] WorldEdit placement failed for '%s': %s", 
                    template.id, e.getMessage()));
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
     * Internal structure template representation.
     */
    private static class StructureTemplate {
        String id;
        int[] dimensions; // [width, height, depth]
        Clipboard clipboard; // WorldEdit clipboard (null for placeholder structures)
    }
}
