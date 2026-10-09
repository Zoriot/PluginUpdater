package org.lushplugins.pluginupdater.api.platform;

import org.jetbrains.annotations.Nullable;
import org.lushplugins.pluginupdater.api.updater.PluginData;
import org.lushplugins.pluginupdater.api.updater.PluginInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public interface UpdaterPlatform<T> {

    @Nullable PluginInfo getPlugin(String name);

    List<? extends PluginInfo> getPlugins();

    default boolean supportsInPlaceUpdates() {
        return false;
    }

    /**
     * Downloads to the update directory unless in-place updates have been explicitly enabled.
     * In-place updates retain the release filename and remove the old jar after validation.
     */
    default boolean downloadUpdate(PluginData pluginData, @Nullable PluginInfo plugin, @Nullable Path destinationDir) {
        if (Boolean.getBoolean("pluginupdater.replace-in-place")) {
            if (!supportsInPlaceUpdates()) {
                throw new UnsupportedOperationException("In-place updates are not supported on this platform (including Paper).");
            }

            File originalJar = plugin != null ? plugin.getFile() : null;
            if (originalJar == null) {
                throw new IllegalStateException("Cannot locate the original jar for plugin '" + pluginData.pluginName() + "'.");
            }

            Path originalPath = originalJar.toPath().toAbsolutePath().normalize();
            return pluginData.downloadUpdateBeside(originalPath);
        }

        return pluginData.downloadUpdate(Objects.requireNonNull(destinationDir, "downloadDir cannot be null"));
    }

    Collection<T> getOnlineUsers();

    default List<T> getOnlineUsersWithPermission(@Nullable String permission) {
        return getOnlineUsers().stream()
            .filter(user -> hasPermission(user, permission))
            .toList();
    }

    boolean hasPermission(T user, String permission);

    void sendMessage(T user, String message);

    void broadcastMessage(Collection<T> users, String message);

    void sendActionBar(T user, String message);

    void broadcastActionBar(List<T> users, String message);
}
