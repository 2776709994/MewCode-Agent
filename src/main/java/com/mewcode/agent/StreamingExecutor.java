// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.compact.RecoveryState;
import com.mewcode.hook.HookEngine;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.impl.BashTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent tool executor that partitions tool calls into read-only (parallel)
 * and write/command (sequential) batches.
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    private final PermissionChecker checker;

    private final HookEngine hookEngine;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final RecoveryState recoveryState;
    private final Path workDir;
    private final boolean interactive;
    private final Set<String> availableTools;

    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    public record ToolExecResult(String toolId, String output, boolean isError) {}

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState, Path workDir,
                             boolean interactive, Set<String> availableTools) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
        this.workDir = workDir;
        this.interactive = interactive;
        this.availableTools = availableTools;
    }

    public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {
        var results = new ArrayList<ToolExecResult>(calls.size());
        boolean executionDirectoryChanged = false;
        for (int i = 0; i < calls.size();) {
            if (Thread.currentThread().isInterrupted()) {
                results.add(fail(calls.get(i++), "Tool execution interrupted"));
                continue;
            }
            int end = i;
            while (!executionDirectoryChanged && end < calls.size() && isParallelRead(calls.get(end))) end++;
            if (end - i < 2) {
                var call = calls.get(i++);
                var outcome = executionDirectoryChanged ? fail(call, "Worktree changed. Reissue this call in the next turn.")
                        : executeSingle(call);
                if (!outcome.isError() && ("EnterWorktree".equals(call.toolName()) || "ExitWorktree".equals(call.toolName()))) {
                    executionDirectoryChanged = true;
                }
                results.add(outcome);
                continue;
            }
            var batch = calls.subList(i, end);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = batch.stream()
                        .map(call -> executor.submit(() -> executeSingle(call)))
                        .toList();
                for (int j = 0; j < batch.size(); j++) {
                    try {
                        results.add(futures.get(j).get());
                    } catch (ExecutionException e) {
                        results.add(fail(batch.get(j), "Tool execution failed: " + e.getCause().getMessage()));
                    } catch (InterruptedException e) {
                        futures.forEach(future -> future.cancel(true));
                        Thread.currentThread().interrupt();
                        for (; j < batch.size(); j++) {
                            results.add(fail(batch.get(j), "Tool execution interrupted"));
                        }
                    }
                }
            }
            i = end;
        }
        return results;
    }

    private boolean isParallelRead(ToolCallInfo call) {
        Tool tool = registry.get(call.toolName());
        return tool != null && tool.category() == ToolCategory.READ
                && !"AskUserQuestion".equals(call.toolName())
                && !"ToolSearch".equals(call.toolName())
                && !"ExitPlanMode".equals(call.toolName())
                && (checker == null || checker.check(tool, call.args(), workDir).decision()
                    != com.mewcode.permission.PermissionMode.Decision.ASK);
    }

    private ToolExecResult fail(ToolCallInfo call, String message) {
        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), message, true, 0));
        return new ToolExecResult(call.toolId(), message, true);
    }

    private ToolExecResult executeSingle(ToolCallInfo call) {
        try {
            return executeChecked(call);
        } catch (RuntimeException e) {
            return fail(call, "Tool execution error: " + e.getMessage());
        }
    }

    private ToolExecResult executeChecked(ToolCallInfo call) {
        Tool tool = registry.get(call.toolName());
        if (tool == null || !availableTools.contains(call.toolName())) {
            return fail(call, "Tool is unavailable: " + call.toolName());
        }

        if (checker != null) {
            var check = checker.check(tool, call.args(), workDir);
            switch (check.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + check.reason();
                    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                    return new ToolExecResult(call.toolId(), msg, true);
                }
                case ASK -> {
                    if (!interactive) return fail(call, "Tool requires interactive permission");
                    var future = new CompletableFuture<PermissionResponse>();
                    String desc = checker.describeToolAction(call.toolName(), call.args());
                    putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
                    PermissionResponse response;
                    try {
                        response = future.get(5, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        response = PermissionResponse.DENY;
                    } catch (Exception e) {
                        response = PermissionResponse.DENY;
                    }
                    if (response == null || response == PermissionResponse.DENY) {
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(), "Permission denied by user", true, 0));
                        return new ToolExecResult(call.toolId(), "User denied permission", true);
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = extractContent(call.toolName(), call.args());
                        if (content != null) {
                            checker.addAllowAlwaysRule(call.toolName(), content);
                        }
                    }
                }
                case ALLOW -> {}
            }
        }

        if (hookEngine != null) {
            var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.args());
            if (hookResult.rejected()) return fail(call, "Rejected by hook: " + hookResult.message());
        }

        Map<String, Object> resolvedArgs = resolvePaths(call);
        long start = System.nanoTime();
        ToolResult result;
        try {
            result = tool instanceof BashTool bash ? bash.execute(resolvedArgs, workDir) : tool.execute(resolvedArgs);
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        snapshotForRecovery(call.toolName(), resolvedArgs, result);

        String output = result.output();
        if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
            output = output.substring(0, ToolRegistry.MAX_OUTPUT_CHARS) + "\n... (truncated)";
        }

        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));

        // Post-tool hooks
        if (hookEngine != null) {
            var ctx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.args(), null, null, null);
            hookEngine.runHooks(ctx);
        }

        return new ToolExecResult(call.toolId(), result.output(), result.isError());
    }

    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Capture what ReadFile just returned so the compact recovery block
     * can replay it after a Layer 2 summary wipes the transcript.
     * Re-reads from disk to keep the snapshot independent of how the tool
     * formats its output (e.g. line-number prefixes).
     */
    private void snapshotForRecovery(String toolName, Map<String, Object> args, ToolResult result) {
        if (recoveryState == null || result.isError()) return;
        if (!"ReadFile".equals(toolName)) return;
        Object pathObj = args.get("file_path");
        if (!(pathObj instanceof String) || ((String) pathObj).isEmpty()) return;
        String path = (String) pathObj;
        try {
            try (var reader = Files.newBufferedReader(Path.of(path))) {
                char[] buffer = new char[ToolRegistry.MAX_OUTPUT_CHARS];
                int count = Math.max(0, reader.read(buffer));
                recoveryState.recordFileRead(path, new String(buffer, 0, count));
            }
        } catch (IOException ignored) {
            // Best-effort snapshot; if the file vanished between the tool
            // call and now, just skip — the model has the tool output it
            // already saw.
        }
    }

    private Map<String, Object> resolvePaths(ToolCallInfo call) {
        String key = switch (call.toolName()) {
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "path";
            default -> null;
        };
        if (key == null) return call.args();
        var args = new HashMap<>(call.args());
        Object value = args.get(key);
        if (value instanceof String path && !path.isBlank()) {
            args.put(key, workDir.resolve(path).toAbsolutePath().normalize().toString());
        } else if ("path".equals(key)) {
            args.put(key, workDir.toString());
        }
        return args;
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = switch (toolName) {
            case "Bash" -> "command";
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            default -> null;
        };
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }
}
