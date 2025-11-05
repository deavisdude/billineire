package com.example.villageoverhaul.commands;

import com.example.villageoverhaul.VillageOverhaulPlugin;
import com.example.villageoverhaul.projects.Project;
import com.example.villageoverhaul.projects.ProjectService;
import com.example.villageoverhaul.villages.Village;
import com.example.villageoverhaul.villages.VillageService;
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
    
    public ProjectCommands(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.projectService = plugin.getProjectService();
        this.villageService = plugin.getVillageService();
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false; // Show usage
        }
        
        String subcommand = args[0].toLowerCase();
        
        switch (subcommand) {
            case "project":
                return handleProjectCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            default:
                sender.sendMessage("§cUnknown subcommand: " + subcommand);
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
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("project");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("project")) {
            completions.addAll(Arrays.asList("list", "status", "create", "activate"));
        }
        
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }
}
