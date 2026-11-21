// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.permission;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PermissionChecker {

    private volatile PermissionMode mode;
    private final Path projectRoot;

    private final Set<Approval> allowAlwaysRules = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Approval(String toolName, String content) {}

    /** Parsed permission rules loaded from YAML files (mutable for dynamic append). */
    private volatile List<PermissionRule> fileRules;
    private volatile String planFilePath;

    /** A single parsed rule from a permissions.yaml file. */
    private record PermissionRule(String toolName, String pattern, RuleEffect effect) {
        boolean matches(String toolName, String content) {
            if (!this.toolName.equals(toolName)) {
                return false;
            }
            // Use glob matching for the pattern against the content
            try {
                return FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(content));
            } catch (Exception e) {
                // Fall back to simple wildcard matching if path parsing fails
                return content.equals(pattern);
            }
        }
    }

    private enum RuleEffect {
        ALLOW, DENY
    }

    private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
            "ToolSearch", "AskUserQuestion", "ExitPlanMode"
    );

    private static final Set<String> SAFE_COMMANDS = Set.of(
            "pwd", "true", "false", "git --version", "java -version", "java --version",
            "node -v", "python --version", "cargo --version", "rustc --version"
    );

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+if=.*of=/dev/"),
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile(">\\s*/dev/sd")
    );

    private static final Map<String, String> CONTENT_FIELDS = Map.of(
            "Bash", "command",
            "ReadFile", "file_path",
            "WriteFile", "file_path",
            "EditFile", "file_path",
            "Glob", "pattern",
            "Grep", "pattern"
    );

    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode;
        this.projectRoot = projectRoot;
        this.fileRules = List.copyOf(loadRules());
    }

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }
    public void setPlanFilePath(String path) { this.planFilePath = path; }

    public record CheckResult(PermissionMode.Decision decision, String reason) {
        public static CheckResult allow() { return new CheckResult(PermissionMode.Decision.ALLOW, ""); }

        public static CheckResult deny(String reason) { return new CheckResult(PermissionMode.Decision.DENY, reason); }
        public static CheckResult ask() { return new CheckResult(PermissionMode.Decision.ASK, ""); }
    }

    public CheckResult check(Tool tool, Map<String, Object> args) {
        return check(tool, args, projectRoot);
    }

    public CheckResult check(Tool tool, Map<String, Object> args, Path workDir) {
        String toolName = tool.name();
        String content = extractContent(toolName, args);

        String path = switch (toolName) {
            case "ReadFile", "WriteFile", "EditFile" -> stringArg(args, "file_path", "");
            case "Glob", "Grep" -> stringArg(args, "path", ".");
            default -> null;
        };
        if (path != null && !isPathAllowed(workDir, path.isEmpty() ? "." : path)) {
            return CheckResult.deny("Path outside allowed workspace: " + path);
        }

        PermissionMode currentMode = mode;
        boolean planWrite = false;
        if (currentMode == PermissionMode.PLAN) {
            planWrite = ("WriteFile".equals(toolName) || "EditFile".equals(toolName))
                    && isActivePlanFile(workDir, path);
            boolean readOnlyAgent = "Agent".equals(toolName)
                    && Set.of("plan", "explore").contains(stringArg(args, "subagent_type", ""))
                    && stringArg(args, "team_name", "").isBlank()
                    && stringArg(args, "isolation", "").isBlank();
            if (!planWrite && !readOnlyAgent && !PLAN_MODE_ALLOWED_TOOLS.contains(toolName)
                    && tool.category() != ToolCategory.READ
                    && !("Bash".equals(toolName) && isSafeCommand(content))) {
                return CheckResult.deny("This tool is unavailable in plan mode");
            }
        }

        // Layer 2: Dangerous command detection
        if ("Bash".equals(toolName) && content != null) {
            for (var pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    return CheckResult.deny("Dangerous command detected: " + pattern.pattern());
                }
            }
        }

        // Layer 4: File-based permission rules (last matching rule wins)
        if (content != null) {
            var rules = fileRules;
            for (int i = rules.size() - 1; i >= 0; i--) {
                PermissionRule rule = rules.get(i);
                if (rule.matches(toolName, content)) {
                    return switch (rule.effect) {
                        case ALLOW -> CheckResult.allow();
                        case DENY -> CheckResult.deny("Denied by rule: " + rule.toolName + "(" + rule.pattern + ")");
                    };
                }
            }
        }

        // Layer 4b: Allow-always rules (session-level)
        if (planWrite || currentMode == PermissionMode.PLAN && "Agent".equals(toolName)
                || "Bash".equals(toolName) && isSafeCommand(content)
                || allowAlwaysRules.contains(new Approval(toolName, content))) {
            return CheckResult.allow();
        }

        // Layer 5: Permission mode matrix
        if ("SendMessage".equals(toolName) && currentMode != PermissionMode.PLAN) return CheckResult.allow();

        var decision = currentMode.decide(tool.category());
        return switch (decision) {
            case ALLOW -> CheckResult.allow();
            case DENY -> CheckResult.deny("Denied by permission mode: " + mode);
            case ASK -> CheckResult.ask();
        };
    }

    public void addAllowAlwaysRule(String toolName, String content) {
        allowAlwaysRules.add(new Approval(toolName, content));
    }

    // --- Rule loading from YAML files ---

    private static final Pattern RULE_PATTERN = Pattern.compile("^(\\w+)\\((.+)\\)$");

    /**
     * Load permission rules from user-level, project-level, and local YAML files.
     * User-level: ~/.mewcode/permissions.yaml
     * Project-level: {projectRoot}/.mewcode/permissions.yaml
     * Local-level: {projectRoot}/.mewcode/permissions.local.yaml
     *
     * Rules are loaded in order; the last matching rule wins when evaluated.
     */
    private List<PermissionRule> loadRules() {
        var rules = new ArrayList<PermissionRule>();

        // User-level rules
        Path userHome = Path.of(System.getProperty("user.home"));
        Path userFile = userHome.resolve(".mewcode").resolve("permissions.yaml");
        rules.addAll(loadRulesFile(userFile));

        // Project-level rules
        if (projectRoot != null) {
            Path projectFile = projectRoot.resolve(".mewcode").resolve("permissions.yaml");
            rules.addAll(loadRulesFile(projectFile));

            // Local-level rules (gitignored, session-persistent)
            Path localFile = projectRoot.resolve(".mewcode").resolve("permissions.local.yaml");
            rules.addAll(loadRulesFile(localFile));
        }

        return new ArrayList<>(rules);
    }

    public synchronized void appendLocalRule(String toolName, String pattern) {
        if (projectRoot == null) return;
        Path localFile = projectRoot.resolve(".mewcode").resolve("permissions.local.yaml");
        try {
            Files.createDirectories(localFile.getParent());
            var rules = new ArrayList<>(loadRulesFile(localFile));
            rules.add(new PermissionRule(toolName, pattern, RuleEffect.ALLOW));

            var entries = new ArrayList<Map<String, String>>();
            for (var r : rules) {
                entries.add(Map.of("rule", r.toolName + "(" + r.pattern + ")", "effect",
                        r.effect == RuleEffect.ALLOW ? "allow" : "deny"));
            }
            var yaml = new Yaml();
            Files.writeString(localFile, yaml.dump(entries));
            // Reload
            fileRules = List.copyOf(loadRules());
        } catch (IOException ignored) {}
    }

    /**
     * Parse a single YAML permissions file into a list of rules.
     * Expected format: a YAML list of maps with "rule" and "effect" keys.
     * Example:
     *   - rule: "Bash(git *)"
     *     effect: allow
     *   - rule: "WriteFile(/etc/*)"
     *     effect: deny
     */
    @SuppressWarnings("unchecked")
    private List<PermissionRule> loadRulesFile(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return List.of();
        }

        Yaml yaml = new Yaml();
        Object parsed;
        try {
            parsed = yaml.load(content);
        } catch (Exception e) {
            return List.of();
        }

        if (!(parsed instanceof List<?> entries)) {
            return List.of();
        }

        var rules = new ArrayList<PermissionRule>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object ruleObj = map.get("rule");
            Object effectObj = map.get("effect");
            if (!(ruleObj instanceof String ruleStr) || !(effectObj instanceof String effectStr)) {
                continue;
            }

            RuleEffect effect;
            if ("allow".equals(effectStr)) {
                effect = RuleEffect.ALLOW;
            } else if ("deny".equals(effectStr)) {
                effect = RuleEffect.DENY;
            } else {
                continue;
            }

            Matcher m = RULE_PATTERN.matcher(ruleStr.trim());
            if (!m.matches()) {
                continue;
            }
            rules.add(new PermissionRule(m.group(1), m.group(2), effect));
        }
        return rules;
    }

    private boolean isSafeCommand(String command) {
        return command != null && SAFE_COMMANDS.contains(command.strip());
    }

    private boolean isActivePlanFile(Path workDir, String path) {
        if (path == null || path.isBlank() || planFilePath == null) return false;
        try {
            Path target = workDir.resolve(path).toAbsolutePath().normalize();
            Path plan = workDir.resolve(planFilePath).toAbsolutePath().normalize();
            return target.equals(plan) && target.equals(resolveExistingAncestors(target));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean isPathAllowed(Path workDir, String path) {
        try {
            Path root = workDir.toRealPath();
            Path target = workDir.resolve(path).toAbsolutePath().normalize();
            return target.startsWith(workDir.toAbsolutePath().normalize())
                    && resolveExistingAncestors(target).startsWith(root);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path resolveExistingAncestors(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException e) {
            Path parent = path.getParent();
            if (parent == null || !Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) throw e;
            return resolveExistingAncestors(parent).resolve(path.getFileName()).normalize();
        }
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = CONTENT_FIELDS.get(toolName);
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    public String describeToolAction(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "Bash" -> "Execute: " + stringArg(args, "command", "");
            case "ReadFile" -> "Read: " + stringArg(args, "file_path", "");
            case "WriteFile" -> "Write: " + stringArg(args, "file_path", "");
            case "EditFile" -> "Edit: " + stringArg(args, "file_path", "");
            case "Glob" -> "Glob: " + stringArg(args, "pattern", "");
            case "Grep" -> "Grep: " + stringArg(args, "pattern", "");
            default -> toolName;
        };
    }
}
