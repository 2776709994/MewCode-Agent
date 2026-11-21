// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.EventQueue;
import com.mewcode.session.SessionManager;
import com.mewcode.worktree.WorktreeSessionStore;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.hook.HookEngine;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.plan.PlanFile;
import com.mewcode.prompt.PlanModePrompt;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.toolresult.ApplyResult;
import com.mewcode.toolresult.ContentReplacementState;
import com.mewcode.toolresult.ReplacementRecordsIO;
import com.mewcode.toolresult.ToolResultBudget;

import java.nio.file.Path;

import java.util.*;
import java.util.concurrent.*;

public class Agent {

    private static final int MAX_RATE_LIMIT_RETRIES = 2;
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    private PermissionChecker checker;
    private PermissionMode headlessMode = PermissionMode.DEFAULT;
    private HookEngine hookEngine;
    private int maxIterations;
    private String workDir;
    /**
     * Session log id for the on-disk transcript. Plumbed so that an in-loop
     * compaction can append a compact_boundary record into the same session file
     * (enabling resume to rebuild the compacted state). Null for sub-agents /
     * one-shot callers that should not write boundaries into the main session.
     */
    private String sessionId;
    private java.util.function.Supplier<List<String>> notificationFn;

    private java.util.function.Predicate<String> toolNameFilter;
    private String instructions = "";
    private String memoryContent = "";
    private final com.mewcode.compact.ContextCompactor.AutoCompactTrackingState compactTracking =
            new com.mewcode.compact.ContextCompactor.AutoCompactTrackingState();

    /**
     * Real API-usage anchor for the compaction decision. Refreshed after each
     * stream ends with the provider-reported usage; null until the first turn
     * reports usage, so the compactor falls back to character estimation on a
     * cold start. See {@link com.mewcode.compact.ContextCompactor.UsageAnchor}.
     */
    private com.mewcode.compact.ContextCompactor.UsageAnchor usageAnchor;

    /**
     * Per-conversation-thread tool-result decision log. Carries across
     * iterations so Anthropic's prompt cache sees byte-stable prefixes.
     * Forks (see {@code AgentTool}) clone this for their child agent.
     */
    private ContentReplacementState replacementState = new ContentReplacementState();

    public ContentReplacementState getReplacementState() { return replacementState; }
    public void setReplacementState(ContentReplacementState state) { this.replacementState = state; }

    /**
     * Holds the snapshots needed to rebuild working context after Layer 2
     * collapses the conversation: most-recent file reads + skill SOPs.
     * Recorded on each ReadFile / skill call; consumed by ContextCompactor
     * when the threshold trips.
     */
    private final com.mewcode.compact.RecoveryState recoveryState =
            new com.mewcode.compact.RecoveryState();

    public com.mewcode.compact.RecoveryState getRecoveryState() { return recoveryState; }

    private com.mewcode.filehistory.FileHistory fileHistory;
    public void setFileHistory(com.mewcode.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public com.mewcode.filehistory.FileHistory getFileHistory() { return fileHistory; }

    public ToolRegistry getRegistry() { return registry; }
    public String getProtocol() { return protocol; }

    public Agent(LlmClient client, ToolRegistry registry, String protocol, ProviderConfig cfg) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.contextWindow = cfg.resolvedContextWindow();
        this.maxOutput = cfg.resolvedMaxOutputTokens();
    }

    public void setHeadlessMode(PermissionMode mode) { this.headlessMode = mode; }

    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setMaxIterations(int max) { this.maxIterations = max; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }

    public Path effectiveWorkDir() {
        var worktree = sessionId == null ? null : WorktreeSessionStore.getCurrentSession();
        String dir = worktree != null ? worktree.worktreePath()
                : workDir != null ? workDir : System.getProperty("user.dir");
        return Path.of(dir).toAbsolutePath().normalize();
    }
    public void setSessionId(String sessionId) {
        if (!Objects.equals(this.sessionId, sessionId)) {
            usageAnchor = null;
            replacementState = new ContentReplacementState();
            recoveryState.clear();
        }
        this.sessionId = sessionId;
    }
    public String getSessionId() { return sessionId; }
    public void setNotificationFn(java.util.function.Supplier<List<String>> fn) { this.notificationFn = fn; }

    public void setToolNameFilter(java.util.function.Predicate<String> filter) { this.toolNameFilter = filter; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public void setMemoryContent(String memoryContent) { this.memoryContent = memoryContent; }
    public HookEngine getHookEngine() { return hookEngine; }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new EventQueue<AgentEvent>();
        if (registry.get("AskUserQuestion") instanceof com.mewcode.tool.impl.AskUserTool ask) {
            ask.setEventQueue(queue);
        }
        if (registry.get("Agent") instanceof com.mewcode.subagent.AgentTool agentTool) {
            agentTool.setParentConversation(conv);
            agentTool.setParentReplacementState(replacementState);
        }
        queue.start(() -> {
            int turns = 0;
            try {
                turns = agentLoop(conv, queue);
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            } finally {
                queue.finish(new AgentEvent.LoopComplete(turns));
            }
        });
        return queue;
    }

