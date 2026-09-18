package ai.interviewhq.crawler.config;

import ai.interviewhq.crawler.util.JdbcUrlParser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates Neon / RDS {@code DATABASE_URL} values into Spring datasource properties
 * before auto-configuration runs.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "ihCrawlerDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = firstNonBlank(
                environment.getProperty("DATABASE_URL"),
                environment.getProperty("SPRING_DATASOURCE_URL"),
                environment.getProperty("spring.datasource.url")
        );
        if (raw == null || raw.isBlank()) {
            return;
        }
        JdbcUrlParser.Parsed parsed;
        try {
            parsed = JdbcUrlParser.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Could not parse DATABASE_URL / datasource URL", ex);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spring.datasource.url", parsed.jdbcUrl());
        map.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        if (parsed.username() != null && isBlank(environment.getProperty("SPRING_DATASOURCE_USERNAME"))
                && isBlank(environment.getProperty("spring.datasource.username"))) {
            map.put("spring.datasource.username", parsed.username());
        }
        if (parsed.password() != null && isBlank(environment.getProperty("SPRING_DATASOURCE_PASSWORD"))
                && isBlank(environment.getProperty("spring.datasource.password"))) {
            map.put("spring.datasource.password", parsed.password());
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, map));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
