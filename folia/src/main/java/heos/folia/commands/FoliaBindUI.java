package heos.folia.commands;

import heos.folia.storage.FoliaStorage;
import heos.folia.utils.FoliaMessages;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Dual-interface binding management: chat-based interactive menu + chest GUI.
 *
 * /heos bind manage   → chat interactive (clickable buttons)
 * /heos bind gui      → chest inventory GUI
 */
public final class FoliaBindUI implements Listener {
    private final FoliaStorage storage;
    private final Plugin plugin;
    private final Map<UUID, Integer> guiPages = new HashMap<>();

    public FoliaBindUI(FoliaStorage storage, Plugin plugin) {
        this.storage = storage;
        this.plugin = plugin;
    }

    // ============================
    //  Chat interactive menu
    // ============================

    public void showChatMenu(Player player) {
        List<FoliaStorage.BindingEntry> bindings = storage.listAllBindings();
        if (bindings.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "当前没有任何绑定记录。");
            return;
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔══════════ HEOS 绑定管理 ══════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "共 " + bindings.size() + " 条绑定记录" + ChatColor.GOLD + "                      ║");

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
        int count = 0;
        for (FoliaStorage.BindingEntry e : bindings) {
            if (count >= 8) {
                player.spigot().sendMessage(ellipsisRow());
                break;
            }
            count++;
            player.spigot().sendMessage(bindingRow(e, sdf));
        }

