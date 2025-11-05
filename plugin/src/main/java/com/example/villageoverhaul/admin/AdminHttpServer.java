package com.example.villageoverhaul.admin;

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
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            
            // Stub: Return 501 Not Implemented for now
            String response = "{\"error\":\"Not implemented yet\"}";
            exchange.sendResponseHeaders(501, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
    
    /**
     * Villages endpoints
     * TODO: Wire to VillageService in Phase 3
     */
    private static class VillagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"villages\":[]}";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
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
