package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatStreamingTest {
    @Test
    void reportsTrailingUsageAndPreservesOutputLimitReason() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}

                data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":20}}}

                data: [DONE]

                """;
        withStream(response, queue -> {
            assertEquals(new StreamEvent.TextDelta("partial"), queue.poll(5, TimeUnit.SECONDS));
            assertEquals(new StreamEvent.StreamEnd("max_tokens", 100, 10, 20, 0), queue.poll(5, TimeUnit.SECONDS));
        });
    }

    @Test
    void prematureEofReportsAnErrorWithoutCompletingToolCalls() throws Exception {
        withStream("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n", queue -> {
            assertInstanceOf(StreamEvent.TextDelta.class, queue.poll(5, TimeUnit.SECONDS));
            assertInstanceOf(StreamEvent.Error.class, queue.poll(5, TimeUnit.SECONDS));
        });
    }

    private static void withStream(String response, StreamAssertions assertions) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().close();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            var cfg = new ProviderConfig();
            cfg.setApiKey("test-key");
            cfg.setModel("test-model");
            cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var conv = new ConversationManager();
            conv.addUserMessage("hello");
            assertions.verify(new OpenAiCompatClient(cfg, "").stream(conv, List.of()));
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface StreamAssertions {
        void verify(java.util.concurrent.BlockingQueue<StreamEvent> events) throws Exception;
    }
}
