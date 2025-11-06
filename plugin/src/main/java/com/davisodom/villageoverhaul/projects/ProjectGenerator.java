package com.davisodom.villageoverhaul.projects;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.villages.Village;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Generates initial projects for villages when they spawn.
 * 
 * This ensures villages always have progression content without requiring
 * admin intervention. Projects are culture-specific and tier-appropriate.
 */
public class ProjectGenerator {
    
    private final VillageOverhaulPlugin plugin;
    private final Logger logger;
    private final ProjectService projectService;
    private final Random random;
    
    public ProjectGenerator(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.projectService = plugin.getProjectService();
        this.random = new Random();
    }
    
    /**
     * Generate and activate initial projects for a newly spawned village
     * 
     * @param village The village to generate projects for
     */
    public void generateInitialProjects(Village village) {
        String cultureId = village.getCultureId();
        
        logger.info("Generating initial projects for village: " + village.getName() + " (" + cultureId + ")");
        
        // Generate tier 1 projects (basic infrastructure)
        List<ProjectTemplate> tier1Projects = getTier1Projects(cultureId);
        
        for (ProjectTemplate template : tier1Projects) {
            Project project = projectService.createProject(
                    village.getId(),
                    template.buildingRef,
                    template.costMillz,
                    template.unlockEffects
            );
            
            // Auto-activate the first project
            if (tier1Projects.indexOf(template) == 0) {
                projectService.activateProject(project.getId());
                logger.info("  → Activated initial project: " + template.buildingRef);
            } else {
                logger.info("  → Created project (pending): " + template.buildingRef);
            }
        }
        
        logger.info("✓ Generated " + tier1Projects.size() + " initial projects for " + village.getName());
    }
    
    /**
     * Get tier 1 (starter) project templates for a culture
     */
    private List<ProjectTemplate> getTier1Projects(String cultureId) {
        return switch (cultureId.toLowerCase()) {
            case "roman" -> Arrays.asList(
                    new ProjectTemplate(
                            "forum_foundation",
                            5000L, // 50 Billz
                            Arrays.asList("trade_slots:+1", "profession:trader")
                    ),
                    new ProjectTemplate(
                            "villa_upgrade_tier_1",
                            8000L, // 80 Billz
                            Arrays.asList("housing:+2", "population:+5")
                    ),
                    new ProjectTemplate(
                            "bathhouse_foundation",
                            12000L, // 120 Billz
                            Arrays.asList("health:+1", "happiness:+10")
                    )
            );
            case "viking" -> Arrays.asList(
                    new ProjectTemplate(
                            "longhouse_expansion",
                            6000L,
                            Arrays.asList("housing:+3", "defense:+1")
                    ),
                    new ProjectTemplate(
                            "forge_foundation",
                            9000L,
                            Arrays.asList("profession:blacksmith", "trade_slots:+1")
                    )
            );
            default -> Arrays.asList(
                    new ProjectTemplate(
                            "town_hall_foundation",
                            5000L,
                            Arrays.asList("trade_slots:+1")
                    ),
                    new ProjectTemplate(
                            "market_stall",
                            7000L,
                            Arrays.asList("profession:trader", "trade_slots:+1")
                    ),
                    new ProjectTemplate(
                            "workshop_tier_1",
                            10000L,
                            Arrays.asList("profession:craftsman", "production:+1")
                    )
            );
        };
    }
    
    /**
     * Check if a village should generate new projects (called periodically)
     * 
     * Generates new projects when:
     * - All active projects are complete
     * - Village has reached wealth/population thresholds
     */
    public void checkAndGenerateProjects(Village village) {
        var activeProjects = projectService.getActiveVillageProjects(village.getId());
        
        // If no active projects, activate a pending one or generate tier 2
        if (activeProjects.isEmpty()) {
            var allProjects = projectService.getVillageProjects(village.getId());
            var pendingProjects = allProjects.stream()
                    .filter(p -> p.getStatus() == Project.Status.PENDING)
                    .toList();
            
            if (!pendingProjects.isEmpty()) {
                // Activate next pending project
                Project next = pendingProjects.get(0);
                projectService.activateProject(next.getId());
                logger.info("Auto-activated next project for " + village.getName() + ": " + next.getBuildingRef());
            } else if (allProjects.stream().allMatch(p -> p.getStatus() == Project.Status.COMPLETE)) {
                // All projects complete - generate next tier
                generateNextTierProjects(village);
            }
        }
    }
    
    /**
     * Generate next tier of projects based on village progression
     */
    private void generateNextTierProjects(Village village) {
        int completedCount = projectService.getVillageProjects(village.getId()).size();
        
        // Simple tier progression for MVP
        if (completedCount >= 3 && completedCount < 6) {
            logger.info("Generating tier 2 projects for " + village.getName());
            generateTier2Projects(village);
        } else if (completedCount >= 6) {
            logger.info("Village " + village.getName() + " has reached max tier (for MVP)");
        }
    }
    
    private void generateTier2Projects(Village village) {
        String cultureId = village.getCultureId();
        
        List<ProjectTemplate> tier2 = switch (cultureId.toLowerCase()) {
            case "roman" -> Arrays.asList(
                    new ProjectTemplate(
                            "forum_expansion",
                            15000L,
                            Arrays.asList("trade_slots:+2", "reputation:+50")
                    ),
                    new ProjectTemplate(
                            "aqueduct_section",
                            20000L,
                            Arrays.asList("water:+1", "health:+2", "population:+10")
                    )
            );
            default -> Arrays.asList(
                    new ProjectTemplate(
                            "town_hall_upgrade",
                            15000L,
                            Arrays.asList("trade_slots:+2", "defense:+1")
                    )
            );
        };
        
        for (ProjectTemplate template : tier2) {
            Project project = projectService.createProject(
                    village.getId(),
                    template.buildingRef,
                    template.costMillz,
                    template.unlockEffects
            );
            
            if (tier2.indexOf(template) == 0) {
                projectService.activateProject(project.getId());
            }
        }
    }
    
    /**
     * Template for project generation
     */
    private static class ProjectTemplate {
        final String buildingRef;
        final long costMillz;
        final List<String> unlockEffects;
        
        ProjectTemplate(String buildingRef, long costMillz, List<String> unlockEffects) {
            this.buildingRef = buildingRef;
            this.costMillz = costMillz;
            this.unlockEffects = new ArrayList<>(unlockEffects);
        }
    }
}

