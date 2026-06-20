package heos.folia;

import heos.folia.commands.FoliaAdminCommands;
import heos.folia.commands.FoliaAuthCommands;
import heos.folia.commands.FoliaBanCommands;
import heos.folia.commands.FoliaBindCommands;
import heos.folia.commands.FoliaBindUI;
import heos.folia.commands.FoliaMigrationCommands;
import heos.folia.event.FoliaAuthListener;
import heos.folia.event.FoliaAuthService;
import heos.folia.event.FoliaCommandInterceptor;
import heos.folia.integrations.FoliaRecipeSyncService;
import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaBanData;
import heos.folia.storage.FoliaStorage;
import heos.folia.storage.FoliaWhitelistData;
import heos.folia.utils.FoliaLoginUsernameValidationBypassService;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.utils.FoliaTpsDisplayService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeosFoliaPlugin extends JavaPlugin {
    private FoliaAuthService authService;
    private FoliaStorage storage;
    private FoliaBanData banData;
    private FoliaWhitelistData whitelistData;
    private FoliaAccountBinding accountBinding;
    private FoliaNameResolver nameResolver;
    private FoliaTpsDisplayService tpsDisplayService;
    private FoliaRecipeSyncService recipeSyncService;
    private FoliaLoginUsernameValidationBypassService bypassService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        heos.folia.utils.FoliaMessages.init(this);
        heos.folia.utils.FoliaLogFilterService.installConfiguredFilters(this);

        // Storage with optional MySQL
        this.storage = new FoliaStorage(getDataFolder().toPath());
        String bindingStorage = getConfig().getString("bindingStorage", "sqlite");
        if ("mysql".equalsIgnoreCase(bindingStorage)) {
            String url = getConfig().getString("mysql.url", "");
            String user = getConfig().getString("mysql.user", "");
            String pass = getConfig().getString("mysql.password", "");
            if (!url.isEmpty()) {
                storage.configureMySQL(url, user, pass);
                getLogger().info("Account binding storage: MySQL");
            }
        }
        storage.initialize();

        this.banData = FoliaBanData.load(getDataFolder().toPath(), getLogger());
        this.whitelistData = FoliaWhitelistData.load(getDataFolder().toPath(), getLogger());
        this.nameResolver = new FoliaNameResolver(storage);
        this.accountBinding = new FoliaAccountBinding(storage, getLogger());
        this.tpsDisplayService = new FoliaTpsDisplayService(this);
        this.authService = new FoliaAuthService(this, storage, nameResolver, accountBinding, tpsDisplayService);

        FoliaBanCommands banCommands = new FoliaBanCommands(banData, nameResolver);
        new heos.folia.utils.FoliaBanCleanupService(this, banData);

        FoliaMigrationCommands migrationCommands = new FoliaMigrationCommands(this, storage, banData, nameResolver);
        FoliaBindUI bindUI = new FoliaBindUI(storage, this);
        getServer().getPluginManager().registerEvents(bindUI, this);
        FoliaBindCommands bindCommands = new FoliaBindCommands(accountBinding, storage, bindUI);
        FoliaAdminCommands adminCommands = new FoliaAdminCommands(this, storage, whitelistData,
                migrationCommands, authService, banCommands, bindCommands);

        getServer().getPluginManager().registerEvents(
                new FoliaCommandInterceptor(this, authService, banCommands), this);
        getServer().getPluginManager().registerEvents(
                new FoliaAuthListener(this, authService, banData, whitelistData), this);
        registerCommands(banCommands, adminCommands);

        if (isRecipeViewerSyncEnabled()) {
            this.recipeSyncService = new FoliaRecipeSyncService(this);
        }

        this.bypassService = new FoliaLoginUsernameValidationBypassService(
                this, banData, whitelistData, accountBinding);
        bypassService.install();

        getLogger().info("Heos Folia enabled (UUID-based + Account Binding + Group Concurrency)");
        getLogger().info("Account binding: " + getConfig().getBoolean("enableAccountBinding", true));
        getLogger().info("Binding storage: " + bindingStorage);
        getLogger().info("Auth: " + getConfig().getBoolean("enableAuthentication", true)
                + ", TPS: " + getConfig().getBoolean("enableAutoLogTps", true));
        getLogger().info("Offline: " + (getConfig().getBoolean("allowOfflinePlayers", true) ? "Enabled" : "Disabled"));
    }

    @Override
    public void onDisable() {
        if (authService != null) authService.close();
        if (tpsDisplayService != null) tpsDisplayService.close();
        if (recipeSyncService != null) recipeSyncService.close();
        if (bypassService != null) bypassService.close();
    }

    private void registerCommands(FoliaBanCommands banCommands, FoliaAdminCommands adminCommands) {
        FoliaAuthCommands cmds = new FoliaAuthCommands(authService);
        bind("login", cmds); bind("register", cmds); bind("changepassword", cmds);
        bind("ban", banCommands); bind("ban-ip", banCommands);
        bind("unban", banCommands); bind("unban-ip", banCommands); bind("banlist", banCommands);
        bind("heos", adminCommands);
    }

    private void bind(String name, org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) { getLogger().warning("Missing command: " + name); return; }
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
    }

    private boolean isRecipeViewerSyncEnabled() {
        return getConfig().getBoolean("enableRecipeViewerSync", true)
                && compareVersions(getServer().getBukkitVersion().split("-", 2)[0], "1.21.2") >= 0;
    }

    private static int compareVersions(String a, String b) {
        String[] ap = a.split("\\."), bp = b.split("\\.");
        for (int i = 0; i < Math.max(ap.length, bp.length); i++) {
            int av = i < ap.length ? tryParse(ap[i]) : 0;
            int bv = i < bp.length ? tryParse(bp[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int tryParse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
