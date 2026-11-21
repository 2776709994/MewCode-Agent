// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

public class SessionManager {

    /**
     * TYPE_COMPACT_BOUNDARY marks a session record as a compaction boundary
     * rather than a plain conversation message. A boundary record's content
     * holds a JSON blob (see {@link CompactBoundary}) carrying the summary text
     * plus the recent tail (keep) preserved verbatim at compaction time. Plain
     * messages leave {@code type} null/empty, so old sessions and normal turns
     * are unaffected (append-only, backward-compatible).
     */
    public static final String TYPE_COMPACT_BOUNDARY = "compact_boundary";

    /**
     * A session record. {@code type} distinguishes record kinds: empty/null (the
     * default) means a plain conversation message; {@link #TYPE_COMPACT_BOUNDARY}
     * means {@code content} is a {@link CompactBoundary} JSON blob written by
     * {@link #saveCompactBoundary}.
     */
    public record SessionMessage(String role, String type, String content, long timestamp, Turn turn) {
        public SessionMessage(String role, String type, String content, long timestamp) {
            this(role, type, content, timestamp, null);
        }
        /** Convenience constructor for plain (non-boundary) messages. */
        public SessionMessage(String role, String content, long timestamp) {
            this(role, null, content, timestamp);
        }

        public boolean isCompactBoundary() {
            return TYPE_COMPACT_BOUNDARY.equals(type);
        }
    }

    public record Turn(List<ThinkingBlock> thinking, List<ToolUseBlock> calls, List<ToolResultBlock> results) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KeepMessage(String role, String content, List<ThinkingBlock> thinking,
                              List<ToolUseBlock> calls, List<ToolResultBlock> results) {
        public KeepMessage(String role, String content) {
            this(role, content, null, null, null);
        }

        public KeepMessage(Message message) {
            this(message.getRole(), message.getContent(), message.getThinkingBlocks(),
                    message.getToolUses(), message.getToolResults());
        }

        private Message toMessage() {
            var message = new Message(role, content == null ? "" : content);
            message.setThinkingBlocks(thinking);
            message.setToolUses(calls);
            message.setToolResults(results);
            return message;
        }
    }

    /**
     * Structured payload stored (as JSON) in the content of a boundary record.
     * {@code summary} is the LLM-produced summary of the older prefix; {@code keep}
     * is the recent tail kept verbatim. On resume the compacted state is rebuilt
     * as: [user message = summary] + keep + any plain messages appended after the
     * boundary.
     */
    public record CompactBoundary(String summary, List<KeepMessage> keep) {}

    /** Result of {@link #findLastCompactBoundary}: the boundary and the plain messages after it. */
    public record BoundaryScan(CompactBoundary boundary, List<SessionMessage> after, boolean found) {}

    public record SessionInfo(String id, String firstMessage, int messageCount,
                              long fileSize, String gitBranch, Instant modTime) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path sessionsDir(String workDir) {
        return Path.of(workDir, ".mewcode", "sessions");
    }

    // ---- ID generation ----

