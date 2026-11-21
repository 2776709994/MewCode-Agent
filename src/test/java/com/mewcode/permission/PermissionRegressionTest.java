package com.mewcode.permission;

import com.mewcode.tool.impl.BashTool;
import com.mewcode.tool.impl.GrepTool;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.tool.impl.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.mewcode.permission.PermissionMode.Decision.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionRegressionTest {
    @Test
    void planAllowsOnlyTheActivePlanFile(@TempDir Path root) {
        var checker = new PermissionChecker(PermissionMode.PLAN, root);
        checker.setPlanFilePath(root.resolve(".mewcode/plans/active.md").toString());
        var write = new WriteFileTool();
        assertEquals(ALLOW, checker.check(write, Map.of("file_path", ".mewcode/plans/active.md")).decision());
        assertEquals(DENY, checker.check(write, Map.of("file_path", ".mewcode/plans/other.md")).decision());
        assertEquals(DENY, checker.check(write, Map.of("file_path", "ordinary.md")).decision());
    }

    @Test
    void checksActualFileAndSearchRoots(@TempDir Path root) throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        var checker = new PermissionChecker(PermissionMode.BYPASS, project);
        assertEquals(DENY, checker.check(new ReadFileTool(), Map.of("file_path", "../outside.txt")).decision());
        assertEquals(DENY, checker.check(new GrepTool(), Map.of("path", "..", "pattern", "example")).decision());
        assertEquals(ALLOW, checker.check(new ReadFileTool(), Map.of("file_path", "inside.txt")).decision());
    }

    @Test
    void shellApprovalUsesWholeCommand(@TempDir Path root) {
        var checker = new PermissionChecker(PermissionMode.DEFAULT, root);
        var bash = new BashTool();
        assertEquals(ALLOW, checker.check(bash, Map.of("command", "pwd")).decision());
        assertEquals(ASK, checker.check(bash, Map.of("command", "pwd\nprintf hello")).decision());
        assertEquals(ASK, checker.check(bash, Map.of("command", "git branch topic")).decision());
    }
}
