package com.davisodom.villageoverhaul.commands;

import com.davisodom.villageoverhaul.VillageOverhaulPlugin;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Player-facing commands for discovering and locating villages.
 * 
 * Commands:
 * - /villages - List all villages with distances
 * - /village locate <name> - Show location and distance to a village
 * - /village nearest - Find the nearest village
 * 
 * Default keybind: V key (configured via client-side)
 */
public class VillageCommands implements CommandExecutor, TabCompleter {
    
    private final VillageOverhaulPlugin plugin;
    private final VillageService villageService;
    
    public VillageCommands(VillageOverhaulPlugin plugin) {
        this.plugin = plugin;
        this.villageService = plugin.getVillageService();
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED));
            return true;
        }
        
        Player player = (Player) sender;
        
        // Handle /villages (list all)
        if (command.getName().equalsIgnoreCase("villages")) {
            return handleListVillages(player);
        }
        
        // Handle /village <subcommand>
        if (args.length == 0) {
            return handleListVillages(player);
        }
        
        String subcommand = args[0].toLowerCase();
        
        switch (subcommand) {
            case "list":
                return handleListVillages(player);
            case "locate":
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /village locate <name>", NamedTextColor.RED));
                    return false;
                }
                String villageName = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                return handleLocateVillage(player, villageName);
            case "nearest":
                return handleNearestVillage(player);
            default:
                player.sendMessage(Component.text("Unknown subcommand. Try: list, locate, nearest", NamedTextColor.RED));
                return false;
        }
    }
    
    private boolean handleListVillages(Player player) {
        List<Village> villages = new ArrayList<>(villageService.getAllVillages());
        
        if (villages.isEmpty()) {
            player.sendMessage(Component.text("No villages have been discovered yet.", NamedTextColor.GRAY));
            return true;
        }
        
        player.sendMessage(Component.text("═══════════ Villages ═══════════", NamedTextColor.GOLD));
        
        String playerWorld = player.getWorld().getName();
        int playerX = player.getLocation().getBlockX();
        int playerZ = player.getLocation().getBlockZ();
        
        // Filter to same world and sort by distance
        villages.stream()
                .filter(v -> v.getWorldName().equals(playerWorld))
                .sorted((v1, v2) -> {
                    int d1 = getDistance2D(playerX, playerZ, v1.getX(), v1.getZ());
                    int d2 = getDistance2D(playerX, playerZ, v2.getX(), v2.getZ());
                    return Integer.compare(d1, d2);
                })
                .forEach(village -> {
                    int distance = getDistance2D(playerX, playerZ, village.getX(), village.getZ());
                    String direction = getDirection(playerX, playerZ, village.getX(), village.getZ());
                    
                    player.sendMessage(Component.text(village.getName(), NamedTextColor.YELLOW)
                            .append(Component.text(" (" + village.getCultureId() + " culture)", NamedTextColor.GRAY)));
                    player.sendMessage(Component.text("  → ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(distance + " blocks " + direction, NamedTextColor.GRAY))
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text("(" + village.getX() + ", " + village.getY() + ", " + village.getZ() + ")", NamedTextColor.GRAY)));
                });
        
        player.sendMessage(Component.text("Tip: Use ", NamedTextColor.DARK_GRAY)
                .append(Component.text("/village locate <name>", NamedTextColor.GRAY))
                .append(Component.text(" for waypoint", NamedTextColor.DARK_GRAY)));
        
        return true;
    }
    
    private boolean handleLocateVillage(Player player, String villageName) {
        Village village = findVillageByName(villageName, player.getWorld().getName());
        
        if (village == null) {
            player.sendMessage(Component.text("Village not found: " + villageName, NamedTextColor.RED));
            player.sendMessage(Component.text("Use ", NamedTextColor.GRAY)
                    .append(Component.text("/villages", NamedTextColor.YELLOW))
                    .append(Component.text(" to see all villages", NamedTextColor.GRAY)));
            return false;
        }
        
        int playerX = player.getLocation().getBlockX();
        int playerZ = player.getLocation().getBlockZ();
        int distance = getDistance2D(playerX, playerZ, village.getX(), village.getZ());
        String direction = getDirection(playerX, playerZ, village.getX(), village.getZ());
        
        player.sendMessage(Component.text("═══ " + village.getName() + " ═══", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Culture: ", NamedTextColor.YELLOW)
                .append(Component.text(village.getCultureId(), NamedTextColor.GRAY)));
        player.sendMessage(Component.text("Location: ", NamedTextColor.YELLOW)
                .append(Component.text(village.getX() + ", " + village.getY() + ", " + village.getZ(), NamedTextColor.GRAY)));
        player.sendMessage(Component.text("Distance: ", NamedTextColor.YELLOW)
                .append(Component.text(distance + " blocks " + direction, NamedTextColor.GRAY)));
        player.sendMessage(Component.text("Wealth: ", NamedTextColor.YELLOW)
                .append(Component.text(formatMillz(village.getWealthMillz()), NamedTextColor.GRAY)));
        
        // Show active projects
        var activeProjects = plugin.getProjectService().getActiveVillageProjects(village.getId());
        if (!activeProjects.isEmpty()) {
            player.sendMessage(Component.text("Active Projects: ", NamedTextColor.YELLOW)
                    .append(Component.text(String.valueOf(activeProjects.size()), NamedTextColor.GRAY)));
            activeProjects.forEach(project -> {
                player.sendMessage(Component.text("  → ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(project.getBuildingRef(), NamedTextColor.GRAY))
                        .append(Component.text(" (" + project.getCompletionPercent() + "% complete)", NamedTextColor.DARK_GRAY)));
            });
        }
        
        return true;
    }
    
    private boolean handleNearestVillage(Player player) {
        String playerWorld = player.getWorld().getName();
        int playerX = player.getLocation().getBlockX();
        int playerZ = player.getLocation().getBlockZ();
        
        Village nearest = villageService.getAllVillages().stream()
                .filter(v -> v.getWorldName().equals(playerWorld))
                .min((v1, v2) -> {
                    int d1 = getDistance2D(playerX, playerZ, v1.getX(), v1.getZ());
                    int d2 = getDistance2D(playerX, playerZ, v2.getX(), v2.getZ());
                    return Integer.compare(d1, d2);
                })
                .orElse(null);
        
        if (nearest == null) {
            player.sendMessage(Component.text("No villages found in this world.", NamedTextColor.GRAY));
            return true;
        }
        
        return handleLocateVillage(player, nearest.getName());
    }
    
    private Village findVillageByName(String name, String worldName) {
        return villageService.getAllVillages().stream()
                .filter(v -> v.getWorldName().equals(worldName))
                .filter(v -> v.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
    
    private int getDistance2D(int x1, int z1, int x2, int z2) {
        int dx = x2 - x1;
        int dz = z2 - z1;
        return (int) Math.sqrt(dx * dx + dz * dz);
    }
    
    private String getDirection(int fromX, int fromZ, int toX, int toZ) {
        int dx = toX - fromX;
        int dz = toZ - fromZ;
        
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        angle = (angle + 360) % 360;
        
        if (angle < 22.5 || angle >= 337.5) return "E";
        if (angle < 67.5) return "SE";
        if (angle < 112.5) return "S";
        if (angle < 157.5) return "SW";
        if (angle < 202.5) return "W";
        if (angle < 247.5) return "NW";
        if (angle < 292.5) return "N";
        return "NE";
    }
    
    private String formatMillz(long millz) {
        if (millz == 0) return "0 Millz";
        
        long trills = millz / 10000;
        long billz = (millz % 10000) / 100;
        long remaining = millz % 100;
        
        List<String> parts = new ArrayList<>();
        if (trills > 0) parts.add(trills + " Trills");
        if (billz > 0) parts.add(billz + " Billz");
        if (remaining > 0) parts.add(remaining + " Millz");
        
        return String.join(", ", parts);
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (command.getName().equalsIgnoreCase("village")) {
            if (args.length == 1) {
                completions.add("list");
                completions.add("locate");
                completions.add("nearest");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("locate")) {
                // Suggest village names
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    completions.addAll(villageService.getAllVillages().stream()
                            .filter(v -> v.getWorldName().equals(player.getWorld().getName()))
                            .map(Village::getName)
                            .collect(Collectors.toList()));
                }
            }
        }
        
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }
}
