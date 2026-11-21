// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.EventQueue;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;

import java.util.*;
import java.util.concurrent.BlockingQueue;

public class OpenAiClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.openai.client.OpenAIClient sdkClient;
    private final String model;
    private final boolean thinking;
    private final String systemPrompt;
    private volatile int maxOutputTokens;

    public OpenAiClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "OpenAI API key not found. Set it in .mewcode/config.yaml or via OPENAI_API_KEY env var.");
        }
        this.sdkClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .build();
        this.model = cfg.getModel();
        this.thinking = cfg.isThinking();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new EventQueue<StreamEvent>();

        queue.start(() -> {
            try {
                doStream(conv, tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try { queue.put(new StreamEvent.Error(classifyError(e).getMessage())); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        return queue;
    }

    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                          BlockingQueue<StreamEvent> queue) throws Exception {

        var paramsBuilder = ResponseCreateParams.builder()
                .model(model)
                .maxOutputTokens(maxOutputTokens)
                .instructions(systemPrompt)
                .inputOfResponse(buildInput(conv.getMessages()));

        if (thinking) {
            paramsBuilder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.HIGH)
                    .summary(Reasoning.Summary.DETAILED)
                    .build());
        }

        if (tools != null && !tools.isEmpty()) {
            for (var schema : tools) {
                paramsBuilder.addTool(buildFunctionTool(schema));
            }
        }

        try (StreamResponse<ResponseStreamEvent> streamResponse =
                     sdkClient.responses().createStreaming(paramsBuilder.build())) {

            var iterator = streamResponse.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();

                if (event.isOutputTextDelta()) {
                    queue.put(new StreamEvent.TextDelta(event.asOutputTextDelta().delta()));
                } else if (event.isOutputItemAdded()) {
                    var item = event.asOutputItemAdded().item();
                    if (item.isFunctionCall()) {
                        var fc = item.asFunctionCall();
                        queue.put(new StreamEvent.ToolCallStart(fc.callId(), fc.name()));
                    }
                } else if (event.isFunctionCallArgumentsDelta()) {
                    queue.put(new StreamEvent.ToolCallDelta(event.asFunctionCallArgumentsDelta().delta()));
                } else if (event.isReasoningSummaryTextDelta()) {
                    queue.put(new StreamEvent.ThinkingDelta(event.asReasoningSummaryTextDelta().delta()));
                } else if (event.isCompleted() || event.isIncomplete()) {
                    var resp = event.isCompleted() ? event.asCompleted().response() : event.asIncomplete().response();
                    String stopReason = "end_turn";
                    if (event.isIncomplete()) {
                        String reason = resp.incompleteDetails()
                                .flatMap(Response.IncompleteDetails::reason).map(Object::toString).orElse("unknown");
                        if (!"max_output_tokens".equals(reason)) {
                            throw new LlmException("Incomplete response: " + reason);
                        }
                        stopReason = "max_tokens";
                    } else {
                        for (var item : resp.output()) {
                            if (!item.isFunctionCall()) continue;
                            var call = item.asFunctionCall();
                            @SuppressWarnings("unchecked")
                            Map<String, Object> args = MAPPER.readValue(call.arguments(), Map.class);
                            if (args == null) throw new LlmException("Invalid tool arguments for " + call.name());
                            queue.put(new StreamEvent.ToolCallComplete(call.callId(), call.name(), args));
                        }
                    }
                    int inputTokens = 0, outputTokens = 0, cacheReadTokens = 0;
                    if (resp.usage().isPresent()) {
                        var usage = resp.usage().get();
                        outputTokens = (int) usage.outputTokens();
                        cacheReadTokens = (int) usage.inputTokensDetails().cachedTokens();
                        inputTokens = Math.max(0, (int) usage.inputTokens() - cacheReadTokens);
                    }
                    queue.put(new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens, cacheReadTokens, 0));
                    return;
                } else if (event.isError()) {
                    throw new LlmException(event.asError().message());
                } else if (event.isFailed()) {
                    var error = event.asFailed().response().error();
                    throw new LlmException(error.map(ResponseError::message).orElse("OpenAI response failed"));
                }
            }
        }
        throw new LlmException.NetworkException("OpenAI stream ended prematurely", null);
    }

    private List<ResponseInputItem> buildInput(List<Message> messages) {
        var result = new ArrayList<ResponseInputItem>();
        for (var msg : messages) {
            if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    result.add(ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                    .role(EasyInputMessage.Role.ASSISTANT)
                                    .content(msg.getContent())
                                    .build()));
                }
                for (var tu : msg.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (Exception e) {
                        argsJson = "{}";
                    }
                    result.add(ResponseInputItem.ofFunctionCall(
                            ResponseFunctionToolCall.builder()
                                    .callId(tu.toolUseId())
                                    .name(tu.toolName())
                                    .arguments(argsJson)
                                    .build()));
                }
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                for (var tr : msg.getToolResults()) {
                    result.add(ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                    .callId(tr.toolUseId())
                                    .output(tr.content())
                                    .build()));
                }
            } else {
                var role = "assistant".equals(msg.getRole())
                        ? EasyInputMessage.Role.ASSISTANT
                        : EasyInputMessage.Role.USER;
                result.add(ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                                .role(role)
                                .content(msg.getContent())
                                .build()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private FunctionTool buildFunctionTool(Map<String, Object> schema) {
        var builder = FunctionTool.builder()
                .name((String) schema.get("name"))
                .strict(false);
        if (schema.containsKey("description")) {
            builder.description(Objects.toString(schema.get("description"), ""));
        }
        var params = (Map<String, Object>) schema.getOrDefault("parameters",
                schema.getOrDefault("input_schema", Map.of()));
        builder.parameters(FunctionTool.Parameters.builder()
                .putAllAdditionalProperties(toJsonValueMap(params))
                .build());
        return builder.build();
    }

    private Map<String, JsonValue> toJsonValueMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return result;
    }

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof com.openai.errors.UnauthorizedException) {
            return new LlmException.AuthenticationException("Invalid API key: " + e.getMessage());
        }
        if (e instanceof com.openai.errors.RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof com.openai.errors.BadRequestException bre) {
            String msg = bre.getMessage() != null ? bre.getMessage().toLowerCase() : "";
            if (msg.contains("context_length_exceeded") || msg.contains("prompt is too long")) {
                return new LlmException.ContextTooLongException("Context too long: " + bre.getMessage());
            }
            return new LlmException("Bad request: " + bre.getMessage(), bre);
        }
        if (e instanceof com.openai.errors.OpenAIServiceException se) {
            if (se.statusCode() == 413) {
                return new LlmException.ContextTooLongException("Context too long: " + se.getMessage());
            }
            return new LlmException("API error (" + se.statusCode() + "): " + se.getMessage(), se);
        }
        if (e instanceof com.openai.errors.OpenAIIoException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }
}