    private int agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        conv.injectLongTermMemory(instructions, memoryContent);
        Path sessionRoot = Path.of(workDir != null ? workDir : System.getProperty("user.dir"));
        PermissionChecker permissions = checker != null ? checker
                : new PermissionChecker(headlessMode, effectiveWorkDir());

        int totalInput = 0, totalOutput = 0;
        int outputRecoveries = 0;
        int contextRetries = 0;
        int rateLimitRetries = 0;

        for (int iteration = 1; ; iteration++) {
            if (maxIterations > 0 && iteration > maxIterations) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }

            if (Thread.currentThread().isInterrupted()) break;

            // Drain background task notifications and inject as system reminders
            if (notificationFn != null) {
                for (String note : notificationFn.get()) {
                    conv.addSystemReminder(note);
                }
            }

            Path executionRoot = effectiveWorkDir();

            // Compute the tool schemas once per iteration so the recovery
            // attachment (when compact fires) and the Stream call below see
            // the same set. Skill filters can only change between iterations.
            var iterToolSchemas = registry.getAllSchemas(protocol);
            if (toolNameFilter != null) {
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            return name == null || toolNameFilter.test(name.toString());
                        })
                        .toList();
            }

            // Auto-compact check
            try {
                String wd = sessionRoot.toString();
                int sizeBefore = conv.size();
                String compactMsg = com.mewcode.compact.ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, compactTracking,
                        recoveryState, iterToolSchemas, usageAnchor);
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                }
                // A Layer 2 compaction collapses the conversation, so the prior
                // anchor (counted against the old message list) no longer aligns;
                // drop it and let the next stream re-anchor on real usage.
                if (conv.size() < sizeBefore) {
                    usageAnchor = null;
                }
            } catch (Exception ignored) {}

            var reminders = new ArrayList<String>();
            var deferredNames = registry.getDeferredToolNames().stream()
                    .filter(name -> toolNameFilter == null || toolNameFilter.test(name))
                    .toList();
            if (!deferredNames.isEmpty()) {
                reminders.add("Deferred tools. Use ToolSearch with select:<name>[,<name>...] to load tool schemas before calling them:\n"
                        + String.join("\n", deferredNames));
            }

            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String planPath = PlanFile.getOrCreatePlanPath(executionRoot.toString());
                checker.setPlanFilePath(planPath);
                reminders.add(PlanModePrompt.buildReminder(planPath, PlanFile.planExists(), iteration));
            }

            // Reuse the schema list computed at the top of the iteration so
            // both the recovery attachment and the LLM call agree on what's
            // wired up.
            var tools = iterToolSchemas;
            // Layer 1: apply tool-result budget. Returns a fresh
            // ConversationManager with replacements baked in; `conv` is
            // never mutated. Request-only reminders are attached to the copy.
            Path sessionDir = sessionId != null ? SessionManager.sessionDataDir(sessionRoot.toString(), sessionId)
                    : executionRoot.resolve(".mewcode/session");
            ApplyResult applied = ToolResultBudget.apply(conv, sessionDir, replacementState);
            if (sessionId != null && !applied.newRecords().isEmpty()) {
                try {
                    ReplacementRecordsIO.append(sessionDir, applied.newRecords());
                } catch (Exception ignored) {
                    // Best-effort transcript persistence; in-memory state is
                    // authoritative for this process lifetime.
                }
            }
            reminders.forEach(applied.apiConv()::addSystemReminder);
            var streamQueue = client.stream(applied.apiConv(), tools);

            // Consume stream events, collect tool calls
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolCalls = new ArrayList<ToolCallInfo>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            String streamError = null;

            try {
                while (true) {
                    StreamEvent event;
                    try {
                        event = streamQueue.poll(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return 0;
                    }

                    if (event == null) {
                        putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                        return 0;
                    }

                    switch (event) {
                        case StreamEvent.TextDelta td -> {
                            text.append(td.text());
                            putSafe(queue, new AgentEvent.StreamText(td.text()));
                        }
                        case StreamEvent.ThinkingDelta td ->
                                putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                        case StreamEvent.ThinkingComplete tc -> {
                            thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                            putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                        }
                        case StreamEvent.ToolCallStart tcs ->
                                putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), null));
                        case StreamEvent.ToolCallDelta tcd -> {}
                        case StreamEvent.ToolCallComplete tcc -> {
                            toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                            putSafe(queue, new AgentEvent.ToolUseEvent(
                                    tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        }
                        case StreamEvent.StreamEnd se -> {
                            stopReason = se.stopReason();
                            turnInput = se.inputTokens();
                            turnOutput = se.outputTokens();
                            turnCacheRead = se.cacheReadTokens();
                            turnCacheCreation = se.cacheCreationTokens();
                        }
                        case StreamEvent.Error err -> {
                            streamError = Objects.toString(err.message(), "Unknown stream error");
                        }
                    }

                    if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
                }
            } finally {
                EventQueue.cancel(streamQueue);
            }

            if (streamError != null) {
                String lowerError = streamError.toLowerCase(Locale.ROOT);
                if (lowerError.contains("context too long") || lowerError.contains("context_length_exceeded")
                        || lowerError.contains("prompt is too long")) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        putSafe(queue, new AgentEvent.RetryEvent("Context too long, compacting...", 0));
                        int sizeBeforeForce = conv.size();
                        try {
                            String wdForce = sessionRoot.toString();
                            com.mewcode.compact.ContextCompactor.forceCompact(
                                    conv, client, contextWindow, wdForce, sessionId,
                                    recoveryState, iterToolSchemas);
                        } catch (Exception ignored) {}
                        // forceCompact shrinks the conversation (summary + kept
                        // tail), so the prior anchor's message count no longer
                        // lines up; drop it and re-anchor on the next stream.
                        if (conv.size() < sizeBeforeForce) {
                            usageAnchor = null;
                        }
                        continue;
                    }
                }
                if (lowerError.contains("rate limit") && rateLimitRetries++ < MAX_RATE_LIMIT_RETRIES) {
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try { Thread.sleep(5000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                putSafe(queue, new AgentEvent.ErrorEvent(streamError));
                break;
            }

            contextRetries = 0;
            rateLimitRetries = 0;
            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));

            if ("max_tokens".equals(stopReason)) {
                if (!text.isEmpty()) {
                    conv.addAssistantMessage(text.toString());
                    persistTurn(sessionRoot, text.toString(), List.of(), List.of(), List.of());
                }
                if (outputRecoveries++ < MAX_OUTPUT_RECOVERIES) {
                    conv.addUserMessage("Output token limit hit. Continue from where you stopped and break remaining work into smaller pieces.");
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "max_tokens recovery %d/%d".formatted(outputRecoveries, MAX_OUTPUT_RECOVERIES), 0));
                    continue;
                }
                putSafe(queue, new AgentEvent.ErrorEvent("Output token limit reached after recovery attempts"));
                break;
            }
            outputRecoveries = 0;

            // Save assistant message to conversation
            var toolUseBlocks = toolCalls.stream()
                    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
                    .toList();
            conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);

            // Re-anchor the compaction estimate on this turn's real usage. The
            // baseline = input + cacheRead + cacheCreation + output covers the
            // sent context and the assistant message just appended; messages
            // added after this point (tool results, next user turn) are
            // estimated incrementally on top. A cache hit reports a small real
            // input, so the anchor tracks the true window far better than the
            // raw character estimate.
            if (turnInput > 0 || turnOutput > 0 || turnCacheRead > 0 || turnCacheCreation > 0) {
                int baseline = turnInput + turnCacheRead + turnCacheCreation + turnOutput;
                usageAnchor = new com.mewcode.compact.ContextCompactor.UsageAnchor(
                        baseline, conv.size());
            }

            // No tool calls → done
            if (toolCalls.isEmpty()) {
                if (fileHistory != null) {
                    String summary = text.length() > 60 ? text.substring(0, 60) + "..." : text.toString();
                    fileHistory.makeSnapshot(conv.size(), summary);
                }
                // No TurnComplete on the terminal (no-tool) turn — aligning Go
                // (agent.go emits only LoopComplete here). The TUI's TurnComplete
                // handler flushes+clears streamBuf without persisting; if we emitted
                // it first, LoopComplete would see an empty buffer and the final
                // assistant message would never be saved to the session file.
                persistTurn(sessionRoot, text.toString(), thinkingBlocks, toolUseBlocks, List.of());
                return iteration;
            }

            // Execute tool calls
            var available = tools.stream().map(schema -> (String) schema.get("name"))
                    .collect(java.util.stream.Collectors.toSet());
            var executor = new StreamingExecutor(registry, permissions, hookEngine, queue, recoveryState,
                    executionRoot, checker != null, available);
            var callInfos = toolCalls.stream()
                    .map(tc -> new StreamingExecutor.ToolCallInfo(tc.toolId, tc.toolName, tc.args))
                    .toList();
            var results = executor.executeAll(callInfos);

            // Add results to conversation
            var resultBlocks = results.stream()
                    .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
                    .toList();
            conv.addToolResultsMessage(resultBlocks);

            persistTurn(sessionRoot, text.toString(), thinkingBlocks, toolUseBlocks, resultBlocks);
            boolean exitPlanCalled = toolCalls.stream()
                    .filter(tc -> "ExitPlanMode".equals(tc.toolName))
                    .anyMatch(tc -> results.stream().anyMatch(r -> r.toolId().equals(tc.toolId) && !r.isError()));
            if (exitPlanCalled) {
                putSafe(queue, new AgentEvent.TurnComplete(iteration));
                return iteration;
            }

            putSafe(queue, new AgentEvent.TurnComplete(iteration));
        }
        return 0;
    }

    private void persistTurn(Path sessionRoot, String text, List<ThinkingBlock> thinking,
                             List<ToolUseBlock> toolUses, List<com.mewcode.conversation.ToolResultBlock> results) {
        if (sessionId != null) {
            SessionManager.saveTurn(sessionRoot.toString(), sessionId, text, thinking, toolUses, results);
        }
    }

    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
}
