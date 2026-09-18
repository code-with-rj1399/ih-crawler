package ai.interviewhq.crawler.dev;

import ai.interviewhq.crawler.crawl.CrawlRunner;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewQuestionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

@RestController
@RequestMapping("/dev/api")
@Profile({"dev", "local"})
public class DevController {
    private final CrawlRunner crawlRunner;
    private final CrawlSourceRepository sourceRepository;
    private final InterviewQuestionRepository questionRepository;

    public DevController(CrawlRunner crawlRunner, CrawlSourceRepository sourceRepository,
                         InterviewQuestionRepository questionRepository) {
        this.crawlRunner = crawlRunner;
        this.sourceRepository = sourceRepository;
        this.questionRepository = questionRepository;
    }

    @GetMapping("/dev")
    public RedirectView page() {
        return new RedirectView("/dev/index.html");
    }

    @GetMapping("/sources")
    public List<CrawlSource> sources() {
        return sourceRepository.findAll();
    }

    @GetMapping("/questions")
    public List<InterviewQuestion> questions() {
        return questionRepository.findAll().stream()
                .sorted((a, b) -> Integer.compare(
                        b.getId() == null ? 0 : b.getId(),
                        a.getId() == null ? 0 : a.getId()))
                .limit(100)
                .toList();
    }

    @PostMapping("/crawl")
    public void crawl() {
        crawlRunner.runOnce();
    }
}
