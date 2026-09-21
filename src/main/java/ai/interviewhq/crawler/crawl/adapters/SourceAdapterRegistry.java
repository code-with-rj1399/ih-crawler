package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.SourceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SourceAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceAdapterRegistry.class);

    private final Map<String, SourceAdapter> byKind;

    public SourceAdapterRegistry(List<SourceAdapter> adapters) {
        this.byKind = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(a -> a.kind().toLowerCase(Locale.ROOT), Function.identity()));
    }

    public SourceAdapter require(String kind) {
        String key = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        SourceAdapter adapter = byKind.get(key);
        if (adapter == null && ("chromium".equals(key) || "browser".equals(key))) {
            adapter = byKind.get("html");
        }
        if (adapter == null) {
            adapter = byKind.get("html");
            if (adapter != null) {
                log.warn("No SourceAdapter for kind={}; falling back to Chromium html crawler", kind);
            }
        }
        if (adapter == null) {
            throw new IllegalArgumentException("No SourceAdapter registered for kind=" + kind);
        }
        return adapter;
    }
}
