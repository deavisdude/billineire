package com.example.villageoverhaul.cultures;

import com.example.villageoverhaul.data.SchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Loads culture definitions from classpath resources and validates against schema.
 * Minimal scaffold to unblock US1 by ensuring at least one culture is available at runtime.
 */
public class CultureService {
    private final Logger logger;
    private final SchemaValidator validator;
    private final Map<String, Culture> cultures = new LinkedHashMap<>();

    public CultureService(Logger logger, SchemaValidator validator) {
        this.logger = logger;
        this.validator = validator;
    }

    /**
     * Load cultures from resources under /cultures/*.json
     *
     * @param plugin Plugin to access resource stream
     */
    public void load(Plugin plugin) {
        List<String> resourceNames = listResourceFiles(plugin, "cultures/");
        int loaded = 0;
        for (String res : resourceNames) {
            if (!res.endsWith(".json")) continue;
            try (InputStream in = plugin.getResource(res)) {
                if (in == null) continue;
                String json = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
                boolean ok = validator.validateCulture(json);
                if (!ok) {
                    logger.warning("Culture validation failed for " + res);
                    continue;
                }
                Culture c = Culture.fromJson(json);
                cultures.put(c.getId(), c);
                loaded++;
            } catch (IOException e) {
                logger.warning("Failed to load culture file " + res + ": " + e.getMessage());
            }
        }
        logger.info("Loaded " + loaded + " culture(s)");
    }

    public Optional<Culture> get(String cultureId) {
        return Optional.ofNullable(cultures.get(cultureId));
    }

    public Collection<Culture> all() {
        return Collections.unmodifiableCollection(cultures.values());
    }

    private static List<String> listResourceFiles(Plugin plugin, String pathPrefix) {
        // Bukkit's getResource doesn't list directories; maintain a simple manifest approach
        // Expect a resource file cultures/_manifest.txt listing JSON files.
        try (InputStream in = plugin.getResource(pathPrefix + "_manifest.txt")) {
            if (in == null) return Collections.emptyList();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return br.lines()
                        .map(String::trim)
                        .filter(s -> !s.isBlank() && !s.startsWith("#"))
                        .map(s -> pathPrefix + s)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    // Minimal culture model for bootstrap
    public static class Culture {
        private final String id;
        private final String name;

        public Culture(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }

        public static Culture fromJson(String json) {
            try {
                ObjectMapper om = new ObjectMapper();
                JsonNode node = om.readTree(json);
                String id = node.path("id").asText("");
                String name = node.path("name").asText("");
                return new Culture(id, name);
            } catch (Exception e) {
                return new Culture("", "");
            }
        }
    }
}
