# MewCode

MewCode 是一个 AI Coding Agent 的完整实现，帮助你理解 AI Agent 的核心原理与工程实践。

## 环境要求

- Java 21+
- Git。Windows 上的 Bash 工具优先使用 Git for Windows 自带的 Git Bash。
- 项目自带 Gradle Wrapper。

## 快速开始

### 1. 配置

编辑 `.mewcode/config.yaml`，填入你的 LLM 提供商信息：

```yaml
providers:
  - name: anthropic-official
    protocol: anthropic                          # 可选 anthropic / openai / openai-compat
    base_url: https://your-api-provider.com/api/anthropic
    api_key: "your-api-key-here"
    model: claude-sonnet-4-6
    thinking: true                               # 是否开启 extended thinking

mcp_servers:
  - name: context7
    command: npx
    args: ["-y", "@upstash/context7-mcp"]
```

**说明：**
- `protocol`：`anthropic` 对应 Anthropic Messages，`openai` 对应 OpenAI Responses，`openai-compat` 对应 Chat Completions。
- `base_url`：你的 API 地址
- `api_key`：你的 API Key
- `model`：模型名称
- `mcp_servers`：每项指定 `name`，通过 `command` 与可选 `args` 启动本地服务，或通过 `url` 连接 HTTP 服务。
- `permission_mode`：默认 `default`，也可设置为 `accept_edits`、`plan` 或 `bypass`。

### 2. 构建 & 运行

```bash
./gradlew shadowJar
java -jar build/libs/mewcode.jar
```

### 3. 运行测试

```bash
./gradlew test
```

会话与压缩记录写入项目的 `.mewcode/sessions/`，格式为 JSONL。自动提取的用户和项目记忆分别写入对应的 `auto_memory.json`。Worktree 为 Agent 分配独立的 Git 工作目录。获批执行的 Shell 命令使用当前工作目录，并继承运行用户的系统权限。
