package com.davisodom.villageoverhaul;

import com.davisodom.villageoverhaul.admin.AdminHttpServer;
import com.davisodom.villageoverhaul.commands.ProjectCommands;
import com.davisodom.villageoverhaul.commands.TestCommands;
import com.davisodom.villageoverhaul.commands.TickBudgetedGenerationQueue;
import com.davisodom.villageoverhaul.commands.VillageCommands;
import com.davisodom.villageoverhaul.core.TickEngine;
import com.davisodom.villageoverhaul.cultures.CultureService;
import com.davisodom.villageoverhaul.data.SchemaValidator;
import com.davisodom.villageoverhaul.economy.TradeListener;
import com.davisodom.villageoverhaul.economy.WalletService;
import com.davisodom.villageoverhaul.npc.CustomVillagerService;
import com.davisodom.villageoverhaul.npc.VillagerAppearanceAdapter;
import com.davisodom.villageoverhaul.npc.VillagerInteractionController;
import com.davisodom.villageoverhaul.obs.Metrics;
import com.davisodom.villageoverhaul.persistence.JsonStore;
import com.davisodom.villageoverhaul.projects.ProjectGenerator;
import com.davisodom.villageoverhaul.projects.ProjectService;
import com.davisodom.villageoverhaul.villages.VillageMetadataStore;
import com.davisodom.villageoverhaul.villages.VillageService;
import com.davisodom.villageoverhaul.worldgen.VillageWorldgenAdapter;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.logging.Logger;

/**
 * Main plugin class for Village Overhaul
 * 
 * Implements a Millénaire-inspired village system with:
 * - Trade-funded village projects
 * - Reputation and contracts
 * - Deterministic dungeons and custom enemies
 * - Inter-village relationships
 * - Property purchasing
 * 
 * All logic is server-authoritative with deterministic tick updates.
 */
public class VillageOverhaulPlugin extends JavaPlugin {
    
    private static final int DEFAULT_VILLAGE_SPACING = 200;
    private Logger logger;
    
    // Configuration values
    private int minBuildingSpacing;
    private int minVillageSpacing;
    private int maxBoundsRadiusBlocks;
    private double spacingMultiplier;
    private int spawnProximityRadius;
    private boolean allowMarkerFallback;
    private double villagersPerStructure;
    private double villagerCapacityPerStructure;
    private int pathMaxNodesExplored;
    private int pathPlannerConcurrencyCap;
    private int pathNodeCapRetryMaxAttempts;
    private int pathNodeCapBackoffBaseMs;
    private int pathNodeCapBackoffMaxMs;
    
    // Core services (Phase 2)
    private TickEngine tickEngine;
    private WalletService walletService;
    private JsonStore jsonStore;
    private SchemaValidator schemaValidator;
    private Metrics metrics;
    private CultureService cultureService;
    private AdminHttpServer adminServer;
    private VillageService villageService;
    private VillageMetadataStore metadataStore;
    private VillageWorldgenAdapter worldgenAdapter;
    private ProjectService projectService;
    private ProjectGenerator projectGenerator;
    private TradeListener tradeListener;
    private com.davisodom.villageoverhaul.projects.UpgradeExecutor upgradeExecutor;
    
    // NPC services (Phase 2.6: Custom Villagers)
    private CustomVillagerService customVillagerService;
    private VillagerAppearanceAdapter villagerAppearanceAdapter;
    private VillagerInteractionController villagerInteractionController;
    
    // T066: Non-blocking command generation queue
    private TickBudgetedGenerationQueue generationQueue;
    
