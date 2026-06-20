package heos.folia.commands;

import heos.folia.storage.FoliaBanData;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.storage.FoliaPlayerData;
import heos.folia.utils.FoliaDisconnects;
import heos.folia.utils.FoliaMessages;
import heos.folia.utils.FoliaPlayerAccess;
import heos.folia.utils.FoliaTimeParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class FoliaBanCommands implements CommandExecutor, TabCompleter {
    private static final String DEFAULT_REASON = "You are banned";

    private final FoliaBanData banData;
    private final FoliaNameResolver nameResolver;

    public FoliaBanCommands(FoliaBanData banData, FoliaNameResolver nameResolver) {
        this.banData = banData;
        this.nameResolver = nameResolver;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("heos.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission");
            return true;
        }
        return onSubcommand(sender, command.getName().toLowerCase(), args);
    }

    public boolean onSubcommand(CommandSender sender, String subcommand, String[] args) {
        if (!sender.hasPermission("heos.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission");
            return true;
        }
        return switch (subcommand) {
            case "ban" -> banPlayer(sender, args);
            case "ban-ip" -> banIp(sender, args);
            case "unban" -> unban(sender, args);
            case "unban-ip" -> unbanIp(sender, args);
            case "banlist" -> list(sender);
            default -> false;
        };
    }

    /**
     * Resolve a player name that may include "正版_" or "离线_" prefix.
     * Returns the underlying FoliaPlayerData if found via prefix, or null.
     */
    private ResolvedTarget resolveTarget(String nameArg) {
        // Try prefixed name first
        FoliaPlayerData prefixed = nameResolver.findByPrefixedName(nameArg);
        if (prefixed != null) {
            Player online = Bukkit.getPlayer(prefixed.uuid);
            return new ResolvedTarget(prefixed.username, prefixed.uuid, online);
        }

        // Try as plain name — check if it's ambiguous
        Player online = Bukkit.getPlayerExact(nameArg);
        if (online != null) {
            return new ResolvedTarget(online.getName(), online.getUniqueId(), online);
        }

        // Offline player — just use the name
        return new ResolvedTarget(nameArg, null, null);
    }

    private boolean banPlayer(CommandSender sender, String[] args) {
        if (args.length < 1) {
            return false;
        }
        ParsedBan parsed = parse(args, 1);
        if (parsed.expiryTime == -2L) {
            sender.sendMessage(ChatColor.RED + "Invalid time format. Use: 15s, 3m, 24h, 7d, 1y or -1");
            return true;
        }

        ResolvedTarget target = resolveTarget(args[0]);
        banData.addPlayerBan(target.name, target.uuid, parsed.reason, parsed.expiryTime, sender.getName());

        String displayName = args[0];
        sender.sendMessage(ChatColor.GREEN + "Banned player " + displayName);
        sender.sendMessage(ChatColor.GRAY + "Reason: " + parsed.reason);
        sender.sendMessage(ChatColor.GRAY + "Duration: " + FoliaTimeParser.formatDuration(parsed.expiryTime));

        if (target.online != null) {
            FoliaDisconnects.disconnect(target.online, banMessage(parsed.reason, parsed.expiryTime), "HEOS_BAN");
        }
        return true;
    }

    private boolean banIp(CommandSender sender, String[] args) {
        if (args.length < 1) {
            return false;
        }
        ParsedBan parsed = parse(args, 1);
        if (parsed.expiryTime == -2L) {
            sender.sendMessage(ChatColor.RED + "Invalid time format. Use: 15s, 3m, 24h, 7d, 1y or -1");
            return true;
        }

        String ip = args[0];
        banData.addIpBan(ip, parsed.reason, parsed.expiryTime, sender.getName());
        sender.sendMessage(ChatColor.GREEN + "Banned IP " + ip);
        sender.sendMessage(ChatColor.GRAY + "Reason: " + parsed.reason);
        sender.sendMessage(ChatColor.GRAY + "Duration: " + FoliaTimeParser.formatDuration(parsed.expiryTime));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ip.equals(FoliaPlayerAccess.ip(player))) {
                FoliaDisconnects.disconnect(player, banIpMessage(parsed.reason, parsed.expiryTime), "HEOS_IP_BAN");
            }
        }
        return true;
    }

    private boolean unban(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return false;
        }
        ResolvedTarget target = resolveTarget(args[0]);
        boolean removedPlayer = banData.removePlayerBan(target.name);
        boolean removedIp = banData.removeIpBan(args[0]);
        if (removedPlayer || removedIp) {
            sender.sendMessage(ChatColor.GREEN + "Unbanned " + args[0]);
        } else {
            sender.sendMessage(ChatColor.RED + args[0] + " is not banned");
        }
        return true;
    }

    private boolean unbanIp(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return false;
        }
        if (banData.removeIpBan(args[0])) {
            sender.sendMessage(ChatColor.GREEN + "Unbanned IP " + args[0]);
        } else {
            sender.sendMessage(ChatColor.RED + args[0] + " is not IP banned");
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        banData.removeExpired();
        sender.sendMessage(ChatColor.GRAY + "=================================");
        sender.sendMessage(ChatColor.YELLOW + "Ban List");
        if (banData.playerBans.isEmpty() && banData.ipBans.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No ban records");
            return true;
        }
        if (!banData.playerBans.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Player bans (" + banData.playerBans.size() + "):");
            for (FoliaBanData.BanEntry ban : banData.playerBans) {
                String uuidStr = ban.uuid != null ? " [" + ban.uuid.toString().substring(0, 8) + "]" : "";
                sender.sendMessage(ChatColor.GRAY + "- " + ban.username + uuidStr + " | " + FoliaTimeParser.formatDuration(ban.expiryTime) + " | " + ban.reason);
            }
        }
        if (!banData.ipBans.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "IP bans (" + banData.ipBans.size() + "):");
            for (FoliaBanData.IpBanEntry ban : banData.ipBans) {
                sender.sendMessage(ChatColor.GRAY + "- " + ban.ip + " | " + FoliaTimeParser.formatDuration(ban.expiryTime) + " | " + ban.reason);
            }
        }
        sender.sendMessage(ChatColor.GRAY + "=================================");
        return true;
    }

    private static ParsedBan parse(String[] args, int start) {
        if (args.length <= start) {
            return new ParsedBan(-1L, DEFAULT_REASON);
        }
        long parsedTime = FoliaTimeParser.parse(args[start]);
        if (parsedTime != -2L) {
            return new ParsedBan(parsedTime, join(args, start + 1, DEFAULT_REASON));
        }
        return new ParsedBan(-1L, join(args, start, DEFAULT_REASON));
    }

    private static String join(String[] args, int start, String fallback) {
        if (start >= args.length) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    public static String banMessage(String reason, long expiryTime) {
        return FoliaMessages.banMessage(reason, FoliaTimeParser.formatAbsolute(expiryTime));
    }

    public static String banIpMessage(String reason, long expiryTime) {
        return FoliaMessages.banIpMessage(reason, FoliaTimeParser.formatAbsolute(expiryTime));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("heos.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1 && (command.getName().equalsIgnoreCase("ban") || command.getName().equalsIgnoreCase("unban"))) {
            String prefix = args[0].toLowerCase();
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }

    private record ParsedBan(long expiryTime, String reason) {
    }

    private record ResolvedTarget(String name, UUID uuid, Player online) {
    }
}
