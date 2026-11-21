// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.ToolRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BashTool implements Tool {

    private static final int MAX_TIMEOUT = 600;

    private static final String DESCRIPTION = """
            Execute a shell command and return stdout and stderr.

            IMPORTANT: Avoid using this tool to run cat, head, tail, sed, awk, or echo commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory persists between commands, but shell state does not.
            - Always quote file paths containing spaces with double quotes.
            - Try to maintain your current working directory using absolute paths; avoid cd unless the user explicitly requests it.
            - Optional timeout in seconds (max 600). Default is 120s.
            - When issuing multiple independent commands, make separate parallel tool calls instead of chaining with &&.
            - Use && to chain sequential dependent commands. Use ; only when you don't care if earlier commands fail.
            - DO NOT use newlines to separate commands.

            Git Safety Protocol:
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., clean -f, branch -D) unless the user explicitly requests it.
            - NEVER skip hooks (--no-verify) unless the user explicitly requests it.
            - Prefer creating a new commit rather than amending an existing one.
            - Before running destructive operations, consider safer alternatives.

            Avoid unnecessary sleep commands. Do not retry failing commands in a sleep loop — diagnose the root cause instead.
            When using find, search from "." or a specific path, not "/" — scanning the full filesystem is too expensive.""";

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        return execute(args, Path.of(System.getProperty("user.dir")));
    }

    public ToolResult execute(Map<String, Object> args, Path workDir) {
        String command = stringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = intArg(args, "timeout", 120);
        timeout = Math.max(1, Math.min(timeout, MAX_TIMEOUT));

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(bashExecutable(), "-c", command);
            pb.directory(workDir.toFile());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            process = pb.start();
            process.getOutputStream().close();
            var stdoutTask = capture(process.getInputStream());
            var stderrTask = capture(process.getErrorStream());
            if (!process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            String stdout = stdoutTask.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            String stderr = stderrTask.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);

            int exitCode = process.exitValue();

            var sb = new StringBuilder();
            sb.append("$ ").append(command).append('\n');
            if (!stdout.isEmpty()) {
                sb.append(stdout);
                if (!stdout.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            if (!stderr.isEmpty()) {
                sb.append("STDERR: ").append(stderr);
                if (!stderr.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            sb.append("(exit code ").append(exitCode).append(')');

            return new ToolResult(sb.toString(), exitCode != 0);

        } catch (TimeoutException e) {
            return ToolResult.error("Error: command timed out after " + timeout + "s");
        } catch (IOException | ExecutionException e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    public static String bashExecutable() {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String dir = System.getenv(env);
                if (dir == null || dir.isBlank()) continue;
                Path root = Path.of(dir);
                if ("LOCALAPPDATA".equals(env)) root = root.resolve("Programs");
                Path candidate = root.resolve("Git/bin/bash.exe");
                if (java.nio.file.Files.isRegularFile(candidate)) return candidate.toString();
            }
            String path = System.getenv("PATH");
            if (path != null) {
                for (String entry : path.split(java.io.File.pathSeparator)) {
                    try {
                        Path bin = Path.of(entry.replace("\"", "")).toAbsolutePath();
                        Path root = bin.getParent();
                        if (root == null || !java.nio.file.Files.isRegularFile(bin.resolve("git.exe"))) continue;
                        Path candidate = root.resolve("bin/bash.exe");
                        if (java.nio.file.Files.isRegularFile(candidate)) return candidate.toString();
                    } catch (java.nio.file.InvalidPathException ignored) {
                        // Ignore malformed entries in PATH.
                    }
                }
            }
        }
        return "bash";
    }

    private static FutureTask<String> capture(InputStream stream) {
        var task = new FutureTask<>(() -> {
            try (stream; var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int limit = ToolRegistry.MAX_OUTPUT_CHARS * 4;
                boolean truncated = false;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    int kept = Math.min(count, limit - output.size());
                    output.write(buffer, 0, kept);
                    truncated |= kept < count;
                }
                String text = output.toString(StandardCharsets.UTF_8);
                return truncated ? text + "\n... (truncated)" : text;
            }
        });
        Thread.startVirtualThread(task);
        return task;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
