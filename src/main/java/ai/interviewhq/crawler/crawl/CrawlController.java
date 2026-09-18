package ai.interviewhq.crawler.crawl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/crawl")
public class CrawlController {

    private final CrawlRunner crawlRunner;

    public CrawlController(CrawlRunner crawlRunner) {
        this.crawlRunner = crawlRunner;
    }

    @PostMapping("/run")
    public ResponseEntity<Void> run() {
        crawlRunner.runOnce();
        return ResponseEntity.accepted().build();
    }
}
