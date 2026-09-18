package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.config.CrawlerSettings;
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
import java.util.List;

@Service
public class CrawlRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);

    private final CrawlSourceRepository sourceRepository;
    private final InterviewQuestionRepository questionRepository;
    private final OpenAiQuestionExtractor extractor;
    private final CrawlerSettings settings;

    public CrawlRunner(CrawlSourceRepository sourceRepository,
                       InterviewQuestionRepository questionRepository,
                       OpenAiQuestionExtractor extractor,
                       CrawlerSettings settings) {
        this.sourceRepository = sourceRepository;
        this.questionRepository = questionRepository;
        this.extractor = extractor;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${crawler.interval-ms:3600000}")
    public void scheduledRun() {
        runOnce();
    }

    public synchronized void runOnce() {
        log.info("=== OpenAI discovery run started ===");
        Instant lookback = Instant.now().minusSeconds(settings.lookbackHours() * 3600L);
        int sourceCount = sourceRepository.findByEnabledTrueOrderByIdAsc().size();
        log.info("Enabled sources: {}, lookback: {}", sourceCount, lookback);

        for (CrawlSource source : sourceRepository.findByEnabledTrueOrderByIdAsc()) {
            log.info("Starting OpenAI discovery: source={}, platform={}, scope={}",
                    source.getSlug(), source.getName(), source.getUrl());
            try {
                List<InterviewQuestion> questions = extractor.extract(source);

                int saved = 0;
                for (InterviewQuestion question : questions) {
                    if (question.getOriginalPostUrl() == null || question.getOriginalPostUrl().isBlank()) {
                        log.warn("Skipping question without originalPostUrl: source={}, question={}",
                                source.getSlug(), question.getQuestionText());
                        continue;
                    }

                    if (questionRepository.findByDedupeHash(question.getDedupeHash()).isEmpty()) {
                        questionRepository.save(question);
                        saved++;
                    }
                }

                log.info("OpenAI discovery finished: source={}, discovered={}, saved={}",
                        source.getSlug(), questions.size(), saved);
            } catch (Exception e) {
                log.error("OpenAI discovery failed: source={}, error={}", source.getSlug(), e.getMessage(), e);
            }
        }
        log.info("=== OpenAI discovery run finished ===");
    }


