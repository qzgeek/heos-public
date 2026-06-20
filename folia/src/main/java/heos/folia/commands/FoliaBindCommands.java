package heos.folia.commands;

import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaStorage;
import heos.folia.utils.FoliaMessages;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class FoliaBindCommands implements CommandExecutor, TabCompleter {
    private final FoliaAccountBinding binding;
    private final FoliaStorage storage;
    private final FoliaBindUI bindUI;

    public FoliaBindCommands(FoliaAccountBinding binding, FoliaStorage storage, FoliaBindUI bindUI) {
        this.binding = binding;
        this.storage = storage;
        this.bindUI = bindUI;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length == 0) { showHelp(player); return true; }

        switch (args[0].toLowerCase()) {
            case "request" -> request(player, args);
            case "accept"  -> accept(player, args);
            case "deny"    -> deny(player, args);
            case "status"  -> status(player);
            case "manage"  -> bindUI.showChatMenu(player);
            case "gui"     -> bindUI.showChestGui(player);
            case "list"    -> list(sender);
            case "revoke"  -> revoke(sender, args);
            default -> showHelp(player);
        }
        return true;
    }

    private void request(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(ChatColor.RED + FoliaMessages.bindUsageRequest()); return; }
        FoliaAccountBinding.BindResult r = binding.requestBinding(player.getUniqueId(), player.getName(), args[1]);
        player.sendMessage(r.success ? ChatColor.GREEN + r.message : ChatColor.RED + r.message);
    }

    private void accept(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(ChatColor.RED + FoliaMessages.bindUsageAccept()); return; }
        FoliaAccountBinding.BindResult r = binding.acceptBinding(player.getUniqueId(), player.getName(), args[1]);
        player.sendMessage(r.success ? ChatColor.GREEN + r.message : ChatColor.RED + r.message);
    }

    private void deny(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(ChatColor.RED + FoliaMessages.bindUsageDeny()); return; }
        List<FoliaStorage.BindingEntry> pending = storage.getPendingForTarget(player.getName());
        String boundName = args[1];
        for (FoliaStorage.BindingEntry e : pending) {
            if (e.boundName.equalsIgnoreCase(boundName)) {
                storage.revokeBinding(e.id);
                player.sendMessage(ChatColor.GREEN + FoliaMessages.bindDenied(boundName));
                return;
            }
        }
        player.sendMessage(ChatColor.RED + FoliaMessages.bindNoPendingRequest(boundName));
    }

    private void status(Player player) {
        // Pending requests targeting this player
        List<FoliaStorage.BindingEntry> pending = storage.getPendingForTarget(player.getName());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        if (!pending.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "=== 待处理的绑定请求 ===");
            for (FoliaStorage.BindingEntry e : pending) {
                player.sendMessage(ChatColor.WHITE + "来自: " + ChatColor.AQUA + e.boundName
                        + ChatColor.GRAY + " (" + sdf.format(new Date(e.createdAt)) + ")");
                player.sendMessage(ChatColor.GRAY + "  /heos bind accept " + e.boundName + "  同意");
                player.sendMessage(ChatColor.GRAY + "  /heos bind deny " + e.boundName + "   拒绝");
            }
            return;
        }
        // Check if this player sent a pending request
        List<FoliaStorage.BindingEntry> all = storage.listAllBindings();
        for (FoliaStorage.BindingEntry e : all) {
            if (e.isPending() && e.boundUuid.equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + FoliaMessages.bindRequestSent(e.targetName)
                        + " (" + sdf.format(new Date(e.createdAt)) + ")");
                return;
            }
        }
        player.sendMessage(ChatColor.YELLOW + FoliaMessages.bindNoPendingForYou());
    }

    private void list(CommandSender sender) {
        if (!sender.hasPermission("heos.admin")) { sender.sendMessage(ChatColor.RED + "Permission denied."); return; }
        List<FoliaStorage.BindingEntry> all = storage.listAllBindings();
        if (all.isEmpty()) { sender.sendMessage(ChatColor.YELLOW + "No bindings."); return; }
        sender.sendMessage(ChatColor.YELLOW + FoliaMessages.bindHeader());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (FoliaStorage.BindingEntry e : all) {
            String st = e.isActive() ? ChatColor.GREEN + "active" : ChatColor.YELLOW + "pending";
            String tu = e.targetUuid != null ? e.targetUuid.toString().substring(0, 8) : "?";
            String bu = e.boundUuid != null ? e.boundUuid.toString().substring(0, 8) : "?";
            sender.sendMessage(String.format(ChatColor.WHITE + "#%d " + ChatColor.AQUA + "%s" + ChatColor.GRAY + "(%s) → " + ChatColor.AQUA + "%s" + ChatColor.GRAY + "(%s) " + ChatColor.WHITE + "[%s] " + ChatColor.DARK_GRAY + "%s",
                    e.id, e.boundName, bu, e.targetName, tu, st, sdf.format(new Date(e.createdAt))));
        }
    }

    private void revoke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("heos.admin")) { sender.sendMessage(ChatColor.RED + "Permission denied."); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + FoliaMessages.bindUsageRevoke()); return; }
        try {
            String idStr = args[1].startsWith("#") ? args[1].substring(1) : args[1];
            long id = Long.parseLong(idStr);
            sender.sendMessage(storage.revokeBinding(id)
                    ? ChatColor.GREEN + FoliaMessages.bindRevoked(id)
                    : ChatColor.RED + "Binding not found.");
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid binding ID. Use /heos bind list to see IDs.");
        }
    }

    private void showHelp(Player player) {
        player.sendMessage(ChatColor.YELLOW + "=== HEOS 账号绑定 ===");
        player.sendMessage(ChatColor.WHITE + "/heos bind request <被绑定账号>" + ChatColor.GRAY + " - 请求绑定");
        player.sendMessage(ChatColor.WHITE + "/heos bind accept <绑定账号>" + ChatColor.GRAY + " - 同意绑定");
        player.sendMessage(ChatColor.WHITE + "/heos bind deny <绑定账号>" + ChatColor.GRAY + " - 拒绝绑定");
        player.sendMessage(ChatColor.WHITE + "/heos bind status" + ChatColor.GRAY + " - 查看待处理");
        if (player.hasPermission("heos.admin")) {
            player.sendMessage(ChatColor.WHITE + "/heos bind list" + ChatColor.GRAY + " - 所有绑定");
            player.sendMessage(ChatColor.WHITE + "/heos bind revoke <ID>" + ChatColor.GRAY + " - 撤销绑定");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            List<String> r = new ArrayList<>();
            for (String s : List.of("request", "accept", "deny", "status", "manage", "gui", "list", "revoke"))
                if (s.startsWith(p)) r.add(s);
            return r;
        }
        return List.of();
    }
}
