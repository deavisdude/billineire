package com.example.villageoverhaul.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.Set;
import java.util.logging.Logger;

/**
 * JSON Schema validator for data-driven content
 * 
 * Validates cultures, projects, contracts, etc. against JSON schemas
 * to ensure extensibility and data integrity.
 * 
 * Constitution compliance:
 * - Principle VII: Extensibility & Data-Driven Content
 */
public class SchemaValidator {
    
    private final Logger logger;
    private final ObjectMapper mapper;
    private final JsonSchemaFactory schemaFactory;
    
    public SchemaValidator(Logger logger) {
        this.logger = logger;
        this.mapper = new ObjectMapper();
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }
    
    /**
     * Validate JSON data against a schema
     * 
     * @param schemaName Schema filename (e.g., "culture.json")
     * @param jsonData JSON data to validate
     * @return true if valid, false otherwise
     */
    public boolean validate(String schemaName, String jsonData) {
        try {
            // Load schema from resources
            InputStream schemaStream = getClass().getClassLoader()
                .getResourceAsStream("schemas/" + schemaName);
            
            if (schemaStream == null) {
                logger.severe("Schema not found: " + schemaName);
                return false;
            }
            
            JsonSchema schema = schemaFactory.getSchema(schemaStream);
            JsonNode dataNode = mapper.readTree(jsonData);
            
            // Validate
            Set<ValidationMessage> errors = schema.validate(dataNode);
            
            if (errors.isEmpty()) {
                logger.fine("Validation passed for " + schemaName);
                return true;
            } else {
                logger.warning("Validation failed for " + schemaName + ":");
                for (ValidationMessage error : errors) {
                    logger.warning("  - " + error.getMessage());
                }
                return false;
            }
            
        } catch (Exception e) {
            logger.severe("Schema validation error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Validate culture JSON
     */
    public boolean validateCulture(String jsonData) {
        return validate("culture.json", jsonData);
    }
    
    /**
     * Validate project JSON
     */
    public boolean validateProject(String jsonData) {
        return validate("project.json", jsonData);
    }
    
    /**
     * Validate contract JSON
     */
    public boolean validateContract(String jsonData) {
        return validate("contract.json", jsonData);
    }
    
    /**
     * Validate custom villager JSON
     */
    public boolean validateCustomVillager(String jsonData) {
        return validate("custom-villager.json", jsonData);
    }
}
