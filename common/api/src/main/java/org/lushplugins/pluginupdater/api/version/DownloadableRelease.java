package org.lushplugins.pluginupdater.api.version;

import org.jetbrains.annotations.Nullable;
import org.lushplugins.pluginupdater.api.http.Endpoint;
import org.lushplugins.pluginupdater.api.updater.PluginData;
import org.lushplugins.pluginupdater.api.util.DownloadLogger;
import org.lushplugins.pluginupdater.api.util.HttpUtil;
import org.lushplugins.pluginupdater.api.util.UpdaterConstants;
import org.lushplugins.pluginupdater.util.BuildParameters;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

public record DownloadableRelease(
    PluginData pluginData,
    Endpoint endpoint,
    String downloadUrl,
    Map<String, String> downloadHeaders,
    Optional<String> jarName
) {

    public void downloadTo(Path destinationDir) throws IOException, InterruptedException {
        download(destinationDir, null);
    }

    /**
     * Keeps the release filename and deletes the original jar only after downloading and validating the new jar.
     */
    public void downloadBeside(Path originalJar) throws IOException, InterruptedException {
        Path originalPath = originalJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(originalPath, LinkOption.NOFOLLOW_LINKS)
            || !originalPath.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Original plugin jar is not a regular jar file: " + originalPath);
        }

        download(originalPath.getParent(), originalPath);
    }

    private void download(Path destinationDir, @Nullable Path originalJar) throws IOException, InterruptedException {
        String version = pluginData.latestVersion().orElseThrow().resolvedVersion();
        String fallbackFileName = pluginData.pluginName() + "-" + version + ".jar";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(this.downloadUrl))
            .header("User-Agent", "PluginUpdater/" + BuildParameters.VERSION);

        this.downloadHeaders.forEach(requestBuilder::header);

        HttpRequest request = requestBuilder
            .GET()
            .build();

        endpoint.markRequest();
        HttpClient client = HttpUtil.client();
        HttpResponse<Void> response = client.send(
            request,
            HttpResponse.BodyHandlers.discarding()
        );

        HttpUtil.assertResponse(endpoint, pluginData, response);

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Response code was " + response.statusCode());
        }

        // Get file name or default to PluginName-Version.jar
        String fileName = this.jarName.orElseGet(() -> getFileName(response, fallbackFileName));
        if (originalJar != null && (fileName.contains("/") || fileName.contains("\\")
            || Path.of(fileName).getRoot() != null || !fileName.endsWith(".jar"))) {
            throw new IOException("Invalid release jar filename: " + fileName);
        }

        Path downloadPath = destinationDir.resolve(fileName);
        // Ensures update folder exists
        Files.createDirectories(downloadPath.getParent());

        UpdaterConstants.LOGGER.info("Saving '{}' to '{}'", fileName, downloadPath.toAbsolutePath());
        if (originalJar == null) {
            downloadFile(client, request, downloadPath);
        } else {
            boolean sameFile = downloadPath.equals(originalJar);
            if (!sameFile && Files.exists(downloadPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Release destination already exists: " + downloadPath);
            }

            Path temporaryPath = Files.createTempFile(destinationDir, ".pluginupdater-", ".tmp");
            try {
                downloadFile(client, request, temporaryPath);
                validateJar(temporaryPath);
                if (sameFile) {
                    // Atomic replacement keeps the original intact if the move cannot be completed.
                    Files.move(temporaryPath, downloadPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporaryPath, downloadPath);
                    try {
                        Files.delete(originalJar);
                    } catch (IOException e) {
                        try {
                            Files.delete(downloadPath);
                        } catch (IOException cleanupFailure) {
                            e.addSuppressed(cleanupFailure);
                        }
                        throw new IOException("Failed to delete old plugin jar: " + originalJar, e);
                    }
                }
            } finally {
                Files.deleteIfExists(temporaryPath);
            }
        }

        DownloadLogger.logDownload(pluginData);
    }

    private void downloadFile(HttpClient client, HttpRequest request, Path path) throws IOException, InterruptedException {
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        HttpUtil.assertResponse(endpoint, pluginData, response);
    }

    private void validateJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.size() == 0) {
                throw new IOException("Downloaded jar is empty.");
            }

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                CRC32 checksum = new CRC32();
                try (InputStream input = new CheckedInputStream(jar.getInputStream(entry), checksum)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
                if (entry.getCrc() != checksum.getValue()) {
                    throw new IOException("Downloaded jar contains a corrupt entry: " + entry.getName());
                }
            }
        }
    }

    private String getFileName(HttpResponse<?> response, String fallbackFileName) {
        String contentDisposition = response.headers().firstValue("Content-Disposition").orElse(null);
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            if (contentDisposition.contains("filename=")) {
                int index = contentDisposition.indexOf("filename=");
                String filename = contentDisposition.substring(index + 9); // Cut out 'filename='
                filename = filename.replace("\"", "").trim();

                // Strip any trailing parameters (like splitting at a semicolon)
                if (filename.contains(";")) {
                    filename = filename.substring(0, filename.indexOf(";"));
                }

                if (filename.endsWith(".jar")) {
                    return filename.replace("%20", " ");
                }
            }
        }

        // Fallback to getting the file from the download URL
        int lastSlash = this.downloadUrl.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < this.downloadUrl.length() - 1) {
            String filename = this.downloadUrl.substring(lastSlash + 1);
            if (filename.endsWith(".jar")) {
                return filename.replace("%20", " ");
            }
        }

        return fallbackFileName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PluginData pluginData;
        private Endpoint endpoint;
        private String downloadUrl;
        private Map<String, String> downloadHeaders;
        private String jarName;

        private Builder() {}

        public Builder pluginData(PluginData pluginData) {
            this.pluginData = pluginData;
            return this;
        }

        public Builder endpoint(Endpoint endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder downloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
            return this;
        }

        public Builder downloadHeaders(@Nullable Map<String, String> downloadHeaders) {
            this.downloadHeaders = downloadHeaders;
            return this;
        }

        public Builder jarName(@Nullable String jarName) {
            this.jarName = jarName;
            return this;
        }

        public DownloadableRelease build() {
            return new DownloadableRelease(
                Objects.requireNonNull(pluginData),
                Objects.requireNonNull(endpoint),
                Objects.requireNonNull(downloadUrl),
                downloadHeaders != null ? downloadHeaders : Collections.emptyMap(),
                Optional.ofNullable(jarName)
            );
        }
    }
}
