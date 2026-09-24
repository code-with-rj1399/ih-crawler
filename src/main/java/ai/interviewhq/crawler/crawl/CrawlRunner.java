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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    @Scheduled(cron = "${crawler.cron:0 0 * * * *}", zone = "${crawler.cron-zone:UTC}")
    public void scheduledRun() {
        runOnce();
    }

    public synchronized void runOnce() {
        log.info("=== crawler run started: Chromium discovery, AI extraction-only (no search tools) ===");

        List<CrawlSource> sources = sourceRepository.findByEnabledTrueOrderByIdAsc();
        int maxConcurrentTasks = settings.maxConcurrentTasks();
        int poolSize = Math.min(maxConcurrentTasks, Math.max(1, sources.size()));

        log.info("Enabled sources: {}, maxConcurrentTasks={}, workerThreads={}",
                sources.size(), maxConcurrentTasks, poolSize);

        ExecutorService executor = Executors.newFixedThreadPool(
                poolSize,
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("crawler-task-" + thread.getId());
                    thread.setDaemon(true);
                    return thread;
                });

        try {
            List<Future<?>> futures = new ArrayList<>();

            for (CrawlSource source : sources) {
                Runnable task = () -> {
                    log.info("Crawl task started: source={}, thread={}",
                            source.getSlug(), Thread.currentThread().getName());
                    try {
                        crawlSource(source);
                    } finally {
                        log.info("Crawl task finished: source={}, thread={}",
                                source.getSlug(), Thread.currentThread().getName());
                    }
                };

                futures.add(executor.submit(task));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.error("Crawl task failed", e);
                }
            }
        } finally {
            executor.shutdown();
        }

        log.info("=== crawler run finished ===");
    }

    private void crawlSource(CrawlSource source) {
        Instant cutoff = Instant.now().minus(settings.lookbackHours(), ChronoUnit.HOURS);
        int cap = Math.max(1, settings.extractMaxPostsPerSource());

        try {
            SourceAdapter adapter = adapterRegistry.require(source.getSourceKind());
            log.info(
                    "Source crawl starting: source={}, adapter={}, extractionCap={}",
                    source.getSlug(), source.getSourceKind(), cap);

            int[] processed = {0};
            int[] saved = {0};
            int[] skipped = {0};
            int[] aiCalls = {0};
            Set<String> seenHashes = new HashSet<>();

            try {
                adapter.crawlStreaming(source, cutoff, fetcher, entry -> {
                    if (aiCalls[0] >= cap) {
                        skipped[0]++;
                        return;
                    }
                    processed[0]++;

                    if (!isEligible(entry, cutoff)
                            || entry.bodyText() == null
                            || entry.bodyText().isBlank()) {
                        skipped[0]++;
                        return;
                    }

                    // IMPORTANT: this callback is invoked by the source adapter
                    // only after this page/post has completed its source-specific
                    // fetch + parsing flow. AI extraction, dedupe and persistence
                    // therefore complete before the adapter moves to the next page.
                    List<InterviewQuestion> questions = extractor.extractQuestionsFromContent(
                            source,
                            entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url(),
                            entry.title(),
                            entry.author(),
                            entry.publishedAt(),
                            entry.bodyText()
                    );
                    aiCalls[0]++;

                    if (questions.isEmpty()) {
                        skipped[0]++;
                        log.info("Discarding interview experience with no extracted questions: source={} url={}",
                                source.getSlug(),
                                entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url());
                        return;
                    }

                    for (InterviewQuestion question : questions) {
                        String dedupeHash = question.getDedupeHash();

                        if (dedupeHash == null || dedupeHash.isBlank()
                                || !seenHashes.add(dedupeHash)
                                || questionRepository.findByDedupeHash(dedupeHash).isPresent()) {
                            skipped[0]++;
                            continue;
                        }

                        questionRepository.save(question);
                        saved[0]++;
                    }

                    log.info("Page flow completed: source={} url={} aiCalls={} savedQuestions={}",
                            source.getSlug(),
                            entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url(),
                            aiCalls[0],
                            saved[0]);
                });
            } catch (FetchBlockedException blocked) {
                log.warn("HTTP blocked for source={}, falling back to Chromium: {}",
                        source.getSlug(), blocked.getMessage());
                chromiumSiteCrawler.crawlStreaming(source, cutoff, cap, 3, entry -> {
                    if (aiCalls[0] >= cap) {
                        skipped[0]++;
                        return;
                    }
                    processed[0]++;
                    if (!isEligible(entry, cutoff)
                            || entry.bodyText() == null
                            || entry.bodyText().isBlank()) {
                        skipped[0]++;
                        return;
                    }
                    List<InterviewQuestion> questions = extractor.extractQuestionsFromContent(
                            source,
                            entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url(),
                            entry.title(),
                            entry.author(),
                            entry.publishedAt(),
                            entry.bodyText()
                    );
                    aiCalls[0]++;
                    if (questions.isEmpty()) {
                        skipped[0]++;
                        log.info("Discarding interview experience with no extracted questions: source={} url={}",
                                source.getSlug(),
                                entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url());
                        return;
                    }
                    for (InterviewQuestion question : questions) {
                        String dedupeHash = question.getDedupeHash();
                        if (dedupeHash == null || dedupeHash.isBlank()
                                || !seenHashes.add(dedupeHash)
                                || questionRepository.findByDedupeHash(dedupeHash).isPresent()) {
                            skipped[0]++;
                            continue;
                        }
                        questionRepository.save(question);
                        saved[0]++;
                    }
                    log.info("Page flow completed: source={} url={} aiCalls={} savedQuestions={}",
                            source.getSlug(),
                            entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url(),
                            aiCalls[0],
                            saved[0]);
                });
            }

            log.info(
                    "Source pipeline finished: source={}, pagesCompleted={}, aiCalls={}, savedQuestions={}, skipped={}",
                    source.getSlug(), processed[0], aiCalls[0], saved[0], skipped[0]
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
