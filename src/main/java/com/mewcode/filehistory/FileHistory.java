// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.filehistory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class FileHistory {

    public record Backup(String backupPath, int version, Instant time, boolean absent) {}

    public record Snapshot(
            int messageIndex,
            String userText,
            Map<String, Backup> backups,
            Instant timestamp
    ) {}

    private static final int MAX_SNAPSHOTS = 100;

    private final Path sessionDir;
    private final String runId = UUID.randomUUID().toString();
    private final Map<String, Integer> trackedFiles = new LinkedHashMap<>();
    private final List<Snapshot> snapshots = new CopyOnWriteArrayList<>();

    public FileHistory(String baseDir, String sessionId) {
        this.sessionDir = Path.of(baseDir, ".mewcode", "file-history", sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException ignored) {}
    }

    private String backupName(String filePath, int version) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(filePath.getBytes());
            return "%02x%02x%02x%02x%02x%02x%02x%02x@v%d".formatted(
                    hash[0], hash[1], hash[2], hash[3],
                    hash[4], hash[5], hash[6], hash[7], version);
        } catch (Exception e) {
            return "backup-" + filePath.hashCode() + "@v" + version;
        }
    }

    public synchronized void trackEdit(String path) {
        String key = Path.of(path).toAbsolutePath().normalize().toString();
        if (trackedFiles.containsKey(key)) return;

        trackedFiles.put(key, 0);
        Backup beforeFirstEdit = capture(key);
        if (beforeFirstEdit == null) return;

        for (int i = 0; i < snapshots.size(); i++) {
            Snapshot old = snapshots.get(i);
            var backups = new LinkedHashMap<>(old.backups());
            backups.put(key, beforeFirstEdit);
            snapshots.set(i, new Snapshot(old.messageIndex(), old.userText(), Map.copyOf(backups), old.timestamp()));
        }
    }

    public synchronized void makeSnapshot(int msgIndex, String userText) {
        var backups = new LinkedHashMap<String, Backup>();
        for (String path : trackedFiles.keySet()) {
            Backup current = capture(path);
            if (current != null) backups.put(path, current);
        }
        snapshots.add(new Snapshot(msgIndex, userText, Map.copyOf(backups), Instant.now()));
        while (snapshots.size() > MAX_SNAPSHOTS) snapshots.removeFirst();
    }

    private Backup capture(String key) {
        Path path = Path.of(key);
        int version = trackedFiles.compute(key, (ignored, last) -> last + 1);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return new Backup(null, version, Instant.now(), true);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
            Path backup = sessionDir.resolve(runId + "-" + backupName(key, version));
            Files.copy(path, backup);
            return new Backup(backup.toString(), version, Instant.now(), false);
        } catch (IOException e) {
            return null;
        }
    }

    public List<Snapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    public boolean hasSnapshots() {
        return !snapshots.isEmpty();
    }

    public synchronized List<String> rewind(int snapshotIndex) {
        if (snapshotIndex < 0 || snapshotIndex >= snapshots.size()) {
            return List.of();
        }

        Snapshot target = snapshots.get(snapshotIndex);
        var changed = new ArrayList<String>();

        for (var entry : target.backups().entrySet()) {
            String filePath = entry.getKey();
            Backup backup = entry.getValue();

            try {
                Path path = Path.of(filePath);
                if (Files.isSymbolicLink(path)) continue;
                if (backup.absent()) {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(path)) changed.add(filePath);
                    continue;
                }
                Path source = Path.of(backup.backupPath());
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path) || Files.mismatch(path, source) != -1) {
                    Files.createDirectories(path.getParent());
                    Files.copy(source, path, StandardCopyOption.REPLACE_EXISTING);
                    changed.add(filePath);
                }
            } catch (IOException ignored) {
                // An unreadable backup cannot be used to restore or remove a file.
            }
        }

        while (snapshots.size() > snapshotIndex + 1) snapshots.removeLast();
        return changed;
    }
}
