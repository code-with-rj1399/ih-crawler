package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.adapters.SourceAdapterRegistry;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.extract.OpenAiQuestionExtractor;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CrawlRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);

    private final CrawlSourceRepository sourceRepository;
    private final InterviewQuestionRepository questionRepository;
    private final SourceAdapterRegistry adapterRegistry;
    private final PoliteFetcher fetcher;
    private final OpenAiQuestionExtractor extractor;
    private final CrawlerSettings settings;
    private final ChromiumSiteCrawler chromiumSiteCrawler;

    public CrawlRunner(
            CrawlSourceRepository sourceRepository,
            InterviewQuestionRepository questionRepository,
            SourceAdapterRegistry adapterRegistry,
            PoliteFetcher fetcher,
            OpenAiQuestionExtractor extractor,
            CrawlerSettings settings,
            ChromiumSiteCrawler chromiumSiteCrawler) {
        this.sourceRepository = sourceRepository;
        this.questionRepository = questionRepository;
        this.adapterRegistry = adapterRegistry;
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.settings = settings;
        this.chromiumSiteCrawler = chromiumSiteCrawler;
    }

    @Scheduled(fixedDelayString = "${crawler.interval-ms:3600000}")
    public void scheduledRun() {
        runOnce();
    }

    public synchronized void runOnce() {
        log.info("=== crawler run started: Chromium discovery, AI extraction-only (no search tools) ===");

        List<CrawlSource> sources = sourceRepository.findByEnabledTrueOrderByIdAsc();
        log.info("Enabled sources: {}", sources.size());

        for (CrawlSource source : sources) {
            crawlSource(source);
        }

        log.info("=== crawler run finished ===");
    }

    private void crawlSource(CrawlSource source) {
        Instant cutoff = Instant.now().minus(settings.lookbackHours(), ChronoUnit.HOURS);
        int cap = Math.max(1, settings.extractMaxPostsPerSource());

        try {
            SourceAdapter adapter = adapterRegistry.require(source.getSourceKind());
            List<ParsedEntry> entries;
            try {
                entries = adapter.crawl(source, cutoff, fetcher);
            } catch (FetchBlockedException blocked) {
                log.warn("HTTP blocked for source={}, falling back to Chromium: {}",
                        source.getSlug(), blocked.getMessage());
                entries = chromiumSiteCrawler.crawl(source, cutoff, cap, 3);
            }

            log.info(
                    "Source crawl finished: source={}, adapter={}, candidates={}, extractionCap={}",
                    source.getSlug(), source.getSourceKind(), entries.size(), cap
            );

            int saved = 0;
            int skipped = 0;
            int aiCalls = 0;
            Set<String> seenHashes = new HashSet<>();

            for (ParsedEntry entry : entries) {
                if (aiCalls >= cap) {
                    skipped++;
                    continue;
                }
                if (!isEligible(entry, cutoff)) {
                    skipped++;
                    continue;
                }
                if (entry.bodyText() == null || entry.bodyText().isBlank()) {
                    skipped++;
                    continue;
                }

                List<InterviewQuestion> questions = extractor.extractQuestionsFromContent(
                        source,
                        entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url(),
                        entry.title(),
                        entry.author(),
                        entry.publishedAt(),
                        entry.bodyText()
                );
                aiCalls++;

                for (InterviewQuestion question : questions) {
                    String dedupeHash = question.getDedupeHash();

                    if (dedupeHash == null || dedupeHash.isBlank()
                            || !seenHashes.add(dedupeHash)
                            || questionRepository.findByDedupeHash(dedupeHash).isPresent()) {
                        skipped++;
                        continue;
                    }

                    questionRepository.save(question);
                    saved++;
                }
            }

            log.info(
                    "Source pipeline finished: source={}, candidates={}, aiCalls={}, savedQuestions={}, skipped={}",
                    source.getSlug(), entries.size(), aiCalls, saved, skipped
            );
        } catch (Exception e) {
            log.error("Source crawl failed: source={}, adapter={}",
                    source.getSlug(), source.getSourceKind(), e);
        }
    }

    private boolean isEligible(ParsedEntry entry, Instant cutoff) {
        if (entry == null) {
            return false;
        }
        // Publication date is a hard gate. We never send an undated page to the LLM.
        if (entry.publishedAt() == null) {
            log.info("24h gate rejected undated candidate: url={}", entry.url());
            return false;
        }
        boolean eligible = !entry.publishedAt().isBefore(cutoff);
        if (!eligible) {
            log.info("24h gate rejected old candidate: url={} publishedAt={} cutoff={}",
                    entry.url(), entry.publishedAt(), cutoff);
        }
        return eligible;
    }
}
