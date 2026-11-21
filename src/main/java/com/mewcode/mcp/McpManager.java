// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.mcp;

import com.mewcode.config.McpServerConfig;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public class McpManager {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

    public record ServerInfo(String name, String instructions) {}
    public record ConnectResult(List<Tool> tools, List<ServerInfo> servers, List<String> errors) {}

    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
    private volatile boolean closed;

    public McpManager(List<McpServerConfig> configs) {
        if (configs != null) {
            for (var cfg : configs) this.configs.put(cfg.getName(), cfg);
        }
    }

    public ConnectResult connectAll() {
        var tools = new ArrayList<Tool>();
        var servers = new ArrayList<ServerInfo>();
        var errors = new ArrayList<String>();
        var registeredNames = new HashSet<String>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            var cfg = entry.getValue();

            if (closed) break;
            McpSyncClient client = null;
            try {
                client = createClient(cfg);
                client.initialize();
                String instructions = client.getServerInstructions();
                var serverTools = new ArrayList<Tool>();
                var cursors = new HashSet<String>();
                String cursor = null;
                do {
                    var result = cursor == null ? client.listTools() : client.listTools(cursor);
                    if (result == null) break;
                    if (result.tools() != null) {
                        for (var sdkTool : result.tools()) serverTools.add(new McpToolWrapper(name, sdkTool, client));
                    }
                    cursor = result.nextCursor();
                    if (cursor != null && !cursors.add(cursor)) {
                        throw new IllegalStateException("Server returned a repeated tool cursor");
                    }
                } while (cursor != null && !cursor.isBlank() && !closed);
                synchronized (clients) {
                    if (closed || clients.containsKey(name)) continue;
                    clients.put(name, client);
                }
                client = null;
                servers.add(new ServerInfo(name, instructions != null ? instructions : ""));
                for (Tool tool : serverTools) {
                    if (registeredNames.add(tool.name())) {
                        tools.add(tool);
                    } else {
                        errors.add("Duplicate MCP tool name: " + tool.name());
                    }
                }
            } catch (Exception e) {
                errors.add("MCP server '" + name + "': " + e.getMessage());
            } finally {
                if (client != null) closeClient(client);
            }
        }

        return new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors));
    }

    public List<String> registerAllTools(ToolRegistry registry) {
        var result = connectAll();
        for (var t : result.tools()) registry.register(t);
        return result.errors();
    }

    public void shutdown() {
        List<McpSyncClient> toClose;
        synchronized (clients) {
            closed = true;
            toClose = List.copyOf(clients.values());
            clients.clear();
        }
        toClose.forEach(McpManager::closeClient);
    }

    private static void closeClient(McpSyncClient client) {
        try { client.close(); } catch (Exception ignored) {}
    }

    private McpSyncClient createClient(McpServerConfig cfg) {
        io.modelcontextprotocol.spec.McpClientTransport transport;

        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
            if (cfg.getArgs() != null) {
                paramsBuilder.args(cfg.getArgs());
            }
            if (cfg.getEnv() != null) {
                var resolvedEnv = new HashMap<String, String>();
                for (var e : cfg.getEnv().entrySet()) {
                    resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
                paramsBuilder.env(resolvedEnv);
            }
            transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());
        } else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            var httpBuilder = HttpClientStreamableHttpTransport.builder(cfg.getUrl());
            if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                httpBuilder.customizeRequest(rb -> {
                    for (var e : cfg.getHeaders().entrySet()) {
                        rb.header(e.getKey(), resolveEnvVars(e.getValue()));
                    }
                });
            }
            transport = httpBuilder.build();
        } else {
            throw new IllegalArgumentException("Neither command nor url configured");
        }

        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("mewcode", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
    }

    private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
            "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");

    static String windowsSafe(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return command;
        String base = command.toLowerCase();
        if (WIN_CMD_SUFFIXED.contains(base)) return command + ".cmd";
        return command;
    }

    public static String sanitizeName(String name) {
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    static String resolveEnvVars(String value) {
        if (value == null) return null;
        return ENV_VAR.matcher(value).replaceAll(m -> {
            String env = System.getenv(m.group(1));
            return java.util.regex.Matcher.quoteReplacement(env != null ? env : m.group(0));
        });
    }

    // ── MCP Tool Wrapper ────────────────────────────────────────────────

    private static class McpToolWrapper implements Tool {
        private final String serverName;
        private final McpSchema.Tool sdkTool;
        private final McpSyncClient client;

        McpToolWrapper(String serverName, McpSchema.Tool sdkTool, McpSyncClient client) {
            this.serverName = serverName;
            this.sdkTool = sdkTool;
            this.client = client;
        }

        @Override public String name() {
            String fullName = "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
            if (fullName.length() <= 64) return fullName;
            return fullName.substring(0, 27) + "-"
                    + UUID.nameUUIDFromBytes(fullName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override public String description() {
            return sdkTool.description() != null ? sdkTool.description() : "";
        }

        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public boolean shouldDefer() { return true; }

        @Override public Map<String, Object> schema() {
            var input = new LinkedHashMap<String, Object>();
            var jsonSchema = sdkTool.inputSchema();
            if (jsonSchema != null) {
                if (jsonSchema.type() != null) input.put("type", jsonSchema.type());
                if (jsonSchema.properties() != null) input.put("properties", jsonSchema.properties());
                if (jsonSchema.required() != null) input.put("required", jsonSchema.required());
                if (jsonSchema.additionalProperties() != null) input.put("additionalProperties", jsonSchema.additionalProperties());
            }
            if (input.isEmpty()) {
                input.put("type", "object");
                input.put("properties", Map.of());
            }
            return Map.of("name", name(), "description", description(), "input_schema", input);
        }

        @Override public ToolResult execute(Map<String, Object> args) {
            try {
                var request = new McpSchema.CallToolRequest(
                        sdkTool.name(), args != null ? args : Map.of());
                var result = client.callTool(request);
                String text = extractTextContent(result);
                boolean isError = result.isError() != null && result.isError();
                return isError ? ToolResult.error(text) : ToolResult.success(text);
            } catch (Exception e) {
                return ToolResult.error("MCP tool call failed: " + e.getMessage());
            }
        }
    }

    private static String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) return "(no output)";
        var sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(tc.text());
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }
}
