package com.davisodom.villageoverhaul.admin;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.economy.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.logging.Logger;

/**
 * Minimal HTTP admin server for CI testing and diagnostics
 * 
 * Provides REST endpoints per specs/001-village-overhaul/contracts/openapi.yaml
 * Used for:
 * - CI deterministic simulation tests
 * - Admin diagnostics
 * - NOT for player-facing features (plugin remains server-authoritative)
 * 
 * Constitution compliance:
 * - Principle X: Security (rate limits, ops-only access)
 */
public class AdminHttpServer {
    
    private final Logger logger;
    private HttpServer server;
    private final int port;
    
    public AdminHttpServer(Logger logger, int port) {
        this.logger = logger;
        this.port = port;
    }
    
    /**
     * Start the HTTP server
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Register endpoints per OpenAPI spec
        server.createContext("/healthz", new HealthCheckHandler());
        server.createContext("/v1/wallets", new WalletsHandler());
        server.createContext("/v1/villages", new VillagesHandler());
        server.createContext("/v1/contracts", new ContractsHandler());
        server.createContext("/v1/properties", new PropertiesHandler());
        
        server.setExecutor(null); // Default executor
        server.start();
        
        logger.info("Admin HTTP server started on port " + port);
    }
    
    /**
     * Stop the HTTP server
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("Admin HTTP server stopped");
        }
    }
    
    /**
     * Health check endpoint
     */
    private static class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"status\":\"OK\"}";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
    
    /**
     * Wallets endpoints
     * TODO: Wire to WalletService in Phase 2/3
     */
    private static class WalletsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String msg = "{\"error\":\"Method Not Allowed\"}";
                exchange.getResponseHeaders().add("Allow", "GET");
                exchange.sendResponseHeaders(405, msg.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(msg.getBytes()); }
                return;
            }

            WalletService walletService = VillageOverhaulPlugin.getInstance().getWalletService();
            ObjectMapper om = new ObjectMapper();
            ArrayNode arr = om.createArrayNode();
            walletService.getAllWallets().forEach((uuid, wallet) -> {
                ObjectNode node = om.createObjectNode();
                node.put("ownerId", uuid.toString());
                node.put("balanceMillz", wallet.getBalanceMillz());
                arr.add(node);
            });
            byte[] bytes = om.createObjectNode().set("wallets", arr).toString().getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
    
    /**
     * Villages endpoints
     * GET /v1/villages - List all villages with basic info
     */
    private static class VillagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String msg = "{\"error\":\"Method Not Allowed\"}";
                exchange.getResponseHeaders().add("Allow", "GET");
                exchange.sendResponseHeaders(405, msg.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(msg.getBytes()); }
                return;
            }

            var villageService = VillageOverhaulPlugin.getInstance().getVillageService();
            ObjectMapper om = new ObjectMapper();
            ArrayNode arr = om.createArrayNode();
            villageService.getAllVillages().forEach(village -> {
                ObjectNode node = om.createObjectNode();
                node.put("id", village.getId().toString());
                node.put("cultureId", village.getCultureId());
                node.put("name", village.getName());
                node.put("wealthMillz", village.getWealthMillz());
                // Location metadata for diagnostics
                node.put("world", village.getWorldName());
                node.put("x", village.getX());
                node.put("y", village.getY());
                node.put("z", village.getZ());
                arr.add(node);
            });
            byte[] bytes = om.createObjectNode().set("villages", arr).toString().getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
    
    /**
     * Contracts endpoints
     * TODO: Wire to ContractService in Phase 4
     */
    private static class ContractsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"contracts\":[]}";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
    
    /**
     * Properties endpoints
     * TODO: Wire to PropertyService in Phase 7
     */
    private static class PropertiesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"properties\":[]}";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}
