package heos.folia.event;

import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;
import heos.folia.utils.FoliaLoginFailureTracker;
import heos.folia.utils.FoliaDisconnects;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.utils.FoliaPasswordHasher;
import heos.folia.utils.FoliaPlayerAccess;
import heos.folia.utils.FoliaTpsDisplayService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import heos.folia.utils.FoliaMessages;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FoliaAuthService {
    private final Plugin plugin;
    private final FoliaStorage storage;
    private final FoliaNameResolver nameResolver;
    private final FoliaAccountBinding accountBinding;
    private final FoliaTpsDisplayService tpsDisplayService;
    private final FoliaLoginFailureTracker failureTracker;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> authenticatedSessionsByIp = new ConcurrentHashMap<>();

    public FoliaAuthService(Plugin plugin, FoliaStorage storage, FoliaNameResolver nameResolver,
                            FoliaAccountBinding accountBinding, FoliaTpsDisplayService tpsDisplayService) {
        this.plugin = plugin;
        this.storage = storage;
        this.nameResolver = nameResolver;
        this.accountBinding = accountBinding;
        this.tpsDisplayService = tpsDisplayService;
        this.failureTracker = new FoliaLoginFailureTracker(plugin);
    }

    /**
     * Resolve the effective UUID for a joining player after binding lookup.
     * This is called before the player is fully logged in to determine identity.
     */
    public UUID resolveEffectiveUuid(UUID rawUuid) {
        return accountBinding.resolveEffectiveUuid(rawUuid);
    }

    /**
     * Prepare a player after join. Load or create their data, set up session.
     */
    public void prepare(Player player) {
        UUID uuid = player.getUniqueId();
        boolean premium = isPremiumUuid(player);
        String ip = FoliaPlayerAccess.ip(player);

        FoliaPlayerData data = storage.load(uuid);
        if (data == null) {
            // New player, create data
            data = new FoliaPlayerData(player.getName(), uuid, premium);
        }
        // Update username if it changed
        if (!player.getName().equals(data.username)) {
            data.username = player.getName();
        }
        data.isOnlineAccount = premium;
        data.lastIp = ip;
        data.lastLoginTime = System.currentTimeMillis();

        // Resolve name conflicts
        nameResolver.resolve(data);

        if (!isAuthenticationEnabled()) {
            storage.save(data);
            Session session = new Session(data, true);
            session.ip = ip;
            sessions.put(uuid, session);
            updateLoginProtection(player, false);
            tpsDisplayService.start(player);
            applyDisplayName(player, data);
            return;
        }

        if (premium) {
            data.uuid = uuid;
            storage.save(data);
            Session session = new Session(data, true);
            session.ip = ip;
            sessions.put(uuid, session);
            updateLoginProtection(player, false);
            tpsDisplayService.start(player);
            applyDisplayName(player, data);
            player.sendMessage(ChatColor.GREEN + FoliaMessages.premiumWelcome());
            return;
        }

        boolean registered = data.isRegistered();
        sessions.put(uuid, new Session(data, false));
        updateLoginProtection(player, true);
        applyDisplayName(player, data);
        player.sendMessage(registered
                ? ChatColor.YELLOW + FoliaMessages.loginInputHint()
                : ChatColor.YELLOW + FoliaMessages.registerInputHint());
        scheduleLoginTimeout(player);
        scheduleLoginReminder(player);
    }

    private boolean isPremiumUuid(Player player) {
        // Bound accounts always get offline UUIDs (remapped to target)
        if (accountBinding.resolveEffectiveUuid(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .equals(player.getUniqueId())) {
            // The player's UUID matches what the binding system would produce
            // for their name. If it's different from the raw offline UUID,
            // it means they're a bound account — NOT premium.
            UUID rawOffline = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + player.getName())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!player.getUniqueId().equals(rawOffline)) {
                return false; // Bound account, not premium
            }
        }
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return !player.getUniqueId().equals(offline);
    }

    public void remove(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        tpsDisplayService.stop(player);
        if (session != null && session.authenticated) {
            decrementIp(session.ip);
        }
    }

    public boolean isAuthenticated(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.authenticated;
    }

    public boolean shouldBlock(Player player) {
        return isAuthenticationEnabled() && !isAuthenticated(player);
    }

    public boolean isAuthenticationEnabled() {
        return plugin.getConfig().getBoolean("enableAuthentication", true);
    }

    public boolean areOfflinePlayersAllowed() {
        return plugin.getConfig().getBoolean("allowOfflinePlayers", true);
    }

    public boolean canRunCommandWhileLocked(String commandLine) {
        String root = commandLine.split(" ", 2)[0].toLowerCase();
        return root.equals("login") || root.equals("l") || root.equals("register") || root.equals("reg");
    }

    public void login(Player player, String password) {
        Session session = session(player);
        FoliaPlayerData data = session.data;
        String ip = FoliaPlayerAccess.ip(player);
        if (failureTracker.isBlocked(player.getName(), ip)) {
            FoliaDisconnects.disconnect(player, failureTracker.blockMessage(player.getName(), ip), "HEOS_LOGIN_FAILURE_LOCK");
            return;
        }
        if (!data.isRegistered()) {
            player.sendMessage(ChatColor.RED + FoliaMessages.notRegistered());
            return;
        }
        if (!FoliaPasswordHasher.verifyPassword(password, data.passwordHash)) {
            if (failureTracker.recordFailure(player.getName(), ip)) {
                FoliaDisconnects.disconnect(player, failureTracker.blockMessage(player.getName(), ip), "HEOS_LOGIN_FAILURE_LOCK");
                return;
            }
            player.sendMessage(ChatColor.RED + FoliaMessages.wrongPassword());
            return;
        }
        if (!markAuthenticated(player, session)) {
            return;
        }
        failureTracker.reset(player.getName(), ip);
        data.lastIp = ip;
        data.lastLoginTime = System.currentTimeMillis();
        if (FoliaPasswordHasher.needsRehash(data.passwordHash)) {
            data.passwordHash = FoliaPasswordHasher.hashPassword(password);
        }
        storage.save(data);
        player.sendMessage(ChatColor.GREEN + FoliaMessages.loginSuccess());
    }

    public void register(Player player, String password, String confirmPassword) {
        Session session = session(player);
        FoliaPlayerData data = session.data;
        if (data.isRegistered()) {
            player.sendMessage(ChatColor.RED + FoliaMessages.alreadyRegistered());
            return;
        }
        if (!password.equals(confirmPassword)) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordMismatch());
            return;
        }
        int min = plugin.getConfig().getInt("minPasswordLength", 4);
        int max = plugin.getConfig().getInt("maxPasswordLength", 32);
        if (password.length() < min) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooShort().formatted(min));
            return;
        }
        if (password.length() > max) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooLong().formatted(max));
            return;
        }
        data.uuid = player.getUniqueId();
        data.passwordHash = FoliaPasswordHasher.hashPassword(password);
        data.lastIp = ip(player);
        long now = System.currentTimeMillis();
        data.registeredTime = now;
        data.lastLoginTime = now;
        if (!markAuthenticated(player, session)) {
            return;
        }
        storage.save(data);
        player.sendMessage(ChatColor.GREEN + FoliaMessages.registerSuccess());
    }

    public void changePassword(Player player, String oldPassword, String newPassword) {
        Session session = session(player);
        if (!session.authenticated) {
            player.sendMessage(ChatColor.RED + FoliaMessages.authPromptLogin());
            return;
        }
        FoliaPlayerData data = session.data;
        if (!FoliaPasswordHasher.verifyPassword(oldPassword, data.passwordHash)) {
            player.sendMessage(ChatColor.RED + FoliaMessages.wrongPassword());
            return;
        }
        if (oldPassword.equals(newPassword)) {
            player.sendMessage(ChatColor.RED + "New password cannot be the same as the old password");
            return;
        }
        int min = plugin.getConfig().getInt("minPasswordLength", 4);
        int max = plugin.getConfig().getInt("maxPasswordLength", 32);
        if (newPassword.length() < min) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooShort().formatted(min));
            return;
        }
        if (newPassword.length() > max) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooLong().formatted(max));
            return;
        }
        data.passwordHash = FoliaPasswordHasher.hashPassword(newPassword);
        storage.save(data);
        player.sendMessage(ChatColor.GREEN + FoliaMessages.keepPasswordSafe());
    }

    public void close() {
        storage.close();
        sessions.clear();
    }

    public FoliaPlayerData getPlayerData(UUID uuid) {
        Session session = sessions.get(uuid);
        if (session != null) {
            return session.data;
        }
        return storage.load(uuid);
    }

    public FoliaStorage getStorage() {
        return storage;
    }

    public FoliaNameResolver getNameResolver() {
        return nameResolver;
    }

    public FoliaAccountBinding getAccountBinding() {
        return accountBinding;
    }

    private void scheduleLoginTimeout(Player player) {
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("loginTimeout", 60));
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline() && shouldBlock(player)) {
                FoliaDisconnects.disconnect(player, FoliaMessages.loginTimeout(), "HEOS_LOGIN_TIMEOUT");
            }
        }, null, timeoutSeconds * 20L);
    }

    private void scheduleLoginReminder(Player player) {
        int reminderSeconds = Math.max(1, plugin.getConfig().getInt("loginReminderSeconds", 10));
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline() || !shouldBlock(player)) {
                return;
            }
            FoliaPlayerData data = session(player).data;
            player.sendMessage(data.isRegistered()
                    ? ChatColor.YELLOW + FoliaMessages.loginInputHint()
                    : ChatColor.YELLOW + FoliaMessages.registerInputHint());
            scheduleLoginReminder(player);
        }, null, reminderSeconds * 20L);
    }

    private Session session(Player player) {
        UUID uuid = player.getUniqueId();
        return sessions.computeIfAbsent(uuid, ignored -> {
            FoliaPlayerData data = storage.load(uuid);
            if (data == null) {
                boolean premium = isPremiumUuid(player);
                data = new FoliaPlayerData(player.getName(), uuid, premium);
                nameResolver.resolve(data);
            }
            return new Session(data, false);
        });
    }

    private boolean markAuthenticated(Player player, Session session) {
        if (session.authenticated) {
            return true;
        }
        int limit = plugin.getConfig().getInt("maxConcurrentSessionsPerIp", -1);
        String ip = FoliaPlayerAccess.ip(player);
        if (limit >= 0 && authenticatedSessionsByIp.getOrDefault(ip, 0) >= limit) {
            FoliaDisconnects.disconnect(
                    player,
                    plugin.getConfig().getString("sessionLimitKickMessage", "The online session limit for this IP has been reached"),
                    "HEOS_SESSION_LIMIT"
            );
            return false;
        }
        session.ip = ip;
        session.authenticated = true;
        authenticatedSessionsByIp.merge(ip, 1, Integer::sum);
        updateLoginProtection(player, false);
        tpsDisplayService.start(player);
        return true;
    }

    private static void updateLoginProtection(Player player, boolean protectedDuringLogin) {
        player.setInvulnerable(protectedDuringLogin);
        player.setInvisible(protectedDuringLogin);
        player.setCollidable(!protectedDuringLogin);
    }

    private static void applyDisplayName(Player player, FoliaPlayerData data) {
        if (data.hasNameConflict && data.displayName != null) {
            player.displayName(net.kyori.adventure.text.Component.text(data.displayName));
            player.playerListName(net.kyori.adventure.text.Component.text(data.displayName));
        }
    }

    private void decrementIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        authenticatedSessionsByIp.computeIfPresent(ip, (key, value) -> value <= 1 ? null : value - 1);
    }

    private static String ip(Player player) {
        return FoliaPlayerAccess.ip(player);
    }

    private static final class Session {
        private final FoliaPlayerData data;
        private volatile boolean authenticated;
        private volatile String ip = "";

        private Session(FoliaPlayerData data, boolean authenticated) {
            this.data = data;
            this.authenticated = authenticated;
        }
    }
}
