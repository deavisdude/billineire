package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.projects.Project;
import com.davisodom.villageoverhaul.projects.ProjectService;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin commands for managing village projects.
 * 
 * Commands:
 * - /vo project list [villageId] - List all projects (or for specific village)
 * - /vo project status <projectId> - Show detailed project status
 * - /vo project create <villageId> <building> <cost> - Create a new project
 * - /vo project activate <projectId> - Activate a project
 */
public class ProjectCommands implements CommandExecutor, TabCompleter {
    
    private final VillageOverhaulPlugin plugin;
    private final ProjectService projectService;
    private final VillageService villageService;
    private final GenerateCommand generateCommand;
    
    public ProjectCommands(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.projectService = plugin.getProjectService();
        this.villageService = plugin.getVillageService();
        // T066: Pass generation queue to GenerateCommand
        this.generateCommand = new GenerateCommand(plugin, plugin.getGenerationQueue());
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Show help when no subcommand provided
            CommandFeedback.header(sender, "Village Overhaul Commands:");
            CommandFeedback.send(sender, CommandFeedback.command("/vo generate <culture> <name> [seed]", "Generate a village"));
            CommandFeedback.send(sender, CommandFeedback.command("/vo project list [villageId]", "List projects"));
            CommandFeedback.send(sender, CommandFeedback.command("/vo project status <projectId>", "Show project status"));
            CommandFeedback.send(sender, CommandFeedback.command("/vo villager list [villageId]", "List villagers"));
            return true;
        }
        
        String subcommand = args[0].toLowerCase();
        
