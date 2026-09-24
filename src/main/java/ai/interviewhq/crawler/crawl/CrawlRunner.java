package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.adapters.SourceAdapterRegistry;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewPost;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.extract.ExperienceExtraction;
import ai.interviewhq.crawler.extract.TwoStepOpenAiExtractor;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewPostRepository;
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
    private final InterviewPostRepository postRepository;
    private final InterviewQuestionRepository questionRepository;
    private final SourceAdapterRegistry adapterRegistry;
    private final PoliteFetcher fetcher;
    private final TwoStepOpenAiExtractor extractor;
    private final CrawlerSettings settings;
    private final ChromiumSiteCrawler chromiumSiteCrawler;

    public CrawlRunner(
            CrawlSourceRepository sourceRepository,
            InterviewPostRepository postRepository,
            InterviewQuestionRepository questionRepository,
            SourceAdapterRegistry adapterRegistry,
            PoliteFetcher fetcher,
            TwoStepOpenAiExtractor extractor,
            CrawlerSettings settings,
            ChromiumSiteCrawler chromiumSiteCrawler) {
        this.sourceRepository = sourceRepository;
        this.postRepository = postRepository;
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
        log.info("=== crawler run started: two-step AI extraction ===");

        List<CrawlSource> sources = sourceRepository.findByEnabledTrueOrderByIdAsc();
        int maxConcurrentTasks = settings.maxConcurrentTasks();
        int poolSize = Math.min(maxConcurrentTasks, Math.max(1, sources.size()));

        log.info("Enabled sources: {}, maxConcurrentTasks={}, workerThreads={}",
                sources.size(), maxConcurrentTasks, poolSize);

        ExecutorService executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("crawler-task-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (CrawlSource source : sources) {
                futures.add(executor.submit(() -> {
                    log.info("Crawl task started: source={}, thread={}",
                            source.getSlug(), Thread.currentThread().getName());
                    try {
                        crawlSource(source);
                    } finally {
                        log.info("Crawl task finished: source={}, thread={}",
                                source.getSlug(), Thread.currentThread().getName());
                    }
                }));
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
            log.info("Source crawl starting: source={}, adapter={}, extractionCap={}",
                    source.getSlug(), source.getSourceKind(), cap);

            int[] processed = {0};
            int[] savedPosts = {0};
            int[] savedQuestions = {0};
            int[] skipped = {0};
            int[] modelCalls = {0};
            Set<String> seenHashes = new HashSet<>();

            try {
                adapter.crawlStreaming(source, cutoff, fetcher, entry -> processEntry(
                        source, entry, cutoff, cap, processed, savedPosts, savedQuestions, skipped,
                        modelCalls, seenHashes));
            } catch (FetchBlockedException blocked) {
                log.warn("HTTP blocked for source={}, falling back to Chromium: {}",
                        source.getSlug(), blocked.getMessage());
                chromiumSiteCrawler.crawlStreaming(source, cutoff, cap, 3, entry -> processEntry(
                        source, entry, cutoff, cap, processed, savedPosts, savedQuestions, skipped,
                        modelCalls, seenHashes));
            }

            log.info("Source pipeline finished: source={}, pagesCompleted={}, modelCalls={}, savedPosts={}, savedQuestions={}, skipped={}",
                    source.getSlug(), processed[0], modelCalls[0], savedPosts[0], savedQuestions[0], skipped[0]);
        } catch (Exception e) {
            log.error("Source crawl failed: source={}, adapter={}", source.getSlug(), source.getSourceKind(), e);
        }
    }

    private void processEntry(CrawlSource source,
                              ParsedEntry entry,
                              Instant cutoff,
                              int cap,
                              int[] processed,
                              int[] savedPosts,
                              int[] savedQuestions,
                              int[] skipped,
                              int[] modelCalls,
                              Set<String> seenHashes) {
        if (processed[0] >= cap) {
            skipped[0]++;
            return;
        }
        processed[0]++;

        if (!isEligible(entry, cutoff) || entry.bodyText() == null || entry.bodyText().isBlank()) {
            skipped[0]++;
            return;
        }

        String postUrl = entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url();
        try {
            TwoStepOpenAiExtractor.ExtractionResult result = extractor.extract(
                    source,
                    postUrl,
                    entry.title(),
                    entry.author(),
                    entry.publishedAt(),
                    entry.bodyText());

            ExperienceExtraction experience = result.experience();
            modelCalls[0] += 1 + result.questions().size();

            if (!experience.authenticExperience()) {
                skipped[0]++;
                log.info("Rejected non-experience page: source={} url={}", source.getSlug(), postUrl);
                return;
            }

            InterviewPost post = saveExperience(source, entry, experience, postUrl);
            savedPosts[0]++;

            for (InterviewQuestion question : result.questions()) {
                question.setPostId(post.getId());
                String dedupeHash = question.getDedupeHash();
                if (dedupeHash == null || dedupeHash.isBlank()
                        || !seenHashes.add(dedupeHash)
                        || questionRepository.findByDedupeHash(dedupeHash).isPresent()) {
                    skipped[0]++;
                    continue;
                }

                questionRepository.save(question);
                savedQuestions[0]++;
            }

            log.info("Two-step extraction completed: source={} url={} questions={} modelCalls={} savedQuestions={}",
                    source.getSlug(), postUrl, result.questions().size(),
                    1 + result.questions().size(), savedQuestions[0]);
        } catch (Exception e) {
            skipped[0]++;
            log.error("Two-step extraction failed: source={} url={}", source.getSlug(), postUrl, e);
        }
    }

    private InterviewPost saveExperience(CrawlSource source,
                                         ParsedEntry entry,
                                         ExperienceExtraction experience,
                                         String postUrl) {
        InterviewPost post = postRepository.findBySourceIdAndUrl(source.getId(), postUrl)
                .orElseGet(InterviewPost::new);

        post.setSourceId(source.getId());
        post.setUrl(postUrl);
        post.setTitle(experience.title() != null ? experience.title() : entry.title());
        post.setAuthor(experience.author() != null ? experience.author() : entry.author());
        post.setPostedAt(experience.postedAt() != null ? experience.postedAt() : entry.publishedAt());
        post.setSummary(experience.summary());
        post.setRawCompany(experience.company());
        post.setRawRole(experience.role());
        post.setExperienceLevel(experience.level());
        post.setLocation(experience.location());
        post.setCandidateYoE(experience.candidateYoE());
        post.setOutcome(experience.outcome());
        post.setRounds(experience.rounds());
        post.setBodyText(entry.bodyText());
        post.setExtracted(true);
        return postRepository.save(post);
    }

    private boolean isEligible(ParsedEntry entry, Instant cutoff) {
        if (entry == null) return false;
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
