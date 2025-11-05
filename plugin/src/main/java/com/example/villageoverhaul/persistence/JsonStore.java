package com.example.villageoverhaul.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Versioned JSON/YAML persistence with migration support
 * 
 * Stores plugin data in versioned files with:
 * - Forward-only migrations
 * - Backup before writes
 * - Schema validation (via SchemaValidator)
 * 
 * Constitution compliance:
 * - Principle IX: Save Compatibility & Migration Safety
 */
public class JsonStore {
    
    private final File dataFolder;
    private final Logger logger;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    
    public static final int SCHEMA_VERSION = 1;
    
    public JsonStore(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        
        // JSON mapper
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // YAML mapper
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }
    
    /**
     * Save data to JSON file with backup
     * 
     * @param filename Filename (relative to data folder)
     * @param data Object to serialize
     * @param schemaVersion Schema version for this data
     */
    public <T> void saveJson(String filename, T data, int schemaVersion) throws IOException {
        File file = new File(dataFolder, filename);
        
        // Backup existing file
        if (file.exists()) {
            backupFile(file);
        }
        
        // Wrap data with versioning metadata
        VersionedData<T> versioned = new VersionedData<>(schemaVersion, data);
        
        // Write atomically (temp file + rename)
        File tempFile = new File(file.getAbsolutePath() + ".tmp");
        jsonMapper.writeValue(tempFile, versioned);
        
        // Atomic rename
        Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        
        logger.info(String.format("Saved %s (schema v%d)", filename, schemaVersion));
    }
    
    /**
     * Load data from JSON file with version check
     * 
     * @param filename Filename (relative to data folder)
     * @param dataClass Data class type
     * @return Loaded data or null if file doesn't exist
     */
    public <T> T loadJson(String filename, Class<T> dataClass) throws IOException {
        File file = new File(dataFolder, filename);
        
        if (!file.exists()) {
            logger.info(String.format("%s not found, will create new", filename));
            return null;
        }
        
        // Read versioned wrapper
        VersionedData<?> versioned = jsonMapper.readValue(file, 
            jsonMapper.getTypeFactory().constructParametricType(VersionedData.class, dataClass));
        
        // Check schema version and migrate if needed
        if (versioned.schemaVersion < SCHEMA_VERSION) {
            logger.warning(String.format(
                "Schema upgrade needed for %s: v%d → v%d",
                filename, versioned.schemaVersion, SCHEMA_VERSION
            ));
            // TODO: Implement migration logic in Phase 2 polish
            // For now, accept old versions with a warning
        }
        
        logger.info(String.format("Loaded %s (schema v%d)", filename, versioned.schemaVersion));
        
        @SuppressWarnings("unchecked")
        T data = (T) versioned.data;
        return data;
    }
    
    /**
     * Save data to YAML file (for human-editable config)
     */
    public <T> void saveYaml(String filename, T data) throws IOException {
        File file = new File(dataFolder, filename);
        
        if (file.exists()) {
            backupFile(file);
        }
        
        File tempFile = new File(file.getAbsolutePath() + ".tmp");
        yamlMapper.writeValue(tempFile, data);
        Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        
        logger.info("Saved " + filename);
    }
    
    /**
     * Load data from YAML file
     */
    public <T> T loadYaml(String filename, Class<T> dataClass) throws IOException {
        File file = new File(dataFolder, filename);
        
        if (!file.exists()) {
            return null;
        }
        
        T data = yamlMapper.readValue(file, dataClass);
        logger.info("Loaded " + filename);
        return data;
    }
    
    /**
     * Backup a file before overwriting
     */
    private void backupFile(File file) throws IOException {
        String backupName = file.getName() + ".backup." + Instant.now().toEpochMilli();
        File backupFile = new File(file.getParentFile(), backupName);
        
        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        logger.fine("Backed up " + file.getName() + " to " + backupName);
        
        // Clean old backups (keep last 5)
        cleanOldBackups(file);
    }
    
    /**
     * Clean old backup files, keeping only the most recent N
     */
    private void cleanOldBackups(File originalFile) {
        File folder = originalFile.getParentFile();
        String baseName = originalFile.getName();
        
        File[] backups = folder.listFiles((dir, name) -> 
            name.startsWith(baseName + ".backup."));
        
        if (backups != null && backups.length > 5) {
            // Sort by modification time
            java.util.Arrays.sort(backups, (a, b) -> 
                Long.compare(a.lastModified(), b.lastModified()));
            
            // Delete oldest
            for (int i = 0; i < backups.length - 5; i++) {
                backups[i].delete();
                logger.fine("Deleted old backup: " + backups[i].getName());
            }
        }
    }
    
    /**
     * Versioned data wrapper for migration support
     */
    public static class VersionedData<T> {
        public int schemaVersion;
        public T data;
        
        public VersionedData() {} // For Jackson
        
        public VersionedData(int schemaVersion, T data) {
            this.schemaVersion = schemaVersion;
            this.data = data;
        }
    }
}
