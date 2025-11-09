package com.davisodom.villageoverhaul.npc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.io.InputStream;
import java.util.logging.Logger;

/**
 * Adapter for applying culture-driven appearance to Custom Villagers
 * 
 * Uses vanilla-compatible visuals (armor, colors, nametags) to provide
 * cross-edition appearance without client mods. Java-only resource packs
 * are optional enhancements; Bedrock gets safe fallbacks.
 * 
 * Constitution compliance:
 * - Principle I: Cross-edition compatible (no client mods required)
 * - Principle VI: Data-driven from custom-villager.json
 */
public class VillagerAppearanceAdapter {
    
    private final Logger logger;
    private final ObjectMapper mapper;
    private final MiniMessage miniMessage;
    
    public VillagerAppearanceAdapter(Logger logger) {
        this.logger = logger;
        this.mapper = new ObjectMapper();
        this.miniMessage = MiniMessage.miniMessage();
    }
    
    /**
     * Apply appearance profile to an entity based on definition
     * 
     * @param entity The entity to apply appearance to
     * @param definitionId Custom villager definition ID (e.g., "roman_blacksmith")
     */
    public void applyAppearance(Entity entity, String definitionId) {
        if (!(entity instanceof LivingEntity)) {
            logger.warning("Cannot apply appearance to non-living entity: " + entity.getType());
            return;
        }
        
        LivingEntity living = (LivingEntity) entity;
        
        try {
            // Load definition from resources
            InputStream defStream = getClass().getClassLoader()
                .getResourceAsStream("datapacks/villageoverhaul/custom-villagers/" + definitionId + ".json");
            
            if (defStream == null) {
                logger.warning("Custom villager definition not found: " + definitionId);
                return;
            }
            
            JsonNode def = mapper.readTree(defStream);
            JsonNode appearance = def.get("appearanceProfile");
            
            if (appearance == null) {
                logger.warning("No appearanceProfile in definition: " + definitionId);
                return;
            }
            
            // Apply display name
            if (appearance.has("displayName")) {
                String displayName = appearance.get("displayName").asText();
                Component nameComponent = miniMessage.deserialize(displayName);
                living.customName(nameComponent);
                living.setCustomNameVisible(true);
            }
            
            // Apply attire (armor/items)
            if (appearance.has("attire")) {
                EntityEquipment equipment = living.getEquipment();
                if (equipment != null) {
                    for (JsonNode item : appearance.get("attire")) {
                        applyAttireItem(equipment, item);
                    }
                }
            }
            
            // Note: Glow color would require team/scoreboard setup
            // Optional enhancement: implement team-based glow for visual distinction
            
            logger.fine("Applied appearance to " + definitionId + " (entity: " + entity.getUniqueId() + ")");
            
        } catch (Exception e) {
            logger.severe("Failed to apply appearance for " + definitionId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Apply a single attire item to equipment
     */
    private void applyAttireItem(EntityEquipment equipment, JsonNode item) {
        String slot = item.get("slot").asText();
        String materialName = item.get("material").asText();
        
        try {
            Material material = Material.valueOf(materialName);
            ItemStack stack = new ItemStack(material);
            
            // Apply color for leather armor
            if (item.has("color") && isLeatherArmor(material)) {
                String hexColor = item.get("color").asText();
                Color color = parseHexColor(hexColor);
                LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
                if (meta != null) {
                    meta.setColor(color);
                    stack.setItemMeta(meta);
                }
            }
            
            // Apply custom model data (Java-only, Bedrock ignores gracefully)
            if (item.has("customModelData")) {
                int modelData = item.get("customModelData").asInt();
                var meta = stack.getItemMeta();
                if (meta != null) {
                    meta.setCustomModelData(modelData);
                    stack.setItemMeta(meta);
                }
            }
            
            // Equip item
            switch (slot) {
                case "HEAD":
                    equipment.setHelmet(stack);
                    break;
                case "CHEST":
                    equipment.setChestplate(stack);
                    break;
                case "LEGS":
                    equipment.setLeggings(stack);
                    break;
                case "FEET":
                    equipment.setBoots(stack);
                    break;
                case "MAINHAND":
                    equipment.setItemInMainHand(stack);
                    break;
                case "OFFHAND":
                    equipment.setItemInOffHand(stack);
                    break;
            }
            
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid material in attire: " + materialName);
        }
    }
    
    private boolean isLeatherArmor(Material material) {
        return material == Material.LEATHER_HELMET ||
               material == Material.LEATHER_CHESTPLATE ||
               material == Material.LEATHER_LEGGINGS ||
               material == Material.LEATHER_BOOTS;
    }
    
    private Color parseHexColor(String hex) {
        // Remove # if present
        hex = hex.replace("#", "");
        int rgb = Integer.parseInt(hex, 16);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return Color.fromRGB(r, g, b);
    }
}

