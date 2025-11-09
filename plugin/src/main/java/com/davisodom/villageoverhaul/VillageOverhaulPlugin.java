package com.davisodom.villageoverhaul;

import com.davisodom.villageoverhaul.admin.AdminHttpServer;
import com.davisodom.villageoverhaul.commands.ProjectCommands;
import com.davisodom.villageoverhaul.commands.TestCommands;
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
    
    private static VillageOverhaulPlugin instance;
    private Logger logger;
    
    // Configuration values
    private int minBuildingSpacing;
    private int minVillageSpacing;
    private int spawnProximityRadius;
    
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
    
    @Override
    public void onEnable() {
        instance = this;
        logger = getLogger();
        
        logger.info("Village Overhaul v" + getPluginMeta().getVersion() + " starting...");
        
        // Load configuration
        saveDefaultConfig();
        minBuildingSpacing = getConfig().getInt("village.minBuildingSpacing", 8);
        minVillageSpacing = getConfig().getInt("village.minVillageSpacing", 200);
        spawnProximityRadius = getConfig().getInt("village.spawnProximityRadius", 512);
    logger.info("OK Configuration loaded (minBuildingSpacing=" + minBuildingSpacing + 
                ", minVillageSpacing=" + minVillageSpacing + 
                ", spawnProximityRadius=" + spawnProximityRadius + ")");
        
        // Initialize foundational services
        initializeFoundation();
        
        // Start the tick engine (must be after all service initialization)
        if (tickEngine != null) {
            tickEngine.start();
        }
        
        logger.info("Village Overhaul enabled successfully!");
    }
    
    @Override
    public void onDisable() {
        logger.info("Village Overhaul shutting down...");
        
        // Despawn all custom villagers
        if (customVillagerService != null) {
            customVillagerService.despawnAll();
        }
        
        // Graceful shutdown
        if (tickEngine != null) {
            tickEngine.stop();
        }
        
        if (adminServer != null) {
            adminServer.stop();
        }
        
        // TODO: Save all state via JsonStore
        
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
    customVillagerService = new CustomVillagerService(this, logger, metrics);
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
            adminServer = new AdminHttpServer(logger, 8080);
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
    
    /**
     * Get the plugin instance
     * @return Plugin singleton
     */
    public static VillageOverhaulPlugin getInstance() {
        return instance;
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
    
    public ProjectService getProjectService() { return projectService; }
    
    public ProjectGenerator getProjectGenerator() { return projectGenerator; }
    
    public com.davisodom.villageoverhaul.projects.UpgradeExecutor getUpgradeExecutor() { return upgradeExecutor; }
    
    public CustomVillagerService getCustomVillagerService() { return customVillagerService; }
    
    public VillagerAppearanceAdapter getVillagerAppearanceAdapter() { return villagerAppearanceAdapter; }
    
    public VillagerInteractionController getVillagerInteractionController() { return villagerInteractionController; }
    
    /**
     * Get configured minimum building spacing
     * @return Minimum spacing in blocks (default: 8)
     */
    public int getMinBuildingSpacing() {
        return minBuildingSpacing;
    }
    
    /**
     * Get configured minimum village spacing (border-to-border)
     * @return Minimum spacing in blocks (default: 200)
     */
    public int getMinVillageSpacing() {
        return minVillageSpacing;
    }
    
    /**
     * Get configured spawn proximity radius for first village
     * @return Maximum radius in blocks (default: 512)
     */
    public int getSpawnProximityRadius() {
        return spawnProximityRadius;
    }
}
