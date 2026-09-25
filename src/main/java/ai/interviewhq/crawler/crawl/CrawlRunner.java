package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.adapters.SourceAdapterRegistry;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewExperience;
import ai.interviewhq.crawler.domain.InterviewPost;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.extract.ExperienceExtraction;
import ai.interviewhq.crawler.extract.TwoStepOpenAiExtractor;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewExperienceRepository;
import ai.interviewhq.crawler.repo.InterviewPostRepository;
import ai.interviewhq.crawler.repo.InterviewQuestionRepository;
import ai.interviewhq.crawler.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class CrawlRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);

    private final CrawlSourceRepository sourceRepository;
    private final InterviewPostRepository postRepository;
    private final InterviewExperienceRepository experienceRepository;
    private final InterviewQuestionRepository questionRepository;
    private final SourceAdapterRegistry adapterRegistry;
    private final PoliteFetcher fetcher;
    private final TwoStepOpenAiExtractor extractor;
    private final CrawlerSettings settings;
    private final ChromiumSiteCrawler chromiumSiteCrawler;

    public CrawlRunner(CrawlSourceRepository sourceRepository, InterviewPostRepository postRepository,
                       InterviewExperienceRepository experienceRepository,
                       InterviewQuestionRepository questionRepository, SourceAdapterRegistry adapterRegistry,
                       PoliteFetcher fetcher, TwoStepOpenAiExtractor extractor, CrawlerSettings settings,
                       ChromiumSiteCrawler chromiumSiteCrawler) {
        this.sourceRepository = sourceRepository;
        this.postRepository = postRepository;
        this.experienceRepository = experienceRepository;
        this.questionRepository = questionRepository;
        this.adapterRegistry = adapterRegistry;
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.settings = settings;
        this.chromiumSiteCrawler = chromiumSiteCrawler;
    }

    @Scheduled(cron = "${crawler.cron:0 0 * * * *}", zone = "${crawler.cron-zone:UTC}")
    public void scheduledRun() { runOnce(); }

    public synchronized void runOnce() {
        log.info("=== crawler run started: two-step AI extraction ===");
        List<CrawlSource> sources = sourceRepository.findByEnabledTrueOrderByIdAsc();
        int maxConcurrentTasks = settings.maxConcurrentTasks();
        int poolSize = Math.min(maxConcurrentTasks, Math.max(1, sources.size()));
        log.info("Enabled sources: {}, maxConcurrentTasks={}, workerThreads={}", sources.size(), maxConcurrentTasks, poolSize);

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
                    log.info("Crawl task started: source={}, thread={}", source.getSlug(), Thread.currentThread().getName());
                    try { crawlSource(source); }
                    finally { log.info("Crawl task finished: source={}, thread={}", source.getSlug(), Thread.currentThread().getName()); }
                }));
            }
            for (Future<?> future : futures) {
                try { future.get(); } catch (Exception e) { log.error("Crawl task failed", e); }
            }
        } finally { executor.shutdown(); }
        log.info("=== crawler run finished ===");
    }

    private void crawlSource(CrawlSource source) {
        Instant cutoff = Instant.now().minus(settings.lookbackHours(), ChronoUnit.HOURS);
        int cap = Math.max(1, settings.extractMaxPostsPerSource());
        try {
            SourceAdapter adapter = adapterRegistry.require(source.getSourceKind());
            log.info("Source crawl starting: source={}, adapter={}, extractionCap={}", source.getSlug(), source.getSourceKind(), cap);
            int[] processed = {0}; int[] savedPosts = {0}; int[] savedExperiences = {0}; int[] savedQuestions = {0}; int[] skipped = {0}; int[] modelCalls = {0};
            try {
                adapter.crawlStreaming(source, cutoff, fetcher, entry -> processEntry(source, entry, cutoff, cap,
                        processed, savedPosts, savedExperiences, savedQuestions, skipped, modelCalls));
            } catch (FetchBlockedException blocked) {
                log.warn("HTTP blocked for source={}, falling back to Chromium: {}", source.getSlug(), blocked.getMessage());
                chromiumSiteCrawler.crawlStreaming(source, cutoff, cap, 3, entry -> processEntry(source, entry, cutoff, cap,
                        processed, savedPosts, savedExperiences, savedQuestions, skipped, modelCalls));
            }
            log.info("Source pipeline finished: source={}, pagesCompleted={}, modelCalls={}, savedPosts={}, savedExperiences={}, savedQuestions={}, skipped={}",
                    source.getSlug(), processed[0], modelCalls[0], savedPosts[0], savedExperiences[0], savedQuestions[0], skipped[0]);
        } catch (Exception e) { log.error("Source crawl failed: source={}, adapter={}", source.getSlug(), source.getSourceKind(), e); }
    }

    private void processEntry(CrawlSource source, ParsedEntry entry, Instant cutoff, int cap,
                              int[] processed, int[] savedPosts, int[] savedExperiences, int[] savedQuestions, int[] skipped,
                              int[] modelCalls) {
        if (processed[0] >= cap) { skipped[0]++; return; }
        processed[0]++;
        if (!isEligible(entry, cutoff) || entry.bodyText() == null || entry.bodyText().isBlank()) { skipped[0]++; return; }

        String postUrl = entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url();
        try {
            TwoStepOpenAiExtractor.ExtractionResult result = extractor.extract(source, postUrl, entry.title(),
                    entry.author(), entry.publishedAt(), entry.bodyText());
            ExperienceExtraction experienceExtraction = result.experience();
            modelCalls[0] += experienceExtraction != null && !experienceExtraction.questions().isEmpty() ? 2 : 1;
            if (experienceExtraction == null) { skipped[0]++; return; }

            InterviewPost post = new InterviewPost();
            post.setSourceId(source.getId());
            post.setUrl(postUrl);
            post.setTitle(experienceExtraction.title());
            post.setAuthor(experienceExtraction.author());
            post.setPostedAt(experienceExtraction.postedAt() != null ? experienceExtraction.postedAt() : entry.publishedAt());
            post.setSummary(experienceExtraction.summary());
            post.setRawCompany(experienceExtraction.company());
            post.setRawRole(experienceExtraction.role());
            post.setExperienceLevel(experienceExtraction.level());
            post.setLocation(experienceExtraction.location());
            post.setCandidateYoE(experienceExtraction.candidateYoE());
            postRepository.save(post);
            savedPosts[0]++;

            String experienceHash = Hashing.experienceDedupeHash(source.getName(), postUrl);
            InterviewExperience experience = experienceRepository.findByDedupeHash(experienceHash).orElseGet(InterviewExperience::new);
            experience.setSourceId(source.getId());
            experience.setSourcePlatform(source.getName());
            experience.setTitle(experienceExtraction.title());
            experience.setSummary(experienceExtraction.summary());
            experience.setAuthor(experienceExtraction.author());
            experience.setPostedAt(experienceExtraction.postedAt() != null ? experienceExtraction.postedAt() : entry.publishedAt());
            experience.setOriginalPostUrl(postUrl);
            experience.setCompany(experienceExtraction.company());
            experience.setRole(experienceExtraction.role());
            experience.setLevel(experienceExtraction.level());
            experience.setLocation(experienceExtraction.location());
            experience.setCandidateYoE(experienceExtraction.candidateYoE());
            experience.setDedupeHash(experienceHash);
            experience.setQuestionCount(0);
            experienceRepository.save(experience);
            savedExperiences[0]++;

            for (InterviewQuestion question : result.questions()) {
                question.setExperienceId(experience.getId());
                questionRepository.save(question);
                savedQuestions[0]++;
            }
            experience.setQuestionCount(result.questions().size());
            experienceRepository.save(experience);
        } catch (Exception e) {
            log.error("AI extraction failed for {}", postUrl, e);
            skipped[0]++;
        }
    }

    private boolean isEligible(ParsedEntry entry, Instant cutoff) {
        Instant publishedAt = entry.publishedAt();
        return publishedAt != null && !publishedAt.isBefore(cutoff);
    }
}