    @Override
    public void onEnable() {
        logger = getLogger();
        
        logger.info("Village Overhaul v" + getPluginMeta().getVersion() + " starting...");
        
        // Load configuration
        saveDefaultConfig();
        minBuildingSpacing = getConfig().getInt("village.minBuildingSpacing", 8);
        maxBoundsRadiusBlocks = getConfig().getInt("village.maxBoundsRadiusBlocks", 220);
        spacingMultiplier = getConfig().getDouble("village.spacingMultiplier", 1.25);
        minVillageSpacing = resolveMinVillageSpacing(getConfig().getInt("village.minVillageSpacing", 200));
        spawnProximityRadius = getConfig().getInt("village.spawnProximityRadius", 512);
        allowMarkerFallback = getConfig().getBoolean("worldgen.allowMarkerFallback", false);
        villagersPerStructure = getConfig().getDouble("worldgen.spawn.villagersPerStructure", 2.0);
        villagerCapacityPerStructure = getConfig().getDouble("npc.villagerCapacityPerStructure", 2.0);
        pathMaxNodesExplored = getConfig().getInt("worldgen.path.maxNodesExplored", 15000);
        pathPlannerConcurrencyCap = getConfig().getInt("worldgen.path.plannerConcurrencyCap", 3);
        pathNodeCapRetryMaxAttempts = getConfig().getInt("worldgen.path.nodeCapRetryMaxAttempts", 2);
        pathNodeCapBackoffBaseMs = getConfig().getInt("worldgen.path.nodeCapBackoffBaseMs", 50);
        pathNodeCapBackoffMaxMs = getConfig().getInt("worldgen.path.nodeCapBackoffMaxMs", 200);
        
        // Configure debug logging if enabled
        getConfig().addDefault("debug.verbose", false);
        getConfig().addDefault("worldgen.allowMarkerFallback", false);
        getConfig().addDefault("worldgen.spawn.villagersPerStructure", 2.0);
        getConfig().addDefault("npc.villagerCapacityPerStructure", 2.0);
        getConfig().addDefault("worldgen.path.maxNodesExplored", 15000);
        getConfig().addDefault("worldgen.path.plannerConcurrencyCap", 3);
        getConfig().addDefault("worldgen.path.nodeCapRetryMaxAttempts", 2);
        getConfig().addDefault("worldgen.path.nodeCapBackoffBaseMs", 50);
        getConfig().addDefault("worldgen.path.nodeCapBackoffMaxMs", 200);
        saveConfig();
        
        boolean verboseLogging = getConfig().getBoolean("debug.verbose", false);
        if (verboseLogging) {
            // Configure logging to INFO level (Paper's console doesn't show FINE reliably)
            logger.info("OK Debug logging configured (verbose=" + verboseLogging + ") - diagnostic logs will use INFO level");
        }
        
logger.info("OK Configuration loaded (minBuildingSpacing=" + minBuildingSpacing +
                ", maxBoundsRadiusBlocks=" + maxBoundsRadiusBlocks + ", spacingMultiplier=" + spacingMultiplier +
                ", minVillageSpacing=" + minVillageSpacing +
                ", spawnProximityRadius=" + spawnProximityRadius + ", allowMarkerFallback=" + allowMarkerFallback +
                ", villagersPerStructure=" + villagersPerStructure +
                ", villagerCapacityPerStructure=" + villagerCapacityPerStructure +
                ", pathMaxNodesExplored=" + pathMaxNodesExplored +
                ", pathPlannerConcurrencyCap=" + pathPlannerConcurrencyCap +
                ", pathNodeCapRetryMaxAttempts=" + pathNodeCapRetryMaxAttempts +
                ", pathNodeCapBackoffBaseMs=" + pathNodeCapBackoffBaseMs +
                ", pathNodeCapBackoffMaxMs=" + pathNodeCapBackoffMaxMs + ")");
        
        // Initialize foundational services
        initializeFoundation();

        try {
            metadataStore.loadAll();
            rehydrateVillageServiceFromMetadata();
            logger.info("OK Village metadata loaded");
        } catch (Exception e) {
            logger.warning("Failed to load village metadata: " + e.getMessage());
        }

        if (customVillagerService != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                int restored = customVillagerService.restorePersistedVillagers();
                logger.info("OK Restored " + restored + " persisted villagers");
            }, 1L);
        }
        
        // Start the tick engine (must be after all service initialization)
        if (tickEngine != null) {
            tickEngine.start();
        }
        
        // T066: Start non-blocking generation queue
        if (generationQueue != null) {
            generationQueue.start();
        }
        
        logger.info("Village Overhaul enabled successfully!");
    }

    private void rehydrateVillageServiceFromMetadata() {
        if (villageService == null || metadataStore == null) {
            return;
        }

        villageService.clearAll();
        for (VillageMetadataStore.VillageMetadata metadata : metadataStore.getAllVillages()) {
            org.bukkit.World world = metadata.getOrigin().getWorld();
            if (world == null) {
                continue;
            }

            String villageName = metadata.getVillageName();
            if (villageName == null || villageName.isBlank()) {
                String shortId = metadata.getVillageId().toString().substring(0, 8);
                villageName = metadata.getCultureId() + "-" + shortId;
            }

            villageService.loadVillage(
                metadata.getVillageId(),
                metadata.getCultureId(),
                villageName,
                0L,
                world,
                metadata.getOrigin().getBlockX(),
                metadata.getOrigin().getBlockY(),
                metadata.getOrigin().getBlockZ()
            );
        }
    }
    
    @Override
    public void onDisable() {
        logger.info("Village Overhaul shutting down...");

        if (metadataStore != null) {
            try {
                metadataStore.saveAll();
            } catch (Exception e) {
                logger.warning("Failed to save village metadata: " + e.getMessage());
            }
        }
        
        // Despawn all custom villagers
        if (customVillagerService != null) {
            customVillagerService.despawnAll(true);
        }
        
        // T066: Stop generation queue
        if (generationQueue != null) {
            generationQueue.stop();
        }
        
        // Graceful shutdown
        if (tickEngine != null) {
            tickEngine.stop();
        }
        
        if (adminServer != null) {
            adminServer.stop();
        }
        
        
        logger.info("Village Overhaul disabled.");
    }
    
    /**
     * Initialize foundational services (Phase 2)
     */
    private void initializeFoundation() {
        // Metrics and observability
    metrics = new Metrics(logger);
    logger.info("OK Metrics initialized");
        
        // Persistence layer
    jsonStore = new JsonStore(getDataFolder(), logger);
    logger.info("OK JSON store initialized");
        
        // Schema validator
    schemaValidator = new SchemaValidator(logger);
    logger.info("OK Schema validator initialized");
        
    // Culture service (data-driven cultural sets)
    cultureService = new CultureService(logger, schemaValidator);
    cultureService.load(this);
    logger.info("OK Culture service loaded " + cultureService.all().size() + " culture(s)");

        // Wallet service (economy)
    walletService = new WalletService();
    logger.info("OK Wallet service initialized");
        
        // Village service (minimal for Phase 2.5)
    villageService = new VillageService();
    logger.info("OK Village service initialized");
        
        // Metadata store (Phase 2.1: inter-village spacing enforcement)
    metadataStore = new VillageMetadataStore(this);
    logger.info("OK Village metadata store initialized");
        
        // Project service (US1)
    projectService = new ProjectService(logger);
    logger.info("OK Project service initialized");
        
        // Project generator (auto-create projects for villages)
    projectGenerator = new ProjectGenerator(this);
    logger.info("OK Project generator initialized");
        
        // Upgrade executor (US1: visual building upgrades)
    upgradeExecutor = new com.davisodom.villageoverhaul.projects.UpgradeExecutor(this);
    logger.info("OK Upgrade executor initialized");
        
        // Custom villager service (Phase 2.6)
    customVillagerService = new CustomVillagerService(this, logger, metrics, metadataStore, villagerCapacityPerStructure);
    logger.info("OK Custom villager service initialized");
        
        // Villager appearance adapter (Phase 2.6)
    villagerAppearanceAdapter = new VillagerAppearanceAdapter(logger);
    logger.info("OK Villager appearance adapter initialized");
        
        // Villager interaction controller (Phase 2.6) - now with US1 integration
        villagerInteractionController = new VillagerInteractionController(this, logger, customVillagerService, metrics);
        getServer().getPluginManager().registerEvents(villagerInteractionController, this);
    logger.info("OK Villager interaction controller registered");
        
        // Tick engine
    tickEngine = new TickEngine(this);
    logger.info("OK Tick engine initialized");
        
        // Trade listener (US1: route trade proceeds to projects for vanilla villagers)
    tradeListener = new TradeListener(this);
        getServer().getPluginManager().registerEvents(tradeListener, this);
    logger.info("OK Trade listener registered");
        
        // Admin HTTP server (for CI/testing)
        try {
            adminServer = new AdminHttpServer(logger, 8080, walletService, villageService);
            adminServer.start();
            logger.info("OK Admin HTTP server started on port 8080");
        } catch (Exception e) {
            logger.warning("Failed to start admin HTTP server: " + e.getMessage());
            logger.warning("  (This is optional for CI testing)");
        }

        // Worldgen adapter: seed a deterministic test village for US1 readiness
        worldgenAdapter = new VillageWorldgenAdapter(this);
        getServer().getPluginManager().registerEvents(worldgenAdapter, this);
        // In test/CI contexts, worlds may already be loaded: attempt immediate seed
        worldgenAdapter.seedIfPossible();
        
        // T066: Initialize non-blocking generation queue
        generationQueue = new TickBudgetedGenerationQueue(this, metadataStore);
        logger.info("OK Generation queue initialized");
        
        // Register commands
        ProjectCommands projectCommands = new ProjectCommands(this);
        PluginCommand voCmd = getCommand("vo");
        if (voCmd != null) {
            voCmd.setExecutor(projectCommands);
            voCmd.setTabCompleter(projectCommands);
        }
        
        TestCommands testCommands = new TestCommands(this, customVillagerService, villagerInteractionController);
        PluginCommand votestCmd = getCommand("votest");
        if (votestCmd != null) {
            votestCmd.setExecutor(testCommands);
            votestCmd.setTabCompleter(testCommands);
        }
        
        VillageCommands villageCommands = new VillageCommands(this);
        PluginCommand villagesCmd = getCommand("villages");
        if (villagesCmd != null) {
            villagesCmd.setExecutor(villageCommands);
            villagesCmd.setTabCompleter(villageCommands);
        }
        PluginCommand villageCmd = getCommand("village");
        if (villageCmd != null) {
            villageCmd.setExecutor(villageCommands);
            villageCmd.setTabCompleter(villageCommands);
        }
        
        logger.info("Village Overhaul enabled successfully!");
    }

    private int resolveMinVillageSpacing(int configuredSpacing) {
        if (configuredSpacing > 0) {
            return configuredSpacing;
        }
        int diameter = maxBoundsRadiusBlocks * 2;
        int derived = (int) Math.ceil(diameter * spacingMultiplier);
        if (derived <= 0) {
            return DEFAULT_VILLAGE_SPACING;
        }
        return derived;
    }

    // Getters for services (used by subsystems)
    
    public TickEngine getTickEngine() {
        return tickEngine;
    }
    
    public WalletService getWalletService() {
        return walletService;
    }
    
    public JsonStore getJsonStore() {
        return jsonStore;
    }
    
    public SchemaValidator getSchemaValidator() {
        return schemaValidator;
    }
    
    public Metrics getMetrics() {
        return metrics;
    }

    public CultureService getCultureService() { return cultureService; }
    
    public VillageService getVillageService() { return villageService; }
    
    public VillageMetadataStore getMetadataStore() { return metadataStore; }

    public VillageWorldgenAdapter getWorldgenAdapter() { return worldgenAdapter; }

    public boolean isMarkerFallbackAllowed() { return allowMarkerFallback; }
    
    public ProjectService getProjectService() { return projectService; }
    
    public ProjectGenerator getProjectGenerator() { return projectGenerator; }
    
    public com.davisodom.villageoverhaul.projects.UpgradeExecutor getUpgradeExecutor() { return upgradeExecutor; }
    
    public CustomVillagerService getCustomVillagerService() { return customVillagerService; }
    
    public VillagerAppearanceAdapter getVillagerAppearanceAdapter() { return villagerAppearanceAdapter; }
    
    public VillagerInteractionController getVillagerInteractionController() { return villagerInteractionController; }
    
    /**
     * Get the non-blocking generation queue (T066)
     * @return Generation queue
     */
    public TickBudgetedGenerationQueue getGenerationQueue() { return generationQueue; }
    
    /**
     * Get configured minimum building spacing
     * @return Minimum spacing in blocks (default: 8)
     */
    public int getMinBuildingSpacing() {
        return minBuildingSpacing;
    }
    
    public int getMinVillageSpacing() {
        return minVillageSpacing;
    }

    public int getMaxBoundsRadiusBlocks() {
        return maxBoundsRadiusBlocks;
    }

    public double getSpacingMultiplier() {
        return spacingMultiplier;
    }

    public int getSpawnProximityRadius() {
        return spawnProximityRadius;
    }

    public double getVillagersPerStructure() {
        return villagersPerStructure;
    }

    public int getPathMaxNodesExplored() {
        return pathMaxNodesExplored;
    }

    public int getPathPlannerConcurrencyCap() {
        return pathPlannerConcurrencyCap;
    }

    public int getPathNodeCapRetryMaxAttempts() {
        return pathNodeCapRetryMaxAttempts;
    }

    public int getPathNodeCapBackoffBaseMs() {
        return pathNodeCapBackoffBaseMs;
    }

    public int getPathNodeCapBackoffMaxMs() {
        return pathNodeCapBackoffMaxMs;
    }
}
