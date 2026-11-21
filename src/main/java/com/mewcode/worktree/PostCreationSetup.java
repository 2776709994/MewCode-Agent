// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Post-creation setup for newly created worktrees.
 */
public final class PostCreationSetup {

    private static final Logger log = Logger.getLogger(PostCreationSetup.class.getName());

    private PostCreationSetup() {}

    public static void perform(String repoRoot, String worktreePath, List<String> symlinkDirs) {
        copySettingsLocal(repoRoot, worktreePath);
        symlinkDirectories(repoRoot, worktreePath, symlinkDirs);
        copyWorktreeIncludeFiles(repoRoot, worktreePath);
    }

    private static void copySettingsLocal(String repoRoot, String worktreePath) {
        Path src = Path.of(repoRoot, ".mewcode", "settings.local.json");
        Path dst = Path.of(worktreePath, ".mewcode", "settings.local.json");
        if (!Files.exists(src)) return;
        try {
            Files.createDirectories(dst.getParent());
            if (dst.getParent().toRealPath().startsWith(Path.of(worktreePath).toRealPath())) Files.copy(src, dst);
        } catch (IOException e) {
            log.fine("Failed to copy settings.local.json: " + e.getMessage());
        }
    }

    private static void symlinkDirectories(String repoRoot, String worktreePath, List<String> dirs) {
        if (dirs == null) return;
        for (String dir : dirs) {
            Path relative = Path.of(dir).normalize();
            if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) continue;
            Path src = Path.of(repoRoot).toAbsolutePath().resolve(relative);
            Path dst = Path.of(worktreePath).toAbsolutePath().resolve(relative);
            if (!Files.isDirectory(src) || Files.exists(dst)) continue;
            try {
                Files.createSymbolicLink(dst, src);
            } catch (IOException e) {
                log.fine("Failed to symlink " + dir + ": " + e.getMessage());
            }
        }
    }

    private static void copyWorktreeIncludeFiles(String repoRoot, String worktreePath) {
        Path includeFile = Path.of(repoRoot, ".worktreeinclude");
        if (!Files.exists(includeFile)) return;
        try {
            List<String> patterns = Files.readAllLines(includeFile).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
            if (patterns.isEmpty()) return;

            ProcessBuilder pb = new ProcessBuilder("git", "ls-files",
                    "--others", "--ignored", "--exclude-standard", "--directory");
            pb.directory(Path.of(repoRoot).toFile());
            var result = com.mewcode.ProcessExecution.capture(pb, java.time.Duration.ofSeconds(30));
            String output = result.output();
            if (result.exitCode() != 0 || output.isBlank()) return;

            for (String entry : output.strip().split("\n")) {
                if (entry.endsWith("/") || entry.isBlank()) continue;
                if (matchesAnyPattern(entry, patterns)) {
                    Path src = Path.of(repoRoot, entry);
                    Path dst = Path.of(worktreePath, entry);
                    if (Files.isSymbolicLink(src) || !Files.isRegularFile(src)) continue;
                    Path worktree = Path.of(worktreePath).toRealPath();
                    Path parent = dst.getParent();
                    if (parent != null && (!Files.exists(parent) || parent.toRealPath().startsWith(worktree))) {
                        Files.createDirectories(parent);
                        if (parent.toRealPath().startsWith(worktree)) Files.copy(src, dst);
                    }
                }
            }
        } catch (Exception e) {
            log.fine("Failed to copy worktreeinclude files: " + e.getMessage());
        }
    }

    private static boolean matchesAnyPattern(String path, List<String> patterns) {
        String baseName = Path.of(path).getFileName().toString();
        for (String p : patterns) {
            String normalized = p.startsWith("/") ? p.substring(1) : p;
            if (normalized.equals(path) || normalized.equals(baseName)) return true;
            if (normalized.endsWith("/") && path.startsWith(normalized)) return true;
        }
        return false;
    }
}
