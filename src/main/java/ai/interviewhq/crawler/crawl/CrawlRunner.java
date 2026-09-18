package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.extract.OpenAiQuestionExtractor;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CrawlRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);

    private final CrawlSourceRepository sourceRepository;
    private final InterviewQuestionRepository questionRepository;
    private final OpenAiQuestionExtractor extractor;

    public CrawlRunner(CrawlSourceRepository sourceRepository,
                       InterviewQuestionRepository questionRepository,
                       OpenAiQuestionExtractor extractor) {
        this.sourceRepository = sourceRepository;
        this.questionRepository = questionRepository;
        this.extractor = extractor;
    }

    @Scheduled(fixedDelayString = "${crawler.interval-ms:3600000}")
    public void scheduledRun() {
        runOnce();
    }

    public synchronized void runOnce() {
        log.info("=== OpenAI discovery run started ===");

        List<CrawlSource> sources = sourceRepository.findByEnabledTrueOrderByIdAsc();
        log.info("Enabled sources: {}", sources.size());

        for (CrawlSource source : sources) {
            crawlSource(source);
        }

        log.info("=== OpenAI discovery run finished ===");
    }

    private void crawlSource(CrawlSource source) {
        log.info(
                "Starting OpenAI discovery: source={}, platform={}, scope={}",
                source.getSlug(),
                source.getName(),
                source.getUrl()
        );

        try {
            List<InterviewQuestion> questions = extractor.extract(source);

            int saved = 0;
            int skipped = 0;
            Set<String> seenHashes = new HashSet<>();

            for (InterviewQuestion question : questions) {
                if (question.getOriginalPostUrl() == null
                        || question.getOriginalPostUrl().isBlank()) {
                    log.warn(
                            "Skipping question without originalPostUrl: source={}, question={}",
                            source.getSlug(),
                            question.getQuestionText()
                    );
                    skipped++;
                    continue;
                }

                String dedupeHash = question.getDedupeHash();
                if (dedupeHash == null || dedupeHash.isBlank()) {
                    log.warn(
                            "Skipping question without dedupeHash: source={}, question={}",
                            source.getSlug(),
                            question.getQuestionText()
                    );
                    skipped++;
                    continue;
                }

                if (!seenHashes.add(dedupeHash)) {
                    log.debug(
                            "Skipping duplicate question in OpenAI response: source={}, hash={}",
                            source.getSlug(),
                            dedupeHash
                    );
                    skipped++;
                    continue;
                }

                if (questionRepository.findByDedupeHash(dedupeHash).isPresent()) {
                    log.debug(
                            "Question already exists: source={}, hash={}",
                            source.getSlug(),
                            dedupeHash
                    );
                    skipped++;
                    continue;
                }

                questionRepository.save(question);
                saved++;
            }

            log.info(
                    "OpenAI discovery finished: source={}, discovered={}, saved={}, skipped={}",
                    source.getSlug(),
                    questions.size(),
                    saved,
                    skipped
            );
        } catch (Exception e) {
            log.error(
                    "OpenAI discovery failed: source={}",
                    source.getSlug(),
                    e
            );
        }
    }
}
