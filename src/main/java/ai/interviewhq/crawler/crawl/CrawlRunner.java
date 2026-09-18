package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.adapters.SourceAdapterRegistry;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewPost;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.extract.OllamaQuestionExtractor;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewPostRepository;
import ai.interviewhq.crawler.repo.InterviewQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class CrawlRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);

    private final CrawlSourceRepository sourceRepository;
    private final SourceAdapterRegistry adapterRegistry;
    private final PoliteFetcher fetcher;
    private final InterviewPostRepository postRepository;
    private final InterviewQuestionRepository questionRepository;
    private final OllamaQuestionExtractor extractor;
    private final CrawlerSettings settings;

    public CrawlRunner(CrawlSourceRepository sourceRepository,
                       SourceAdapterRegistry adapterRegistry,
                       PoliteFetcher fetcher,
                       InterviewPostRepository postRepository,
                       InterviewQuestionRepository questionRepository,
                       OllamaQuestionExtractor extractor,
                       CrawlerSettings settings) {
        this.sourceRepository = sourceRepository;
        this.adapterRegistry = adapterRegistry;
        this.fetcher = fetcher;
        this.postRepository = postRepository;
        this.questionRepository = questionRepository;
        this.extractor = extractor;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${crawler.interval-ms:3600000}")
    public void scheduledRun() {
        runOnce();
    }

    public synchronized void runOnce() {
        log.info("=== Crawl run started ===");
        Instant lookback = Instant.now().minusSeconds(settings.lookbackHours() * 3600L);
        int processed = 0;
        int sourceCount = sourceRepository.findByEnabledTrueOrderByIdAsc().size();
        log.info("Enabled sources: {}, lookback: {}, extraction limit: {}", sourceCount, lookback, settings.extractMaxPostsPerJob());

        for (CrawlSource source : sourceRepository.findByEnabledTrueOrderByIdAsc()) {
            log.info("Crawling source: slug={}, kind={}, url={}", source.getSlug(), source.getSourceKind(), source.getUrl());
            try {
                List<ParsedEntry> entries =
                        adapterRegistry.require(source.getSourceKind()).crawl(source, lookback, fetcher);
                log.info("Source {} returned {} entries", source.getSlug(), entries.size());

                for (ParsedEntry entry : entries) {
                    if (processed >= settings.extractMaxPostsPerJob()) {
                        log.info("Extraction limit reached; stopping this crawl run");
                        return;
                    }

                    log.info("Processing entry {}: title={}, url={}", processed + 1, entry.title(), entry.url());
                    InterviewPost post = upsertPost(source, entry);
                    processed++;
                    log.info("Saved post: id={}, extracted={}, bodyLength={}", post.getId(), post.isExtracted(), entry.bodyText() == null ? 0 : entry.bodyText().length());

                    // Deliberately sequential: one crawled entry -> one Ollama call -> DynamoDB -> next entry.
                    try {
                        log.info("Starting AI extraction: postId={}, model={}", post.getId(), settings.extractModel());
                        InterviewQuestion question = extractor.extract(entry, post.getId());
                        if (question == null) {
                            log.info("AI returned no interview question: postId={}, url={}", post.getId(), entry.url());
                            continue;
                        }

                        log.info("Ollama extracted question: postId={}, questionId={}, type={}, company={}, role={}, confidence={}", post.getId(), question.getId(), question.getQuestionType(), question.getCompany(), question.getRole(), question.getConfidence());
                        boolean existing = questionRepository.findByDedupeHash(question.getDedupeHash()).isPresent();
                        log.info("Question dedupe: hash={}, existing={}", question.getDedupeHash(), existing);
                        questionRepository.findByDedupeHash(question.getDedupeHash())
                                .orElseGet(() -> questionRepository.save(question));

                        post.setExtracted(true);
                        postRepository.save(post);
                        log.info("Post marked extracted: postId={}", post.getId());
                    } catch (RuntimeException extractionError) {
                        log.error("AI extraction failed: postId={}, url={}, error={}", post.getId(), entry.url(), extractionError.getMessage(), extractionError);
                    }
                }
            } catch (Exception e) {
                log.error("Source crawl failed: slug={}, error={}", source.getSlug(), e.getMessage(), e);
            }
        }
        log.info("=== Crawl run finished: processed {} entries ===", processed);
    }

    private InterviewPost upsertPost(CrawlSource source, ParsedEntry entry) {
        String url = entry.canonicalUrl() != null ? entry.canonicalUrl() : entry.url();

        return postRepository.findBySourceIdAndUrl(source.getId(), url)
                .map(existing -> {
                    existing.setTitle(entry.title());
                    existing.setAuthor(entry.author());
                    existing.setPostedAt(entry.publishedAt());
                    existing.setRawCompany(entry.rawCompany());
                    existing.setRawRole(entry.rawRole());
                    existing.setBodyText(entry.bodyText());
                    existing.setContentHash(entry.contentHash());
                    return postRepository.save(existing);
                })
                .orElseGet(() -> {
                    InterviewPost post = new InterviewPost();
                    post.setSourceId(source.getId());
                    post.setExternalId(entry.externalId());
                    post.setUrl(url);
                    post.setTitle(entry.title());
                    post.setAuthor(entry.author());
                    post.setPostedAt(entry.publishedAt());
                    post.setRawCompany(entry.rawCompany());
                    post.setRawRole(entry.rawRole());
                    post.setBodyText(entry.bodyText());
                    post.setContentHash(entry.contentHash());
                    post.setExtracted(false);
                    return postRepository.save(post);
                });
    }
}
