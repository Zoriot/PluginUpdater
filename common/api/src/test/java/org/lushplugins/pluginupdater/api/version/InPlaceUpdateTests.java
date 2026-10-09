package org.lushplugins.pluginupdater.api.version;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lushplugins.pluginupdater.api.http.Endpoint;
import org.lushplugins.pluginupdater.api.http.RateLimit;
import org.lushplugins.pluginupdater.api.platform.UpdaterPlatform;
import org.lushplugins.pluginupdater.api.source.Source;
import org.lushplugins.pluginupdater.api.source.SourceData;
import org.lushplugins.pluginupdater.api.source.SourceRegistry;
import org.lushplugins.pluginupdater.api.updater.PluginData;
import org.lushplugins.pluginupdater.api.updater.PluginInfo;
import org.lushplugins.pluginupdater.api.updater.Updater;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class InPlaceUpdateTests {
    private static final String PROPERTY = "pluginupdater.replace-in-place";

    @TempDir
    Path directory;

    private HttpServer server;
    private PluginData pluginData;
    private PluginInfo plugin;
    private SourceData sourceData;
    private Endpoint endpoint;
    private Path original;
    private byte[] originalBytes;
    private byte[] responseBytes;
    private String releaseName;
    private String headerName;
    private String url;
    private String sourceName;
    private String previousProperty;
    private int downloadStatus;
    private boolean truncateDownload;
    private Runnable beforeDownload;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setup() throws IOException {
        previousProperty = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
        original = directory.resolve("Plugin-1.0.jar");
        originalBytes = jar("old");
        Files.write(original, originalBytes);
        responseBytes = jar("new");
        releaseName = "Plugin-2.0.jar";
        downloadStatus = 200;
        beforeDownload = () -> {};

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int requestNumber = requests.incrementAndGet();
            if (requestNumber == 2) {
                beforeDownload.run();
            }
            if (headerName != null) {
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + headerName + "\"");
            }
            exchange.sendResponseHeaders(requestNumber >= 2 ? downloadStatus : 200,
                responseBytes.length + (requestNumber >= 2 && truncateDownload ? 10 : 0));
            try (var output = exchange.getResponseBody()) {
                output.write(responseBytes);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/release.jar";
        endpoint = Endpoint.create(url, new RateLimit(100, Duration.ofMinutes(1)));
        sourceName = "test-" + UUID.randomUUID();
        sourceData = new SourceData() {
            @Override
            public String sourceName() {
                return sourceName;
            }

            @Override
            public Endpoint endpoint() {
                return endpoint;
            }
        };
        pluginData = PluginData.builder("Plugin", "1.0.0").sourceData(sourceData).build();
        pluginData.latestVersion("2.0.0");
        plugin = new PluginInfo() {
            @Override
            public String getName() {
                return "Plugin";
            }

            @Override
            public String getVersion() {
                return "1.0.0";
            }

            @Override
            public File getFile() {
                return original.toFile();
            }

            @Override
            public ComponentLogger getComponentLogger() {
                return ComponentLogger.logger("test");
            }
        };
        SourceRegistry.register(new Source() {
            @Override
            public String getName() {
                return sourceName;
            }

            @Override
            public Version fetchLatestVersion(PluginData data, SourceData source) {
                return pluginData.latestVersion().orElseThrow();
            }

            @Override
            public DownloadableRelease fetchDownloadableRelease(PluginData data, SourceData source) {
                return release(data);
            }

            @Override
            public String getChangelogUrl(PluginData data, SourceData source) {
                return null;
            }
        });
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        SourceRegistry.unregister(sourceName);
        if (previousProperty == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previousProperty);
        }
    }

    @Test
    void keepsReleaseFilenameAndDeletesOriginalOnlyAfterDownload() throws Exception {
        beforeDownload = () -> assertArrayEquals(originalBytes, read(original));
        release(pluginData).downloadBeside(original);
        assertFalse(Files.exists(original));
        assertArrayEquals(responseBytes, Files.readAllBytes(directory.resolve(releaseName)));
        assertOnlyFile(releaseName);
    }

    @Test
    void retainsContentDispositionFilename() throws Exception {
        releaseName = null;
        headerName = "header-release.jar";
        release(pluginData).downloadBeside(original);
        assertOnlyFile(headerName);
    }

    @Test
    void retainsUrlFilename() throws Exception {
        releaseName = null;
        release(pluginData).downloadBeside(original);
        assertOnlyFile("release.jar");
    }

    @Test
    void retainsFallbackFilename() throws Exception {
        releaseName = null;
        url = url.replace("release.jar", "download");
        release(pluginData).downloadBeside(original);
        assertOnlyFile("Plugin-2.0.0.jar");
    }

    @Test
    void replacesSameFilenameAfterValidation() throws Exception {
        releaseName = original.getFileName().toString();
        release(pluginData).downloadBeside(original);
        assertArrayEquals(responseBytes, Files.readAllBytes(original));
        assertOnlyFile(releaseName);
    }

    @Test
    void preservesOriginalOnInvalidJar() {
        responseBytes = "not a jar".getBytes(StandardCharsets.UTF_8);
        assertDownloadFailurePreservesOriginal();
    }

    @Test
    void preservesOriginalOnEmptyJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream ignored = new JarOutputStream(bytes)) {
        }
        responseBytes = bytes.toByteArray();
        assertDownloadFailurePreservesOriginal();
    }

    @Test
    void preservesOriginalOnCorruptJarEntry() {
        for (int i = 0; i < responseBytes.length - 2; i++) {
            if (responseBytes[i] == 'n' && responseBytes[i + 1] == 'e' && responseBytes[i + 2] == 'w') {
                responseBytes[i] = 'x';
                break;
            }
        }
        assertDownloadFailurePreservesOriginal();
    }

    @Test
    void preservesOriginalOnFailedSecondHttpResponse() {
        downloadStatus = 500;
        assertDownloadFailurePreservesOriginal();
    }

    @Test
    void preservesOriginalOnTruncatedDownload() {
        truncateDownload = true;
        assertDownloadFailurePreservesOriginal();
    }

    @Test
    void preservesSameNamedOriginalOnInvalidDownload() {
        releaseName = original.getFileName().toString();
        responseBytes = new byte[]{1, 2, 3};
        assertDownloadFailurePreservesOriginal();
    }

    @Test
    void refusesToOverwriteAnotherJar() throws IOException {
        Path existing = directory.resolve(releaseName);
        Files.write(existing, originalBytes);
        assertThrows(IOException.class, () -> release(pluginData).downloadBeside(original));
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
        assertArrayEquals(originalBytes, Files.readAllBytes(existing));
        assertEquals(1, requests.get());
    }

    @Test
    void rejectsPathInReleaseFilename() {
        releaseName = "../outside.jar";
        assertDownloadFailurePreservesOriginal();
        releaseName = "..\\outside.jar";
        assertDownloadFailurePreservesOriginal();
    }

    @Test
    void rollsBackNewJarIfOldJarCannotBeDeleted() throws Exception {
        beforeDownload = () -> {
            try {
                // A nonempty directory deterministically simulates a path that cannot be deleted.
                Files.delete(original);
                Files.createDirectory(original);
                Files.write(original.resolve("original.jar"), originalBytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        IOException failure = assertThrows(IOException.class, () -> release(pluginData).downloadBeside(original));
        assertTrue(failure.getMessage().contains("Failed to delete old plugin jar"));
        assertFalse(Files.exists(directory.resolve(releaseName)));
        assertArrayEquals(originalBytes, Files.readAllBytes(original.resolve("original.jar")));
        assertOnlyFile(original.getFileName().toString());
    }

    @Test
    void keepsDefaultUpdateDirectoryBehavior() throws Exception {
        Path updates = directory.resolve("update");
        assertTrue(new TestPlatform(true).downloadUpdate(pluginData, plugin, updates));
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
        assertArrayEquals(responseBytes, Files.readAllBytes(updates.resolve(releaseName)));
    }

    @Test
    void enabledPropertyUsesOriginalFolderWithoutAnUpdateDirectory() throws Exception {
        System.setProperty(PROPERTY, "true");
        assertTrue(new TestPlatform(true).downloadUpdate(pluginData, plugin, null));
        assertFalse(Files.exists(original));
        assertOnlyFile(releaseName);
    }

    @Test
    void unsupportedPlatformRejectsPropertyBeforeDownloading() {
        System.setProperty(PROPERTY, "true");
        assertThrows(UnsupportedOperationException.class,
            () -> new TestPlatform(false).downloadUpdate(pluginData, plugin, directory.resolve("update")));
        assertEquals(0, requests.get());
        assertArrayEquals(originalBytes, read(original));
    }

    @Test
    void missingOriginalPluginIsAnExplicitFailure() {
        System.setProperty(PROPERTY, "true");
        assertThrows(IllegalStateException.class,
            () -> new TestPlatform(true).downloadUpdate(pluginData, null, directory));
        assertEquals(0, requests.get());
    }

    @Test
    void invalidDownloadIsReportedAsFailureThroughPlatform() {
        System.setProperty(PROPERTY, "true");
        responseBytes = new byte[]{1, 2, 3};
        assertFalse(new TestPlatform(true).downloadUpdate(pluginData, plugin, null));
        assertArrayEquals(originalBytes, read(original));
        assertOnlyFile(original.getFileName().toString());
    }

    @Test
    void missingOriginalFileIsReportedAsFailureWithoutDownloading() throws IOException {
        System.setProperty(PROPERTY, "true");
        Files.delete(original);
        assertFalse(new TestPlatform(true).downloadUpdate(pluginData, plugin, null));
        assertEquals(0, requests.get());
    }

    @Test
    void shadeableUpdaterDoesNotMarkFailedDownloadAsSuccessful() {
        System.setProperty(PROPERTY, "true");
        responseBytes = new byte[]{1, 2, 3};
        Updater<Object> updater = newUpdater();
        try {
            assertFalse(updater.forceDownload().join());
            assertFalse(updater.isAlreadyDownloaded());
            assertArrayEquals(originalBytes, read(original));
            assertOnlyFile(original.getFileName().toString());
        } finally {
            updater.shutdown();
        }
    }

    @Test
    void shadeableUpdaterUsesSameReplacementFlowAndMarksSuccess() throws Exception {
        System.setProperty(PROPERTY, "true");
        Updater<Object> updater = newUpdater();
        try {
            assertTrue(updater.forceDownload().join());
            assertTrue(updater.isAlreadyDownloaded());
            assertFalse(Files.exists(original));
            assertOnlyFile(releaseName);
        } finally {
            updater.shutdown();
        }
    }

    private Updater<Object> newUpdater() {
        Updater<Object> updater = Updater.builder(new TestPlatform(true), plugin)
            .source(sourceData)
            .checkSchedule(-1)
            .notify(false)
            .onBuild(value -> {})
            .build();
        updater.pluginData().latestVersion("2.0.0");
        return updater;
    }

    private DownloadableRelease release(PluginData data) {
        return new DownloadableRelease(data, endpoint, url, Map.of(), Optional.ofNullable(releaseName));
    }

    private void assertDownloadFailurePreservesOriginal() {
        assertThrows(Exception.class, () -> release(pluginData).downloadBeside(original));
        assertArrayEquals(originalBytes, read(original));
        assertOnlyFile(original.getFileName().toString());
    }

    private void assertOnlyFile(String filename) {
        try (var files = Files.list(directory)) {
            assertEquals(List.of(filename), files.map(path -> path.getFileName().toString()).toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] jar(String content) throws IOException {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        CRC32 checksum = new CRC32();
        checksum.update(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            JarEntry entry = new JarEntry("plugin.yml");
            entry.setMethod(JarEntry.STORED);
            entry.setSize(payload.length);
            entry.setCrc(checksum.getValue());
            output.putNextEntry(entry);
            output.write(payload);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static class TestPlatform implements UpdaterPlatform<Object> {
        private final boolean supported;

        private TestPlatform(boolean supported) {
            this.supported = supported;
        }

        @Override
        public boolean supportsInPlaceUpdates() {
            return supported;
        }

        @Override
        public PluginInfo getPlugin(String name) {
            return null;
        }

        @Override
        public List<PluginInfo> getPlugins() {
            return List.of();
        }

        @Override
        public Collection<Object> getOnlineUsers() {
            return List.of();
        }

        @Override
        public boolean hasPermission(Object user, String permission) {
            return false;
        }

        @Override
        public void sendMessage(Object user, String message) {}

        @Override
        public void broadcastMessage(Collection<Object> users, String message) {}

        @Override
        public void sendActionBar(Object user, String message) {}

        @Override
        public void broadcastActionBar(List<Object> users, String message) {}
    }
}