        switch (subcommand) {
            case "generate":
                return generateCommand.execute(sender, Arrays.copyOfRange(args, 1, args.length));
            case "project":
                return handleProjectCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "villager":
                return handleVillagerCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            default:
                CommandFeedback.error(sender, "Unknown subcommand: " + subcommand);
                CommandFeedback.detail(sender, "Type /vo for help");
                return false;
        }
    }
    
    private boolean handleProjectCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            CommandFeedback.warn(sender, "Project commands:");
            CommandFeedback.detail(sender, "/vo project list [villageId]");
            CommandFeedback.detail(sender, "/vo project status <projectId>");
            CommandFeedback.detail(sender, "/vo project create <villageId> <building> <cost>");
            CommandFeedback.detail(sender, "/vo project activate <projectId>");
            return true;
        }
        
        String action = args[0].toLowerCase();
        
        switch (action) {
            case "list":
                return handleListProjects(sender, args);
            case "status":
                return handleProjectStatus(sender, args);
            case "create":
                return handleCreateProject(sender, args);
            case "activate":
                return handleActivateProject(sender, args);
            default:
                CommandFeedback.error(sender, "Unknown project action: " + action);
                return false;
        }
    }
    
    private boolean handleListProjects(CommandSender sender, String[] args) {
        if (args.length > 1) {
            // List projects for specific village
            try {
                UUID villageId = UUID.fromString(args[1]);
                List<Project> projects = projectService.getVillageProjects(villageId);
                
                CommandFeedback.header(sender, "Projects for village " + villageId + ":");
                if (projects.isEmpty()) {
                    CommandFeedback.detail(sender, "No projects found");
                } else {
                    for (Project p : projects) {
                        CommandFeedback.send(sender, Component.text(p.getBuildingRef(), NamedTextColor.YELLOW)
                            .append(Component.text(" - " + p.getId() + " (" + p.getCompletionPercent() + "%) [" + p.getStatus() + "]",
                                NamedTextColor.GRAY)));
                    }
                }
            } catch (IllegalArgumentException e) {
                CommandFeedback.error(sender, "Invalid village ID");
                return false;
            }
        } else {
            // List all projects
            CommandFeedback.header(sender, "All projects:");
            List<Project> projects = new ArrayList<>(projectService.getAllProjects());
            if (projects.isEmpty()) {
                CommandFeedback.detail(sender, "No projects found");
            } else {
                for (Project p : projects) {
                    CommandFeedback.send(sender, Component.text(p.getBuildingRef(), NamedTextColor.YELLOW)
                        .append(Component.text(" - " + p.getId() + " (" + p.getCompletionPercent() + "%) [" + p.getStatus() + "]",
                            NamedTextColor.GRAY)));
                }
            }
        }
        return true;
    }
    
    private boolean handleProjectStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /vo project status <projectId>");
            return false;
        }
        
        try {
            UUID projectId = UUID.fromString(args[1]);
            var projectOpt = projectService.getProject(projectId);
            
            if (projectOpt.isEmpty()) {
                CommandFeedback.error(sender, "Project not found: " + projectId);
                return false;
            }
            
            Project project = projectOpt.get();
            CommandFeedback.header(sender, "Project Status");
            CommandFeedback.send(sender, CommandFeedback.line("Building: ", project.getBuildingRef()));
            CommandFeedback.send(sender, CommandFeedback.line("Village: ", project.getVillageId()));
            CommandFeedback.send(sender, CommandFeedback.line("Status: ", project.getStatus()));
            CommandFeedback.send(sender, CommandFeedback.line(
                "Progress: ",
                project.getProgressMillz() + " / " + project.getCostMillz() + " Millz (" + project.getCompletionPercent() + "%)"
            ));
            CommandFeedback.send(sender, CommandFeedback.line("Contributors: ", project.getContributors().size()));
            
            if (!project.getUnlockEffects().isEmpty()) {
                CommandFeedback.warn(sender, "Unlock Effects:");
                for (String effect : project.getUnlockEffects()) {
                    CommandFeedback.send(sender, CommandFeedback.bullet(effect));
                }
            }
            
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid project ID");
            return false;
        }
        
        return true;
    }
    
    private boolean handleCreateProject(CommandSender sender, String[] args) {
        if (args.length < 4) {
            CommandFeedback.error(sender, "Usage: /vo project create <villageId|name> <building> <costMillz>");
            return false;
        }
        
        try {
            // Try to parse as UUID first, then fall back to name lookup
            UUID villageId;
            try {
                villageId = UUID.fromString(args[1]);
            } catch (IllegalArgumentException e) {
                // Not a UUID, try to find by name
                String villageName = args[1];
                var village = villageService.getAllVillages().stream()
                        .filter(v -> v.getName().equalsIgnoreCase(villageName))
                        .findFirst()
                        .orElse(null);
                
                if (village == null) {
                    CommandFeedback.error(sender, "Village not found: " + villageName);
                    CommandFeedback.detail(sender, "Use /villages to see all villages");
                    return false;
                }
                villageId = village.getId();
            }
            
            String buildingRef = args[2];
            long costMillz = Long.parseLong(args[3]);
            
            // Verify village exists
            var villageOpt = villageService.getVillage(villageId);
            if (villageOpt.isEmpty()) {
                CommandFeedback.error(sender, "Village not found: " + villageId);
                return false;
            }
            
            List<String> unlockEffects = new ArrayList<>();
            if (args.length > 4) {
                unlockEffects = Arrays.asList(Arrays.copyOfRange(args, 4, args.length));
            }
            
            Project project = projectService.createProject(villageId, buildingRef, costMillz, unlockEffects);
            CommandFeedback.info(sender, "OK Created project for " + villageOpt.get().getName() + ": " + project.getId());
            CommandFeedback.detail(sender, "Use /vo project activate " + project.getId() + " to make it active");
            
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid arguments: " + e.getMessage());
            return false;
        }
        
        return true;
    }
    
    private boolean handleActivateProject(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /vo project activate <projectId>");
            return false;
        }
        
        try {
            UUID projectId = UUID.fromString(args[1]);
            boolean activated = projectService.activateProject(projectId);
            
                if (activated) {
                CommandFeedback.info(sender, "OK Project activated: " + projectId);
            } else {
                CommandFeedback.error(sender, "Failed to activate project (not found or already active)");
            }
            
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid project ID");
            return false;
        }
        
        return true;
    }
    
    /**
     * Handle /vo villager commands
     */
    private boolean handleVillagerCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            CommandFeedback.warn(sender, "Villager Commands:");
            CommandFeedback.send(sender, CommandFeedback.command("/vo villager spawn <cultureId> <profession> [villageId]", "Spawn a custom villager"));
            CommandFeedback.send(sender, CommandFeedback.command("/vo villager list [villageId]", "List custom villagers"));
            CommandFeedback.send(sender, CommandFeedback.command("/vo villager despawn <entityId>", "Despawn a custom villager"));
            return true;
        }
        
        String action = args[0].toLowerCase();
        
        switch (action) {
            case "spawn":
                return handleVillagerSpawn(sender, Arrays.copyOfRange(args, 1, args.length));
            case "list":
                return handleVillagerList(sender, Arrays.copyOfRange(args, 1, args.length));
            case "despawn":
                return handleVillagerDespawn(sender, Arrays.copyOfRange(args, 1, args.length));
            default:
                CommandFeedback.error(sender, "Unknown villager command: " + action);
                return false;
        }
    }
    
    private boolean handleVillagerSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            CommandFeedback.error(sender, "This command must be run by a player");
            return true;
        }
        
        if (args.length < 2) {
            CommandFeedback.error(sender, "Usage: /vo villager spawn <cultureId> <profession> [villageId]");
            return true;
        }
        
        Player player = (Player) sender;
        String cultureId = args[0];
        String profession = args[1];
        UUID villageId = null;
        
        // Find nearest village or use specified village
        if (args.length >= 3) {
            try {
                villageId = UUID.fromString(args[2]);
            } catch (IllegalArgumentException e) {
                // Try by name
                Village village = villageService.findVillageByName(args[2]);
                if (village != null) {
                    villageId = village.getId();
                } else {
                    CommandFeedback.error(sender, "Village not found: " + args[2]);
                    return true;
                }
            }
        } else {
            // Find nearest village
            Village nearest = villageService.findNearestVillage(player.getLocation());
            if (nearest != null) {
                villageId = nearest.getId();
            } else {
                CommandFeedback.error(sender, "No villages found. Specify a village ID or create one first.");
                return true;
            }
        }
        
        String definitionId = cultureId + "_" + profession;
        var npcService = plugin.getCustomVillagerService();
        var appearanceAdapter = plugin.getVillagerAppearanceAdapter();
        
        var customVillager = npcService.spawnVillager(
            definitionId,
            cultureId,
            profession,
            villageId,
            player.getLocation()
        );
        
        if (customVillager != null) {
            // Apply appearance
            var entity = player.getServer().getEntity(customVillager.getEntityId());
            if (entity != null) {
                appearanceAdapter.applyAppearance(entity, definitionId);
            }
            
            CommandFeedback.info(sender, "OK Spawned " + definitionId + " at your location");
            CommandFeedback.send(sender, CommandFeedback.detailLine("Entity ID: ", customVillager.getEntityId()));
        } else {
            CommandFeedback.error(sender, "Failed to spawn villager (capacity reached or error)");
        }
        
        return true;
    }
    
    private boolean handleVillagerList(CommandSender sender, String[] args) {
        var npcService = plugin.getCustomVillagerService();
        
        if (args.length > 0) {
            // List for specific village
            UUID villageId;
            try {
                villageId = UUID.fromString(args[0]);
            } catch (IllegalArgumentException e) {
                Village village = villageService.findVillageByName(args[0]);
                if (village == null) {
                    CommandFeedback.error(sender, "Village not found: " + args[0]);
                    return true;
                }
                villageId = village.getId();
            }
            
            List<com.davisodom.villageoverhaul.npc.CustomVillager> villagers = npcService.getVillagersByVillageId(villageId);
            CommandFeedback.warn(sender, "Custom Villagers in village " + villageId + ": " + villagers.size());
            int capacity = npcService.getVillagerCapacity(villageId);
            if (capacity == Integer.MAX_VALUE) {
                CommandFeedback.detail(sender, "Capacity: no cap (derived from structures)");
            } else {
                CommandFeedback.detail(sender, "Capacity: " + capacity + " (derived from structures)");
            }
            for (com.davisodom.villageoverhaul.npc.CustomVillager villager : villagers) {
                CommandFeedback.detail(sender, villager.getDefinitionId() + " (entity: " + villager.getEntityId() + ")");
            }
        } else {
            // List all
            java.util.Collection<com.davisodom.villageoverhaul.npc.CustomVillager> allVillagers = npcService.getAllVillagers();
            CommandFeedback.warn(sender, "Total custom villagers: " + allVillagers.size());
            double ratio = npcService.getVillagerCapacityPerStructure();
            if (ratio <= 0) {
                CommandFeedback.detail(sender, "Capacity: no cap (villagerCapacityPerStructure=" + ratio + ")");
            } else {
                CommandFeedback.detail(sender, "Capacity ratio: " + ratio + " villagers per structure");
            }
            
            for (com.davisodom.villageoverhaul.npc.CustomVillager villager : allVillagers) {
                CommandFeedback.detail(sender, villager.getDefinitionId() + " @ village " + villager.getVillageId());
            }
        }
        
        return true;
    }
    
    private boolean handleVillagerDespawn(CommandSender sender, String[] args) {
        if (args.length < 1) {
            CommandFeedback.error(sender, "Usage: /vo villager despawn <entityId>");
            return true;
        }
        
        UUID entityId;
        try {
            entityId = UUID.fromString(args[0]);
        } catch (IllegalArgumentException e) {
            CommandFeedback.error(sender, "Invalid entity ID");
            return true;
        }
        
        var npcService = plugin.getCustomVillagerService();
        boolean success = npcService.despawnVillager(entityId);
        
        if (success) {
            CommandFeedback.info(sender, "OK Despawned custom villager");
        } else {
            CommandFeedback.error(sender, "Villager not found");
        }
        
        return true;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("generate");
            completions.add("project");
            completions.add("villager");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("generate")) {
            // Suggest available culture IDs
            completions.addAll(plugin.getCultureService().all().stream()
                    .map(c -> c.getId())
                    .collect(Collectors.toList()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("project")) {
            completions.addAll(Arrays.asList("list", "status", "create", "activate"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("villager")) {
            completions.addAll(Arrays.asList("spawn", "list", "despawn"));
        }
        
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }
}

