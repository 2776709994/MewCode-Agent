// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Lightweight worktree API for sub-agents. Does NOT touch global session
 * state (WorktreeSessionStore).
 */
public final class AgentWorktree {

    private static final Logger log = Logger.getLogger(AgentWorktree.class.getName());

    public record Result(String worktreePath, String worktreeBranch, String headCommit, String gitRoot) {}

    private AgentWorktree() {}

    /**
     * Creates a worktree for a sub-agent.
     */
    public static Result create(String slug, String repoRoot, List<String> symlinkDirs) throws Exception {
        SlugValidator.validate(slug);

        Path wtPath = Path.of(repoRoot, ".mewcode", "worktrees", SlugValidator.flatten(slug));
        String branch = "worktree-" + SlugValidator.flatten(slug);

        if (Files.exists(wtPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Worktree path already exists: " + wtPath);
        }

        Files.createDirectories(wtPath.getParent());

        ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", "-b", branch, "--", wtPath.toString(), "HEAD");
        pb.directory(Path.of(repoRoot).toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        var result = com.mewcode.ProcessExecution.capture(pb, java.time.Duration.ofSeconds(60));
        if (result.exitCode() != 0) {
            throw new IOException("Failed to create agent worktree: " + result.output());
        }

        PostCreationSetup.perform(repoRoot, wtPath.toString(), symlinkDirs);

        String head = readHead(wtPath.toString());
        return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
    }

    /**
     * Removes a worktree created by {@link #create}.
     */
    public static boolean remove(String worktreePath, String worktreeBranch, String gitRoot) {
        if (gitRoot == null || gitRoot.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", "--", worktreePath);
            pb.directory(Path.of(gitRoot).toFile());
            return com.mewcode.ProcessExecution.capture(pb, java.time.Duration.ofSeconds(30)).exitCode() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.fine("Failed to remove agent worktree: " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds the notice text for sub-agents running in isolated worktrees.
     */
    public static String buildNotice(String parentCwd, String worktreeCwd) {
        return "You've inherited the conversation context above from a parent agent working in %s. "
                .formatted(parentCwd)
                + "You are operating in an isolated git worktree at %s — same repository, same relative "
                .formatted(worktreeCwd)
                + "file structure, separate working copy. Paths in the inherited context refer to the "
                + "parent's working directory; translate them to your worktree root. Re-read files before "
                + "editing if the parent may have modified them since they appear in the context. Your "
                + "changes stay in this worktree and will not affect the parent's files.";
    }

    private static String readHead(String worktreePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(Path.of(worktreePath).toFile());
            var result = com.mewcode.ProcessExecution.capture(pb, java.time.Duration.ofSeconds(10));
            return result.exitCode() == 0 ? result.output().strip() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
