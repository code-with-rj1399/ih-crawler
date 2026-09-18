package ai.interviewhq.crawler.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Accepts Neon/RDS-style {@code postgres://} / {@code postgresql://} URLs as well as
 * standard {@code jdbc:postgresql://} URLs (optionally with user/password in the query).
 */
public final class JdbcUrlParser {

    public record Parsed(String jdbcUrl, String username, String password) {
    }

    private JdbcUrlParser() {
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("database URL is blank");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("jdbc:postgresql:")) {
            return parseJdbc(trimmed);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("postgres://") || lower.startsWith("postgresql://")) {
            return parseLibpq(trimmed);
        }
        throw new IllegalArgumentException("Unsupported database URL scheme: " + schemeOf(trimmed));
    }

    private static Parsed parseLibpq(String raw) {
        String normalized = raw.replaceFirst("(?i)^postgres://", "postgresql://");
        URI uri = URI.create(normalized);
        String user = null;
        String pass = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                user = decode(userInfo);
            } else {
                user = decode(userInfo.substring(0, colon));
                pass = decode(userInfo.substring(colon + 1));
            }
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("database URL is missing host");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String db = path.startsWith("/") ? path.substring(1) : path;
        if (db.contains("/")) {
            db = db.substring(0, db.indexOf('/'));
        }
        String query = uri.getRawQuery();
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(port).append('/').append(db == null ? "" : db);
        Map<String, String> params = parseQuery(query);
        if (user == null) {
            user = first(params, "user", "username");
        }
        if (pass == null) {
            pass = first(params, "password");
        }
        params.remove("user");
        params.remove("username");
        params.remove("password");
        appendQuery(jdbc, params);
        return new Parsed(jdbc.toString(), user, pass);
    }

    private static Parsed parseJdbc(String jdbcUrl) {
        String remainder = jdbcUrl.substring("jdbc:postgresql:".length());
        if (remainder.startsWith("//")) {
            String asUri = "postgresql:" + remainder;
            try {
                return parseLibpq(asUri);
            } catch (IllegalArgumentException ex) {
                return stripUserPassFromJdbc(jdbcUrl);
            }
        }
        return stripUserPassFromJdbc(jdbcUrl);
    }

    private static Parsed stripUserPassFromJdbc(String jdbcUrl) {
        int q = jdbcUrl.indexOf('?');
        if (q < 0) {
            return new Parsed(jdbcUrl, null, null);
        }
        String base = jdbcUrl.substring(0, q);
        Map<String, String> params = parseQuery(jdbcUrl.substring(q + 1));
        String user = first(params, "user", "username");
        String pass = first(params, "password");
        params.remove("user");
        params.remove("username");
        params.remove("password");
        StringBuilder out = new StringBuilder(base);
        appendQuery(out, params);
        return new Parsed(out.toString(), user, pass);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(decode(pair), "");
            } else {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private static void appendQuery(StringBuilder jdbc, Map<String, String> params) {
        if (params.isEmpty()) {
            return;
        }
        jdbc.append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                jdbc.append('&');
            }
            first = false;
            jdbc.append(encode(e.getKey())).append('=').append(encode(e.getValue() == null ? "" : e.getValue()));
        }
    }

    private static String first(Map<String, String> params, String... keys) {
        for (String key : keys) {
            if (params.containsKey(key) && params.get(key) != null && !params.get(key).isBlank()) {
                return params.get(key);
            }
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String schemeOf(String raw) {
        int idx = raw.indexOf(':');
        return idx < 0 ? raw : raw.substring(0, idx);
    }
}
