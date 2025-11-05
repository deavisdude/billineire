package com.example.villageoverhaul;

import com.example.villageoverhaul.admin.AdminHttpServer;
import com.example.villageoverhaul.commands.ProjectCommands;
import com.example.villageoverhaul.commands.VillageCommands;
import com.example.villageoverhaul.core.TickEngine;
import com.example.villageoverhaul.cultures.CultureService;
import com.example.villageoverhaul.data.SchemaValidator;
import com.example.villageoverhaul.economy.TradeListener;
import com.example.villageoverhaul.economy.WalletService;
import com.example.villageoverhaul.obs.Metrics;
import com.example.villageoverhaul.persistence.JsonStore;
import com.example.villageoverhaul.projects.ProjectGenerator;
import com.example.villageoverhaul.projects.ProjectService;
import com.example.villageoverhaul.villages.VillageService;
import com.example.villageoverhaul.worldgen.VillageWorldgenAdapter;
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
    
    // Core services (Phase 2)
    private TickEngine tickEngine;
    private WalletService walletService;
    private JsonStore jsonStore;
    private SchemaValidator schemaValidator;
    private Metrics metrics;
    private CultureService cultureService;
    private AdminHttpServer adminServer;
    private VillageService villageService;
    private VillageWorldgenAdapter worldgenAdapter;
    private ProjectService projectService;
    private ProjectGenerator projectGenerator;
    private TradeListener tradeListener;
    private com.example.villageoverhaul.projects.UpgradeExecutor upgradeExecutor;
    
    @Override
    public void onEnable() {
        instance = this;
        logger = getLogger();
        
        logger.info("Village Overhaul v" + getDescription().getVersion() + " starting...");
        
        // Initialize foundational services
        initializeFoundation();
        
        logger.info("Village Overhaul enabled successfully!");
    }
    
    @Override
    public void onDisable() {
        logger.info("Village Overhaul shutting down...");
        
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
        logger.info("✓ Metrics initialized");
        
        // Persistence layer
        jsonStore = new JsonStore(getDataFolder(), logger);
        logger.info("✓ JSON store initialized");
        
        // Schema validator
        schemaValidator = new SchemaValidator(logger);
        logger.info("✓ Schema validator initialized");
        
    // Culture service (data-driven cultural sets)
    cultureService = new CultureService(logger, schemaValidator);
    cultureService.load(this);
    logger.info("✓ Culture service loaded " + cultureService.all().size() + " culture(s)");

        // Wallet service (economy)
        walletService = new WalletService();
        logger.info("✓ Wallet service initialized");
        
        // Village service (minimal for Phase 2.5)
        villageService = new VillageService();
        logger.info("✓ Village service initialized");
        
        // Project service (US1)
        projectService = new ProjectService(logger);
        logger.info("✓ Project service initialized");
        
        // Project generator (auto-create projects for villages)
        projectGenerator = new ProjectGenerator(this);
        logger.info("✓ Project generator initialized");
        
        // Upgrade executor (US1: visual building upgrades)
        upgradeExecutor = new com.example.villageoverhaul.projects.UpgradeExecutor(this);
        logger.info("✓ Upgrade executor initialized");
        
        // Tick engine
        tickEngine = new TickEngine(this);
        // Don't start yet - will register systems in user story phases
        logger.info("✓ Tick engine initialized (not started)");
        
        // Trade listener (US1: route trade proceeds to projects)
        tradeListener = new TradeListener(this);
        getServer().getPluginManager().registerEvents(tradeListener, this);
        logger.info("✓ Trade listener registered");
        
        // Admin HTTP server (for CI/testing)
        try {
            adminServer = new AdminHttpServer(logger, 8080);
            adminServer.start();
            logger.info("✓ Admin HTTP server started on port 8080");
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
        getCommand("vo").setExecutor(projectCommands);
        getCommand("vo").setTabCompleter(projectCommands);
        
        VillageCommands villageCommands = new VillageCommands(this);
        getCommand("villages").setExecutor(villageCommands);
        getCommand("villages").setTabCompleter(villageCommands);
        getCommand("village").setExecutor(villageCommands);
        getCommand("village").setTabCompleter(villageCommands);
        
        logger.info("✓ Commands registered");
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

    public VillageWorldgenAdapter getWorldgenAdapter() { return worldgenAdapter; }
    
    public ProjectService getProjectService() { return projectService; }
    
    public ProjectGenerator getProjectGenerator() { return projectGenerator; }
    
    public com.example.villageoverhaul.projects.UpgradeExecutor getUpgradeExecutor() { return upgradeExecutor; }
}
