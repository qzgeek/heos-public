package heos.folia.storage;

import heos.folia.utils.FoliaMessages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * High-level account binding operations with validation.
 *
 * Binding rules:
 *  - bound account → target account (many-to-one)
 *  - One account can only bind to ONE target
 *  - A target cannot itself be bound (no chains)
 *  - Double-blind: target must specify bound name to accept
 *  - Only one player per binding group can be online
 */
public final class FoliaAccountBinding {
    private final FoliaStorage storage;
    private final Logger logger;

    public FoliaAccountBinding(FoliaStorage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    /** Request binding: boundPlayer wants to bind to targetPlayer. */
    public BindResult requestBinding(UUID boundUuid, String boundName, String targetName) {
        if (targetName == null || targetName.isEmpty())
            return BindResult.fail(FoliaMessages.bindOldNameRequired());
        if (boundName == null || boundName.isEmpty())
            return BindResult.fail("Invalid player name");
        if (boundName.equalsIgnoreCase(targetName))
            return BindResult.fail(FoliaMessages.bindSelfTarget());

        // Check: bound player is not already bound to someone
        if (storage.isBound(boundUuid))
            return BindResult.fail(FoliaMessages.bindAlreadyBound());

        // Check: bound player is not a target themselves
        if (storage.isTarget(boundUuid))
            return BindResult.fail(FoliaMessages.bindIsTarget());

        // Check: target player is not already bound to someone else (no chains)
        java.util.UUID targetOfflineUuid = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + targetName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (storage.isBound(targetOfflineUuid))
            return BindResult.fail(FoliaMessages.bindTargetIsBound());

        // Check: no existing pending request from this bound player
        List<FoliaStorage.BindingEntry> all = storage.listAllBindings();
        for (FoliaStorage.BindingEntry e : all) {
            if (e.isPending() && e.boundUuid.equals(boundUuid))
                return BindResult.fail(FoliaMessages.bindPendingExists());
        }

        FoliaStorage.BindingEntry entry = storage.requestBinding(boundUuid, boundName, targetName);
        if (entry == null) return BindResult.fail(FoliaMessages.bindRequestFailed());

        logger.info("Binding requested: " + boundName + " → " + targetName);
        return BindResult.ok(entry, FoliaMessages.bindRequestSent(targetName));
    }

    /** Accept binding: targetPlayer confirms by specifying the bound player's name. */
    public BindResult acceptBinding(UUID targetUuid, String targetName, String boundName) {
        if (boundName == null || boundName.isEmpty())
            return BindResult.fail(FoliaMessages.bindNewNameRequired());

        // Check: target is not already bound to someone else
        if (storage.isBound(targetUuid))
            return BindResult.fail(FoliaMessages.bindTargetIsBound());

        // Check: if already actively bound to this target, just confirm
        UUID alreadyTargetUuid = storage.getTargetUuid(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + boundName)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (alreadyTargetUuid != null && alreadyTargetUuid.equals(targetUuid)) {
            return BindResult.fail(FoliaMessages.bindAlreadyActive(boundName));
        }

        FoliaStorage.BindingEntry entry = storage.acceptBinding(targetUuid, targetName, boundName);
        if (entry == null) return BindResult.fail(FoliaMessages.bindNoPendingRequest(boundName));

        logger.info("Binding accepted: " + entry.boundName + " → " + entry.targetName
                + " (UUID: " + entry.boundUuid + " → " + entry.targetUuid + ")");
        return BindResult.ok(entry, FoliaMessages.bindAccepted(boundName));
    }

    /** Check if anyone in the binding group of this UUID is currently online.
     *  Returns the online player name if so, null otherwise. */
    public String checkGroupOnline(UUID uuid) {
        List<UUID> group = storage.getBindingGroup(uuid);
        if (group.isEmpty()) return null;
        for (UUID id : group) {
            if (id.equals(uuid)) continue; // skip self
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline()) {
                return online.getName();
            }
        }
        return null;
    }

    /** Get the group UUIDs including this one, for full online check. */
    public List<UUID> getBindingGroup(UUID uuid) {
        return storage.getBindingGroup(uuid);
    }

    /** Resolve effective UUID: if this UUID is bound, return the target UUID. */
    public UUID resolveEffectiveUuid(UUID uuid) {
        UUID target = storage.getTargetUuid(uuid);
        return target != null ? target : uuid;
    }

    // ==========

    public static final class BindResult {
        public final boolean success;
        public final String message;
        public final FoliaStorage.BindingEntry entry;

        private BindResult(boolean s, String m, FoliaStorage.BindingEntry e) { success = s; message = m; entry = e; }
        public static BindResult ok(FoliaStorage.BindingEntry e, String m) { return new BindResult(true, m, e); }
        public static BindResult fail(String m) { return new BindResult(false, m, null); }
    }
}
