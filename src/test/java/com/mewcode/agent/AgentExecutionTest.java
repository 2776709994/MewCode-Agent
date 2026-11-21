package com.mewcode.agent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.session.SessionManager;
import com.mewcode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionTest {
    @Test
    void executesCallsInOrderWithinWorkingDirectoryAndResumesCompleteTurn(@TempDir Path parent) throws Exception {
        Path worker = Files.createDirectory(parent.resolve("worker"));
        var calls = new AtomicInteger();
        LlmClient llm = (conv, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return events(
                        new StreamEvent.ToolCallComplete("write", "WriteFile", Map.of("file_path", "test.txt", "content", "worker")),
                        new StreamEvent.ToolCallComplete("read", "ReadFile", Map.of("file_path", "test.txt")),
                        new StreamEvent.StreamEnd("tool_use", 40, 5));
            }
            var results = conv.getMessages().getLast().getToolResults();
            assertEquals(List.of("write", "read"), results.stream().map(r -> r.toolUseId()).toList());
            assertTrue(results.getLast().content().contains("worker"));
            return events(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("end_turn", 50, 6));
        };
        var agent = new Agent(llm, ToolRegistry.createDefault(), "anthropic", new ProviderConfig());
        agent.setWorkDir(worker.toString());
        agent.setChecker(new PermissionChecker(PermissionMode.ACCEPT_EDITS, worker));
        agent.setSessionId("ordered-turn");
        var conv = new ConversationManager();
        conv.addUserMessage("write then read");
        var queue = agent.run(conv);

        assertEquals(2, awaitComplete(queue).totalTurns());
        assertEquals("worker", Files.readString(worker.resolve("test.txt")));
        assertFalse(Files.exists(parent.resolve("test.txt")));
        var restored = SessionManager.rebuildConversation(SessionManager.loadSession(worker.toString(), "ordered-turn"));
        assertEquals("write", restored.getMessages().getFirst().getToolUses().getFirst().toolUseId());
        assertEquals("read", restored.getMessages().get(1).getToolResults().getLast().toolUseId());
    }

    @Test
    void modelCannotCallToolOutsideAdvertisedSet(@TempDir Path root) throws Exception {
        var calls = new AtomicInteger();
        LlmClient llm = (conv, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return events(new StreamEvent.ToolCallComplete("forced", "WriteFile",
                                Map.of("file_path", "blocked.txt", "content", "blocked")),
                        new StreamEvent.StreamEnd("tool_use", 40, 5));
            }
            assertTrue(conv.getMessages().getLast().getToolResults().getFirst().isError());
            return events(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("end_turn", 50, 6));
        };
        var agent = new Agent(llm, ToolRegistry.createDefault(), "anthropic", new ProviderConfig());
        agent.setWorkDir(root.toString());
        agent.setChecker(new PermissionChecker(PermissionMode.ACCEPT_EDITS, root));
        agent.setToolNameFilter("ReadFile"::equals);
        var conv = new ConversationManager();
        conv.addUserMessage("restricted tool set");
        assertEquals(2, awaitComplete(agent.run(conv)).totalTurns());
        assertFalse(Files.exists(root.resolve("blocked.txt")));
    }

    private static AgentEvent.LoopComplete awaitComplete(BlockingQueue<AgentEvent> queue) throws InterruptedException {
        while (true) {
            var event = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            if (event instanceof AgentEvent.LoopComplete complete) return complete;
            if (event instanceof AgentEvent.ErrorEvent error) fail(error.message());
        }
    }

    private static BlockingQueue<StreamEvent> events(StreamEvent... events) {
        return new LinkedBlockingQueue<>(List.of(events));
    }
}
