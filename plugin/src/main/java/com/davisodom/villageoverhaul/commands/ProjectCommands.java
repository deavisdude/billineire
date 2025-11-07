package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
import com.davisodom.villageoverhaul.projects.Project;
import com.davisodom.villageoverhaul.projects.ProjectService;
import com.davisodom.villageoverhaul.villages.Village;
import com.davisodom.villageoverhaul.villages.VillageService;
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
        this.generateCommand = new GenerateCommand(plugin);
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Show help when no subcommand provided
            sender.sendMessage("§6Village Overhaul Commands:");
            sender.sendMessage("  §7/vo generate <culture> <name> [seed] §f- Generate a village");
            sender.sendMessage("  §7/vo project list [villageId] §f- List projects");
            sender.sendMessage("  §7/vo project status <projectId> §f- Show project status");
            sender.sendMessage("  §7/vo villager list [villageId] §f- List villagers");
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
                sender.sendMessage("§cUnknown subcommand: " + subcommand);
                sender.sendMessage("§7Type /vo for help");
                return false;
        }
    }
    
    private boolean handleProjectCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eProject commands:");
            sender.sendMessage("  §7/vo project list [villageId]");
            sender.sendMessage("  §7/vo project status <projectId>");
            sender.sendMessage("  §7/vo project create <villageId> <building> <cost>");
            sender.sendMessage("  §7/vo project activate <projectId>");
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
                sender.sendMessage("§cUnknown project action: " + action);
                return false;
        }
    }
    
    private boolean handleListProjects(CommandSender sender, String[] args) {
        if (args.length > 1) {
            // List projects for specific village
            try {
                UUID villageId = UUID.fromString(args[1]);
                List<Project> projects = projectService.getVillageProjects(villageId);
                
                sender.sendMessage("§6Projects for village " + villageId + ":");
                if (projects.isEmpty()) {
                    sender.sendMessage("  §7No projects found");
                } else {
                    for (Project p : projects) {
                        sender.sendMessage(String.format("  §e%s §7- %s §7(%d%%) [%s]",
                                p.getBuildingRef(), p.getId(), p.getCompletionPercent(), p.getStatus()));
                    }
                }
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid village ID");
                return false;
            }
        } else {
            // List all projects
            sender.sendMessage("§6All projects:");
            List<Project> projects = new ArrayList<>(projectService.getAllProjects());
            if (projects.isEmpty()) {
                sender.sendMessage("  §7No projects found");
            } else {
                for (Project p : projects) {
                    sender.sendMessage(String.format("  §e%s §7- %s §7(%d%%) [%s]",
                            p.getBuildingRef(), p.getId(), p.getCompletionPercent(), p.getStatus()));
                }
            }
        }
        return true;
    }
    
    private boolean handleProjectStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vo project status <projectId>");
            return false;
        }
        
        try {
            UUID projectId = UUID.fromString(args[1]);
            var projectOpt = projectService.getProject(projectId);
            
            if (projectOpt.isEmpty()) {
                sender.sendMessage("§cProject not found: " + projectId);
                return false;
            }
            
            Project project = projectOpt.get();
            sender.sendMessage("§6═══ Project Status ═══");
            sender.sendMessage("§eBuilding: §7" + project.getBuildingRef());
            sender.sendMessage("§eVillage: §7" + project.getVillageId());
            sender.sendMessage("§eStatus: §7" + project.getStatus());
            sender.sendMessage("§eProgress: §7" + project.getProgressMillz() + " / " + 
                    project.getCostMillz() + " Millz (" + project.getCompletionPercent() + "%)");
            sender.sendMessage("§eContributors: §7" + project.getContributors().size());
            
            if (!project.getUnlockEffects().isEmpty()) {
                sender.sendMessage("§eUnlock Effects:");
                for (String effect : project.getUnlockEffects()) {
                    sender.sendMessage("  §7- " + effect);
                }
            }
            
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid project ID");
            return false;
        }
        
        return true;
    }
    
    private boolean handleCreateProject(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /vo project create <villageId|name> <building> <costMillz>");
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
                    sender.sendMessage("§cVillage not found: " + villageName);
                    sender.sendMessage("§7Use §e/villages §7to see all villages");
                    return false;
                }
                villageId = village.getId();
            }
            
            String buildingRef = args[2];
            long costMillz = Long.parseLong(args[3]);
            
            // Verify village exists
            var villageOpt = villageService.getVillage(villageId);
            if (villageOpt.isEmpty()) {
                sender.sendMessage("§cVillage not found: " + villageId);
                return false;
            }
            
            List<String> unlockEffects = new ArrayList<>();
            if (args.length > 4) {
                unlockEffects = Arrays.asList(Arrays.copyOfRange(args, 4, args.length));
            }
            
            Project project = projectService.createProject(villageId, buildingRef, costMillz, unlockEffects);
            sender.sendMessage("§a✓ Created project for " + villageOpt.get().getName() + ": " + project.getId());
            sender.sendMessage("§7Use §e/vo project activate " + project.getId() + " §7to make it active");
            
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid arguments: " + e.getMessage());
            return false;
        }
        
        return true;
    }
    
    private boolean handleActivateProject(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vo project activate <projectId>");
            return false;
        }
        
        try {
            UUID projectId = UUID.fromString(args[1]);
            boolean activated = projectService.activateProject(projectId);
            
            if (activated) {
                sender.sendMessage("§a✓ Project activated: " + projectId);
            } else {
                sender.sendMessage("§cFailed to activate project (not found or already active)");
            }
            
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid project ID");
            return false;
        }
        
        return true;
    }
    
    /**
     * Handle /vo villager commands
     */
    private boolean handleVillagerCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eVillager Commands:");
            sender.sendMessage("§7/vo villager spawn <cultureId> <profession> [villageId] - Spawn a custom villager");
            sender.sendMessage("§7/vo villager list [villageId] - List custom villagers");
            sender.sendMessage("§7/vo villager despawn <entityId> - Despawn a custom villager");
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
                sender.sendMessage("§cUnknown villager command: " + action);
                return false;
        }
    }
    
    private boolean handleVillagerSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command must be run by a player");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vo villager spawn <cultureId> <profession> [villageId]");
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
                    sender.sendMessage("§cVillage not found: " + args[2]);
                    return true;
                }
            }
        } else {
            // Find nearest village
            Village nearest = villageService.findNearestVillage(player.getLocation());
            if (nearest != null) {
                villageId = nearest.getId();
            } else {
                sender.sendMessage("§cNo villages found. Specify a village ID or create one first.");
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
            
            sender.sendMessage("§a✓ Spawned " + definitionId + " at your location");
            sender.sendMessage("§7Entity ID: " + customVillager.getEntityId());
        } else {
            sender.sendMessage("§cFailed to spawn villager (village cap reached or error)");
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
                    sender.sendMessage("§cVillage not found: " + args[0]);
                    return true;
                }
                villageId = village.getId();
            }
            
            List<com.davisodom.villageoverhaul.npc.CustomVillager> villagers = npcService.getVillagersByVillageId(villageId);
            sender.sendMessage("§eCustom Villagers in village " + villageId + ": " + villagers.size());
            for (com.davisodom.villageoverhaul.npc.CustomVillager villager : villagers) {
                sender.sendMessage("  §7" + villager.getDefinitionId() + " (entity: " + villager.getEntityId() + ")");
            }
        } else {
            // List all
            java.util.Collection<com.davisodom.villageoverhaul.npc.CustomVillager> allVillagers = npcService.getAllVillagers();
            sender.sendMessage("§eTotal custom villagers: " + allVillagers.size());
            sender.sendMessage("§7Per-village cap: " + npcService.getMaxVillagersPerVillage());
            
            for (com.davisodom.villageoverhaul.npc.CustomVillager villager : allVillagers) {
                sender.sendMessage("  §7" + villager.getDefinitionId() + " @ village " + villager.getVillageId());
            }
        }
        
        return true;
    }
    
    private boolean handleVillagerDespawn(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /vo villager despawn <entityId>");
            return true;
        }
        
        UUID entityId;
        try {
            entityId = UUID.fromString(args[0]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid entity ID");
            return true;
        }
        
        var npcService = plugin.getCustomVillagerService();
        boolean success = npcService.despawnVillager(entityId);
        
        if (success) {
            sender.sendMessage("§a✓ Despawned custom villager");
        } else {
            sender.sendMessage("§cVillager not found");
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

