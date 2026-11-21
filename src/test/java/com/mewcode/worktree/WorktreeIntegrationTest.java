package com.mewcode.worktree;

import com.mewcode.ProcessExecution;
import com.mewcode.tool.impl.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeIntegrationTest {
    @Test
    void worktreeHasSeparateFilesAndKeepsChanges(@TempDir Path root) throws Exception {
        git(root, "init", "-q");
        Files.writeString(root.resolve("test.txt"), "original");
        git(root, "add", "test.txt");
        git(root, "-c", "user.name=Worktree Test", "-c", "user.email=test@example.invalid",
                "commit", "-qm", "Fixture");

        var manager = new WorktreeManager(root.toString(), java.util.List.of(), 24);
        var worktree = manager.create("integration-branch", null);
        Path worktreePath = Path.of(worktree.path());
        assertEquals("original", Files.readString(worktreePath.resolve("test.txt")));
        assertFalse(WorktreeChanges.hasChanges(worktree.path(), manager.readHead(worktree.path())));

        var result = new BashTool().execute(Map.of("command", "printf changed > test.txt"), worktreePath);
        assertFalse(result.isError(), result.output());
        assertEquals("changed", Files.readString(worktreePath.resolve("test.txt")));
        assertEquals("original", Files.readString(root.resolve("test.txt")));
        assertTrue(WorktreeChanges.hasChanges(worktree.path(), manager.readHead(worktree.path())));
    }

    @Test
    void bashTimeoutAppliesEvenWhenProcessOutputHasNotClosed(@TempDir Path root) {
        var result = assertTimeoutPreemptively(Duration.ofSeconds(8),
                () -> new BashTool().execute(Map.of("command", "sleep 15", "timeout", 1), root));
        assertTrue(result.isError());
        assertTrue(result.output().contains("timed out"), result.output());
    }

    private static void git(Path path, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        var result = ProcessExecution.capture(new ProcessBuilder(command).directory(path.toFile()), Duration.ofSeconds(15));
        assertEquals(0, result.exitCode(), result.output());
    }
}
