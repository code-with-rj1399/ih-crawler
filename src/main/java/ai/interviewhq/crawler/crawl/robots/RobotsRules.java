package ai.interviewhq.crawler.crawl.robots;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal robots.txt parser: User-agent groups, Allow/Disallow, Crawl-delay.
 * Longest matching path wins.
 */
public final class RobotsRules {

    public record Decision(boolean allowed, Integer crawlDelaySeconds) {
        public static Decision allow(Integer delay) {
            return new Decision(true, delay);
        }

        public static Decision deny(Integer delay) {
            return new Decision(false, delay);
        }
    }

    private record Rule(boolean allow, String path) {
    }

    private record Group(List<String> agents, List<Rule> rules, Integer crawlDelay) {
    }

    private final List<Group> groups;
    private final Integer wildcardDelay;

    private RobotsRules(List<Group> groups, Integer wildcardDelay) {
        this.groups = groups;
        this.wildcardDelay = wildcardDelay;
    }

    public static RobotsRules allowAll() {
        return new RobotsRules(List.of(), null);
    }

    public static RobotsRules parse(String body) {
        if (body == null || body.isBlank()) {
            return allowAll();
        }
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        Integer delay = null;
        boolean inGroup = false;

        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String field = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            switch (field) {
                case "user-agent" -> {
                    if (inGroup && (!rules.isEmpty() || delay != null)) {
                        groups.add(new Group(List.copyOf(agents), List.copyOf(rules), delay));
                        agents = new ArrayList<>();
                        rules = new ArrayList<>();
                        delay = null;
                    }
                    inGroup = true;
                    agents.add(value.toLowerCase(Locale.ROOT));
                }
                case "allow" -> {
                    inGroup = true;
                    rules.add(new Rule(true, value.isBlank() ? "/" : value));
                }
                case "disallow" -> {
                    inGroup = true;
                    rules.add(new Rule(false, value));
                }
                case "crawl-delay" -> {
                    inGroup = true;
                    try {
                        delay = (int) Math.ceil(Double.parseDouble(value));
                    } catch (NumberFormatException ignored) {
                        // skip malformed crawl-delay
                    }
                }
                default -> {
                    // sitemap and others ignored
                }
            }
        }
        if (inGroup) {
            groups.add(new Group(List.copyOf(agents), List.copyOf(rules), delay));
        }
        Integer starDelay = groups.stream()
                .filter(g -> g.agents.contains("*"))
                .map(Group::crawlDelay)
                .filter(d -> d != null)
                .findFirst()
                .orElse(null);
        return new RobotsRules(List.copyOf(groups), starDelay);
    }

    public Decision decide(String userAgent, String path) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        String product = productToken(ua);
        Group matched = findGroup(product);
        if (matched == null) {
            matched = findGroup("*");
        }
        if (matched == null) {
            return Decision.allow(wildcardDelay);
        }
        String requestPath = (path == null || path.isBlank()) ? "/" : path;
        Rule best = null;
        for (Rule rule : matched.rules) {
            if (pathMatches(rule.path, requestPath)) {
                if (best == null || rule.path.length() > best.path.length()
                        || (rule.path.length() == best.path.length() && rule.allow && !best.allow)) {
                    best = rule;
                }
            }
        }
        boolean allowed = best == null || best.allow || best.path.isBlank();
        return new Decision(allowed, matched.crawlDelay);
    }

    private Group findGroup(String token) {
        Group exact = null;
        Group star = null;
        Group prefix = null;
        int prefixLen = -1;
        for (Group group : groups) {
            for (String agent : group.agents) {
                if (agent.equals("*")) {
                    star = group;
                } else if (agent.equals(token)) {
                    exact = group;
                } else if (token.startsWith(agent) && agent.length() > prefixLen) {
                    prefix = group;
                    prefixLen = agent.length();
                }
            }
        }
        if (exact != null) {
            return exact;
        }
        if (prefix != null) {
            return prefix;
        }
        return star;
    }

    static String productToken(String ua) {
        int slash = ua.indexOf('/');
        int space = ua.indexOf(' ');
        int end = ua.length();
        if (slash > 0) {
            end = slash;
        } else if (space > 0) {
            end = space;
        }
        return ua.substring(0, end).trim();
    }

    static boolean pathMatches(String pattern, String path) {
        if (pattern == null || pattern.isEmpty()) {
            // "Disallow:" with empty path means allow all.
            return false;
        }
        String p = pattern;
        boolean endAnchor = p.endsWith("$");
        if (endAnchor) {
            p = p.substring(0, p.length() - 1);
        }
        String regex = globToRegex(p);
        if (endAnchor) {
            regex = regex + "$";
        } else if (!regex.endsWith(".*")) {
            regex = regex + ".*";
        }
        return path.matches(regex);
    }

    private static String globToRegex(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '$' -> sb.append('$');
                case '.', '(', ')', '+', '|', '{', '}', '[', ']', '\\', '?' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
