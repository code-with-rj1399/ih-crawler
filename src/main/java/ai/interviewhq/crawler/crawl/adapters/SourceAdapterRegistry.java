package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.SourceAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SourceAdapterRegistry {

    private final Map<String, SourceAdapter> byKind;

    public SourceAdapterRegistry(List<SourceAdapter> adapters) {
        this.byKind = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(a -> a.kind().toLowerCase(Locale.ROOT), Function.identity()));
    }

    public SourceAdapter require(String kind) {
        SourceAdapter adapter = kind == null ? null : byKind.get(kind.toLowerCase(Locale.ROOT));
        if (adapter == null) {
            throw new IllegalArgumentException("No SourceAdapter registered for kind=" + kind);
        }
        return adapter;
    }
}
