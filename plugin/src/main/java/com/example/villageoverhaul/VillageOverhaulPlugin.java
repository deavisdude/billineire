package com.example.villageoverhaul;

import com.example.villageoverhaul.admin.AdminHttpServer;
import com.example.villageoverhaul.core.TickEngine;
import com.example.villageoverhaul.data.SchemaValidator;
import com.example.villageoverhaul.economy.WalletService;
import com.example.villageoverhaul.obs.Metrics;
import com.example.villageoverhaul.persistence.JsonStore;
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
    private AdminHttpServer adminServer;
    
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
        
        // Wallet service (economy)
        walletService = new WalletService();
        logger.info("✓ Wallet service initialized");
        
        // Tick engine
        tickEngine = new TickEngine(this);
        // Don't start yet - will register systems in user story phases
        logger.info("✓ Tick engine initialized (not started)");
        
        // Admin HTTP server (for CI/testing)
        try {
            adminServer = new AdminHttpServer(logger, 8080);
            adminServer.start();
            logger.info("✓ Admin HTTP server started on port 8080");
        } catch (Exception e) {
            logger.warning("Failed to start admin HTTP server: " + e.getMessage());
            logger.warning("  (This is optional for CI testing)");
        }
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
}
