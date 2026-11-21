// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FileMailBox {

    public record MailMessage(String from, String text, String timestamp,
                              boolean read, String color, String summary) {
        public MailMessage(String from, String text) {
            this(from, text, DateTimeFormatter.ISO_INSTANT.format(Instant.now()), false, "", "");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 10;
    private static final int MIN_SLEEP_MS = 5;
    private static final int MAX_SLEEP_MS = 100;

    private final Path baseDir;

    public FileMailBox(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {}
    }

    private Path inboxPath(String agentId) {
        if (!TeamManager.isValidName(agentId)) throw new IllegalArgumentException("Invalid recipient name");
        return baseDir.resolve(agentId + ".json");
    }

    private Path lockPath(String agentId) {
        return inboxPath(agentId).resolveSibling(agentId + ".json.lock");
    }

    public boolean send(String recipient, MailMessage msg) {
        return withLock(recipient, messages -> {
            var m = new MailMessage(msg.from(), msg.text(), msg.timestamp(), false, msg.color(), msg.summary());
            messages.add(m);
            return messages;
        });
    }

    public List<MailMessage> readUnread(String agentId) {
        List<MailMessage> messages = readInbox(agentId);
        List<MailMessage> unread = new ArrayList<>();
        for (var m : messages) {
            if (!m.read()) unread.add(m);
        }
        return unread;
    }

    public void markAllRead(String agentId) {
        withLock(agentId, messages -> {
            List<MailMessage> updated = new ArrayList<>();
            for (var m : messages) {
                updated.add(new MailMessage(m.from(), m.text(), m.timestamp(), true, m.color(), m.summary()));
            }
            return updated;
        });
    }

    public List<MailMessage> drainUnread(String agentId) {
        var unread = new ArrayList<MailMessage>();
        boolean saved = withLock(agentId, messages -> {
            for (int i = 0; i < messages.size(); i++) {
                var message = messages.get(i);
                if (message.read()) continue;
                unread.add(message);
                messages.set(i, new MailMessage(message.from(), message.text(), message.timestamp(),
                        true, message.color(), message.summary()));
            }
            return unread.isEmpty() ? null : messages;
        });
        return saved ? List.copyOf(unread) : List.of();
    }

    private interface MutationFn {
        List<MailMessage> apply(List<MailMessage> messages);
    }

    private boolean withLock(String agentId, MutationFn fn) {
        Path lock = lockPath(agentId);
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var held = channel.tryLock()) {
                if (held != null) {
                    var updated = fn.apply(readInboxFile(agentId));
                    if (updated != null) writeInbox(agentId, updated);
                    return true;
                }
            } catch (OverlappingFileLockException ignored) {
                // A thread in this process holds the recipient lock.
            } catch (IOException e) {
                return false;
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(MIN_SLEEP_MS, MAX_SLEEP_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private List<MailMessage> readInbox(String agentId) {
        try {
            return readInboxFile(agentId);
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<MailMessage> readInboxFile(String agentId) throws IOException {
        Path path = inboxPath(agentId);
        if (Files.notExists(path)) return new ArrayList<>();
        if (Files.isSymbolicLink(path)) throw new IOException("Mailbox must be a regular file");
        var messages = MAPPER.readValue(path.toFile(), new TypeReference<List<MailMessage>>() {});
        if (messages == null || messages.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IOException("Invalid mailbox contents");
        }
        return messages;
    }

    private void writeInbox(String agentId, List<MailMessage> messages) throws IOException {
        Path path = inboxPath(agentId);
        Path temporary = Files.createTempFile(baseDir, "inbox-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), messages);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
