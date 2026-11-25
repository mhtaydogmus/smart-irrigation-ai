package com.smartirrigation.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.smartirrigation.backend.config.WatcherProperties;

import jakarta.annotation.PostConstruct;

@Service
@ConditionalOnProperty(name = "capture.mode", havingValue = "FOLDER", matchIfMissing = true)
public class FolderWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FolderWatcherService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");

    private record DroppedFile(Path path, String key, long size) {
    }

    private final SnapshotService snapshotService;
    private final Path directory;
    private final long pollIntervalMs;
    private final Set<String> processed = new HashSet<>();
    private final Map<String, Long> pendingSizes = new HashMap<>();

    public FolderWatcherService(SnapshotService snapshotService,
                                WatcherProperties props,
                                @Value("${watcher.poll-interval-ms:1500}") long pollIntervalMs) {
        this.snapshotService = snapshotService;
        this.directory = resolveDirectory(props.directory());
        this.pollIntervalMs = pollIntervalMs;
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(directory);
        List<DroppedFile> existing = listImages();
        existing.forEach(f -> processed.add(f.key()));
        log.info("FOLDER mode: watching {} every {} ms ({} existing image(s) skipped, drop new .jpg/.jpeg/.png files to scan)",
                directory, pollIntervalMs, existing.size());
    }

    @Scheduled(fixedDelayString = "${watcher.poll-interval-ms:1500}")
    public synchronized void poll() {
        List<DroppedFile> images = listImages();
        Set<String> present = new HashSet<>();
        images.forEach(f -> present.add(f.key()));
        processed.retainAll(present);
        pendingSizes.keySet().retainAll(present);

        for (DroppedFile file : images) {
            if (processed.contains(file.key()) || !isFullyWritten(file)) {
                continue;
            }

            String name = file.path().getFileName().toString();
            log.info("New image dropped: {}", name);
            try {
                if (snapshotService.processDroppedImage(file.path()).isEmpty()) {
                    log.warn("System BUSY, {} will be retried on the next poll", name);
                    continue;
                }
            } catch (RuntimeException e) {
                log.error("Scan pipeline failed for {}: {} (re-drop or touch the file to retry)", name, e.getMessage());
            }
            processed.add(file.key());
        }
    }

    private boolean isFullyWritten(DroppedFile file) {
        Long previousSize = pendingSizes.put(file.key(), file.size());
        if (previousSize == null || previousSize != file.size()) {
            return false;
        }
        pendingSizes.remove(file.key());
        return true;
    }

    private List<DroppedFile> listImages() {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(FolderWatcherService::isSupportedImage)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(FolderWatcherService::describe)
                    .flatMap(Stream::ofNullable)
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot list {}: {}", directory, e.getMessage());
            return List.of();
        }
    }

    private static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return !name.startsWith(".")
                && dot > 0
                && SUPPORTED_EXTENSIONS.contains(name.substring(dot).toLowerCase(Locale.ROOT))
                && Files.isRegularFile(path);
    }

    private static DroppedFile describe(Path path) {
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            return new DroppedFile(path, path.getFileName() + "@" + modified, Files.size(path));
        } catch (IOException e) {
            return null;
        }
    }

    private static Path resolveDirectory(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("backend")) && Files.isDirectory(dir.resolve("edge-vision-service"))) {
                return dir.resolve("test-images");
            }
        }
        return cwd.resolve("test-images");
    }
}