    public static String newId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID();
    }

    // ---- Persistence ----

    public static void saveMessage(String workDir, String sessionId, String role, String content) {
        saveRecord(workDir, sessionId, role, null, content, null);
    }

    public static void saveTurn(String workDir, String sessionId, String text, List<ThinkingBlock> thinking,
                                List<ToolUseBlock> calls, List<ToolResultBlock> results) {
        saveRecord(workDir, sessionId, "assistant", null, text, new Turn(thinking, calls, results));
    }

    public static Path sessionDataDir(String workDir, String sessionId) {
        if (!validId(sessionId)) throw new IllegalArgumentException("Invalid session id");
        return sessionsDir(workDir).resolve(sessionId);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]+");
    }

    /**
     * Append a compaction boundary record so a later resume can rebuild the
     * compacted state (summary + kept tail) instead of replaying the full
     * pre-compaction transcript. Append-only: the original prefix messages stay
     * in the file but won't be replayed past this boundary (see
     * {@link #findLastCompactBoundary}). The summary + keep are inlined into the
     * record's content as a {@link CompactBoundary} JSON blob. No-op when
     * workDir/sessionId is null/blank (tests, one-shot callers).
     */
    public static void saveCompactBoundary(String workDir, String sessionId,
                                           String summary, List<KeepMessage> keep) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String blob = MAPPER.writeValueAsString(
                    new CompactBoundary(summary, keep == null ? List.of() : keep));
            saveRecord(workDir, sessionId, "system", TYPE_COMPACT_BOUNDARY, blob, null);
        } catch (JsonProcessingException ignored) {
            // best-effort: a failed boundary just means the next resume replays
            // verbatim, which is still correct (backward-compatible).
        }
    }

    private static synchronized void saveRecord(String workDir, String sessionId,
                                                  String role, String type, String content, Turn turn) {
        if (workDir == null || !validId(sessionId)) return;
        try {
            Path baseDir = sessionsDir(workDir);
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(sessionId + ".jsonl");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            // omit `type` for plain messages so old readers and old sessions are
            // unaffected (matches Go's `omitempty`).
            if (type != null && !type.isEmpty()) {
                line.put("type", type);
            }
            line.put("content", content);
            line.put("ts", Instant.now().getEpochSecond());
            if (turn != null) line.put("turn", turn);
            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort, same as the Go version
        }
    }

    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        if (!validId(sessionId)) return List.of();
        Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    var record = MAPPER.readTree(line);
                    String role = record.path("role").asText(null);
                    String type = record.path("type").asText(null);
                    String content = record.path("content").asText("");
                    long ts = record.path("ts").asLong();
                    Turn turn = record.hasNonNull("turn") ? MAPPER.treeToValue(record.get("turn"), Turn.class) : null;
                    if (role != null && (!content.isEmpty() || turn != null)) {
                        messages.add(new SessionMessage(role, type, content, ts, turn));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // return whatever we collected so far
        }
        return messages;
    }

    // ---- Compaction-boundary scanning ----

    /**
     * Scan the loaded records for the LAST compaction boundary. Returns the
     * parsed boundary plus the plain (non-boundary) messages appended after it.
     * When no boundary exists (or its blob is corrupt) {@code found} is false and
     * the caller should replay all records verbatim — backward-compatible with
     * old sessions that have no boundary records.
     */
    public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!messages.get(i).isCompactBoundary()) continue;
            try {
                var boundary = MAPPER.readValue(messages.get(i).content(), CompactBoundary.class);
                if (boundary == null || boundary.summary() == null || boundary.keep() == null
                        || boundary.keep().stream().anyMatch(Objects::isNull)) continue;
                var after = messages.subList(i + 1, messages.size()).stream()
                        .filter(message -> !message.isCompactBoundary()).toList();
                return new BoundaryScan(boundary, after, true);
            } catch (IOException ignored) {
                // Try the previous valid boundary.
            }
        }
        return new BoundaryScan(null, List.of(), false);
    }

    // ---- Conversation rebuild ----

    /**
     * Compaction-aware rebuild. If the session contains a {@code compact_boundary},
     * the live conversation is the compacted state — [summary as user message] +
     * kept tail + any plain messages appended after the boundary — and the
     * original pre-compaction prefix is NOT replayed (it stays in the file for
     * audit). Without a boundary (old sessions) everything is replayed verbatim.
     */
    public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
        BoundaryScan scan = findLastCompactBoundary(messages);
        if (!scan.found()) {
            return replay(messages);
        }
        ConversationManager conversation = new ConversationManager();
        String resumeSummary = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n"
                + scan.boundary().summary();
        if (!scan.boundary().keep().isEmpty()) {
            resumeSummary += "\n\n近期消息已原样保留。";
        }
        conversation.addUserMessage(resumeSummary);
        for (KeepMessage kept : scan.boundary().keep()) {
            conversation.getMessagesMutable().add(kept.toMessage());
        }
        replay(conversation, scan.after());
        return conversation;
    }

    private static ConversationManager replay(List<SessionMessage> messages) {
        var conversation = new ConversationManager();
        replay(conversation, messages);
        return conversation;
    }

    private static void replay(ConversationManager conversation, List<SessionMessage> messages) {
        for (SessionMessage msg : messages) {
            if (msg.isCompactBoundary()) continue;
            var turn = msg.turn();
            if (turn != null) {
                conversation.addAssistantFull(msg.content(), turn.thinking(), turn.calls());
                if (turn.results() != null && !turn.results().isEmpty()) {
                    conversation.addToolResultsMessage(turn.results());
                }
            } else if ("assistant".equals(msg.role())) {
                conversation.addAssistantMessage(msg.content());
            } else {
                conversation.addUserMessage(msg.content());
            }
        }
    }

    // ---- Listing ----

    public static List<SessionInfo> listSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        String branch = currentGitBranch(workDir);
        List<SessionInfo> sessions = new ArrayList<>();
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                     try {
                         long fileSize = Files.size(p);
                         Instant modTime = Files.getLastModifiedTime(p).toInstant();
                         List<SessionMessage> msgs = loadSession(workDir, id);
                         String first = msgs.stream()
                                 .filter(m -> "user".equals(m.role()))
                                 .map(SessionMessage::content)
                                 .findFirst()
                                 .orElse("");
                         sessions.add(new SessionInfo(id, first, msgs.size(),
                                 fileSize, branch, modTime));
                     } catch (IOException ignored) {
                         // skip this file
                     }
                 });
        } catch (IOException ignored) {
            // return empty
        }
        sessions.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return sessions;
    }

    // ---- Git branch ----

    public static String currentGitBranch(String workDir) {
        try {
            Process proc = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int code = proc.waitFor();
            return code == 0 ? output : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    // ---- Formatting helpers ----

    public static String formatRelativeTime(Instant t) {
        Duration d = Duration.between(t, Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = hours / 24;
        if (days < 7) {
            return days == 1 ? "1 day ago" : days + " days ago";
        }
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return kb == (long) kb
                    ? String.format("%.0fKB", kb)
                    : String.format("%.1fKB", kb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.1fMB", mb);
    }

    // ---- Search ----

    public static boolean matchesSearch(SessionInfo s, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        return s.firstMessage().toLowerCase().contains(q)
                || s.id().toLowerCase().contains(q);
    }
}