        // Action buttons
        player.sendMessage("");
        TextComponent guiBtn = new TextComponent(ChatColor.AQUA + "[打开 GUI 管理界面]");
        guiBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/heos bind gui"));
        guiBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text("点击打开箱子界面管理绑定")));
        player.spigot().sendMessage(guiBtn);

        TextComponent listBtn = new TextComponent(ChatColor.GRAY + "[查看列表]");
        listBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/heos bind list"));
        player.spigot().sendMessage(listBtn);

        player.sendMessage(ChatColor.GOLD + "╚══════════════════════════════════╝");
    }

    private TextComponent bindingRow(FoliaStorage.BindingEntry e, SimpleDateFormat sdf) {
        String color = e.isActive() ? ChatColor.GREEN.toString() : ChatColor.YELLOW.toString();
        String status = e.isActive() ? "active" : "pending";

        // Row: [Revoke] name → target [status]
        ComponentBuilder builder = new ComponentBuilder("");

        // Revoke button
        TextComponent revokeBtn = new TextComponent(ChatColor.RED + " [✕] ");
        revokeBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/heos bind revoke " + e.id));
        revokeBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text("点击撤销这条绑定")));

        // Info
        String tu = e.targetUuid != null ? e.targetUuid.toString().substring(0, 8) : "?";
        String bu = e.boundUuid != null ? e.boundUuid.toString().substring(0, 8) : "?";

        TextComponent row = new TextComponent(
                ChatColor.GRAY + "#" + e.id + " " + revokeBtn.getText() +
                color + e.boundName + ChatColor.DARK_GRAY + "(" + bu + ")" +
                ChatColor.GRAY + " → " +
                color + e.targetName + ChatColor.DARK_GRAY + "(" + tu + ")" +
                ChatColor.GRAY + " [" + status + "] " +
                ChatColor.DARK_GRAY + sdf.format(new Date(e.createdAt))
        );
        // Rebuild with click event
        row = new TextComponent("");
        row.addExtra(revokeBtn);
        row.addExtra(new TextComponent(
                color + e.boundName + ChatColor.DARK_GRAY + "(" + bu + ")" +
                ChatColor.GRAY + " → " +
                color + e.targetName + ChatColor.DARK_GRAY + "(" + tu + ")" +
                ChatColor.GRAY + " [" + status + "] " +
                ChatColor.DARK_GRAY + sdf.format(new Date(e.createdAt))
        ));
        return row;
    }

    private TextComponent ellipsisRow() {
        TextComponent btn = new TextComponent(ChatColor.GRAY + "... 还有更多，使用 ");
        TextComponent gui = new TextComponent(ChatColor.AQUA + "[GUI]");
        gui.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/heos bind gui"));
        gui.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text("打开完整 GUI 界面")));
        btn.addExtra(gui);
        btn.addExtra(ChatColor.GRAY + " 查看全部");
        return btn;
    }

    // ============================
    //  Chest GUI
    // ============================

    private static final String GUI_TITLE = "HEOS 绑定管理";
    private static final int ROWS = 6;

    public void showChestGui(Player player) {
        List<FoliaStorage.BindingEntry> bindings = storage.listAllBindings();
        int page = guiPages.getOrDefault(player.getUniqueId(), 0);
        showChestGuiPage(player, bindings, page);
    }

    private void showChestGuiPage(Player player, List<FoliaStorage.BindingEntry> bindings, int page) {
        int itemsPerPage = (ROWS - 1) * 9; // 5 rows for items, 1 for navigation
        int maxPage = Math.max(0, (bindings.size() - 1) / itemsPerPage);
        page = Math.max(0, Math.min(page, maxPage));
        guiPages.put(player.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, ROWS * 9, GUI_TITLE + " (" + (page + 1) + "/" + (maxPage + 1) + ")");

        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, bindings.size());
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");

        for (int i = start; i < end; i++) {
            FoliaStorage.BindingEntry e = bindings.get(i);

            Material mat = e.isActive() ? Material.LIME_WOOL : Material.YELLOW_WOOL;
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((e.isActive() ? ChatColor.GREEN : ChatColor.YELLOW) + "#" + e.id + " " + e.boundName + " → " + e.targetName);

            String tu = e.targetUuid != null ? e.targetUuid.toString().substring(0, 12) : "pending...";
            String bu = e.boundUuid != null ? e.boundUuid.toString().substring(0, 12) : "?";
            meta.setLore(List.of(
                    ChatColor.GRAY + "绑定账号: " + ChatColor.WHITE + e.boundName + " (" + bu + ")",
                    ChatColor.GRAY + "目标账号: " + ChatColor.WHITE + e.targetName + " (" + tu + ")",
                    ChatColor.GRAY + "状态: " + (e.isActive() ? ChatColor.GREEN + "已激活" : ChatColor.YELLOW + "待确认"),
                    ChatColor.GRAY + "创建: " + sdf.format(new Date(e.createdAt)),
                    e.acceptedAt != null ? ChatColor.GRAY + "确认: " + sdf.format(new Date(e.acceptedAt)) : "",
                    "",
                    ChatColor.RED + "点击撤销此绑定"
            ));
            item.setItemMeta(meta);
            inv.setItem(i - start, item);
        }

        // Navigation row
        int navRow = (ROWS - 1) * 9;
        if (page > 0) {
            inv.setItem(navRow, navItem(Material.ARROW, ChatColor.GREEN + "◀ 上一页", "prev"));
        }
        inv.setItem(navRow + 4, infoItem(bindings.size()));
        if (page < maxPage) {
            inv.setItem(navRow + 8, navItem(Material.ARROW, ChatColor.GREEN + "下一页 ▶", "next"));
        }

        player.openInventory(inv);
    }

    private ItemStack navItem(Material mat, String name, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem(int total) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "共 " + total + " 条绑定");
        meta.setLore(List.of(
                ChatColor.GRAY + "点击物品撤销绑定",
                ChatColor.GRAY + "绿色 = 已激活 | 黄色 = 待确认"
        ));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.startsWith(GUI_TITLE)) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        String displayName = clicked.getItemMeta().getDisplayName();

        if (displayName.contains("上一页")) {
            int page = guiPages.getOrDefault(player.getUniqueId(), 0);
            showChestGuiPage(player, storage.listAllBindings(), page - 1);
        } else if (displayName.contains("下一页")) {
            int page = guiPages.getOrDefault(player.getUniqueId(), 0);
            showChestGuiPage(player, storage.listAllBindings(), page + 1);
        } else if (displayName.startsWith(ChatColor.GREEN.toString()) || displayName.startsWith(ChatColor.YELLOW.toString())) {
            // Binding item clicked — extract ID from "#123 name → target"
            String namePart = ChatColor.stripColor(displayName);
            String[] parts = namePart.split(" ");
            if (parts.length > 0 && parts[0].startsWith("#")) {
                try {
                    long id = Long.parseLong(parts[0].substring(1));
                    if (storage.revokeBinding(id)) {
                        player.sendMessage(ChatColor.GREEN + FoliaMessages.bindRevoked(id));
                    } else {
                        player.sendMessage(ChatColor.RED + "绑定 #" + id + " 不存在。");
                    }
                    // Refresh GUI
                    showChestGuiPage(player, storage.listAllBindings(),
                            guiPages.getOrDefault(player.getUniqueId(), 0));
                    return;
                } catch (NumberFormatException ignored) {}
            }
            player.sendMessage(ChatColor.RED + "无法解析绑定 ID。");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        guiPages.remove(event.getPlayer().getUniqueId());
    }
}
