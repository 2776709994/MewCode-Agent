// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.subagent;

import com.mewcode.agent.AgentEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory task manager for tracking background sub-agent tasks.
 * Accumulates notifications that the parent agent can drain between turns.
 */
public class SubAgentTaskManager {

    public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    public record Task(String id, String name, TaskStatus status, String output, String error) {}

    public record TaskNotification(String taskId, String name, TaskStatus status, String output) {}

    private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();

    private final List<TaskNotification> notifications = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();

    private static class TaskEntry {
        final String id;
        final String name;
        volatile TaskStatus status;
        volatile String output;
        volatile String error;
        volatile Thread thread;

        TaskEntry(String id, String name) {
            this.id = id;
            this.name = name;
            this.status = TaskStatus.PENDING;
        }
    }

    public synchronized String createTask(String name) {
        String id = "task_" + nextId.incrementAndGet();
        tasks.put(id, new TaskEntry(id, name));
        return id;
    }

    public synchronized void setRunning(String id, Thread thread) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status == TaskStatus.PENDING) {
            t.status = TaskStatus.RUNNING;
            t.thread = thread;
        }
    }

    public synchronized void setCompleted(String id, String output) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status == TaskStatus.RUNNING) {
            t.status = TaskStatus.COMPLETED;
            t.output = output;
            notifications.add(new TaskNotification(id, t.name, TaskStatus.COMPLETED, output));
        }
    }

    public synchronized void setFailed(String id, String errMsg) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status == TaskStatus.RUNNING) {
            t.status = TaskStatus.FAILED;
            t.error = errMsg;
            notifications.add(new TaskNotification(id, t.name, TaskStatus.FAILED, errMsg));
        }
    }

    public synchronized void cancelTask(String id) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status == TaskStatus.RUNNING) {
            t.status = TaskStatus.CANCELLED;
            if (t.thread != null) {
                t.thread.interrupt();
            }
            notifications.add(new TaskNotification(id, t.name, TaskStatus.CANCELLED, ""));
        }
    }

    public synchronized void cancelAll() {
        for (String id : tasks.keySet()) cancelTask(id);
    }

    public synchronized List<TaskNotification> drainNotifications() {
        var result = new ArrayList<>(notifications);
        notifications.clear();
        return result;
    }

    public synchronized Task getTask(String id) {
        TaskEntry t = tasks.get(id);
        if (t == null) return null;
        return new Task(t.id, t.name, t.status, t.output, t.error);
    }

    public synchronized List<Task> listTasks() {
        return tasks.values().stream()
                .map(t -> new Task(t.id, t.name, t.status, t.output, t.error))
                .toList();
    }

    /**
     * Spawn a sub-agent in a background virtual thread, tracked by this manager.
     */
    public String spawnSubAgent(
            com.mewcode.llm.LlmClient client,
            com.mewcode.tool.ToolRegistry registry,
            String protocol,
            com.mewcode.config.ProviderConfig cfg,
            SubAgentSpec spec,
            String prompt
    ) {
        return spawnSubAgent(client, registry, protocol, cfg, spec, prompt, null);
    }

    /**
     * Spawn variant that lets the caller pre-seed the child agent's
     * tool-result decision log with a clone of the parent's state. Used by
     * the fork path so parent and child share a byte-identical prompt-cache
     * prefix on tool_use_ids that exist in their shared history.
     */
    public String spawnSubAgent(
            com.mewcode.llm.LlmClient client,
            com.mewcode.tool.ToolRegistry registry,
            String protocol,
            com.mewcode.config.ProviderConfig cfg,
            SubAgentSpec spec,
            String prompt,
            com.mewcode.toolresult.ContentReplacementState parentState
    ) {
        var conv = new com.mewcode.conversation.ConversationManager();
        conv.addUserMessage(prompt);
        return spawnConfigured(client, ToolFilter.filterForAgent(registry, spec, true, false, false),
                protocol, cfg, spec, prompt, parentState, conv, System.getProperty("user.dir"),
                com.mewcode.permission.PermissionMode.DEFAULT, null);
    }

    String spawnConfigured(com.mewcode.llm.LlmClient client, com.mewcode.tool.ToolRegistry registry,
                           String protocol, com.mewcode.config.ProviderConfig cfg, SubAgentSpec spec,
                           String prompt, com.mewcode.toolresult.ContentReplacementState parentState,
                           com.mewcode.conversation.ConversationManager conv, String workDir,
                           com.mewcode.permission.PermissionMode mode, com.mewcode.worktree.AgentWorktree.Result worktree) {
        String taskId = createTask(spec.name() + ": " + truncate(prompt, 50));
        var inheritedState = parentState == null ? null : parentState.copy();

        Thread thread = Thread.ofVirtual().unstarted(() -> {
            var subAgent = new com.mewcode.agent.Agent(client, registry, protocol, cfg);
            subAgent.setWorkDir(workDir);
            subAgent.setHeadlessMode(mode);
            int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
            subAgent.setMaxIterations(maxTurns);
            if (inheritedState != null) {
                subAgent.setReplacementState(inheritedState);
            }

            if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
                conv.addSystemReminder(spec.systemPromptOverride());
            }

            var output = new StringBuilder();
            BlockingQueue<AgentEvent> queue = subAgent.run(conv);

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    AgentEvent event;
                    try {
                        event = queue.poll(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        setFailed(taskId, "Interrupted");
                        return;
                    }
                    if (event == null) {
                        setFailed(taskId, "Timeout");
                        return;
                    }

                    switch (event) {
                        case AgentEvent.StreamText st -> output.append(st.text());
                        case AgentEvent.ErrorEvent err -> {
                            setFailed(taskId, err.message());
                            return;
                        }
                        case AgentEvent.LoopComplete lc -> {
                            String result = output.isEmpty() ? "(agent produced no output)" : output.toString();
                            if (worktree != null) result += "\n\nWorktree: " + worktree.worktreePath();
                            setCompleted(taskId, result);
                            return;
                        }
                        default -> {}
                    }
                }
            } finally {
                com.mewcode.EventQueue.cancel(queue);
            }
        });

        setRunning(taskId, thread);
        thread.start();
        return taskId;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
