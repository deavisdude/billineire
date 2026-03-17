package com.davisodom.villageoverhaul.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

final class CommandFeedback {

    private CommandFeedback() {
    }

    static void send(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }

    static void header(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.GOLD));
    }

    static void info(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.GREEN));
    }

    static void detail(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.GRAY));
    }

    static void warn(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.YELLOW));
    }

    static void error(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.RED));
    }

    static Component line(String label, Object value) {
        return Component.text(label, NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    static Component detailLine(String label, Object value) {
        return Component.text(label, NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    static Component command(String usage, String description) {
        return Component.text(usage, NamedTextColor.GRAY)
            .append(Component.text(" - " + description, NamedTextColor.WHITE));
    }

    static Component bullet(String text) {
        return Component.text("  - ", NamedTextColor.GRAY)
            .append(Component.text(text, NamedTextColor.WHITE));
    }
}