package ai.interviewhq.crawler.dev;

import ai.interviewhq.crawler.crawl.CrawlRunner;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewQuestionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

@RestController
@RequestMapping("/dev/api")
@Profile({"dev", "local"})
public class DevController {
    private final CrawlRunner crawlRunner;
    private final CrawlSourceRepository sourceRepository;
    private final InterviewQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    public DevController(CrawlRunner crawlRunner, CrawlSourceRepository sourceRepository,
                         InterviewQuestionRepository questionRepository,
                         ObjectMapper objectMapper) {
        this.crawlRunner = crawlRunner;
        this.sourceRepository = sourceRepository;
        this.questionRepository = questionRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/dev")
    public RedirectView page() {
        return new RedirectView("/dev/index.html");
    }

    @GetMapping("/sources")
    public List<CrawlSource> sources() {
        return sourceRepository.findAll();
    }

    @PatchMapping("/sources/{id}/enabled")
    public CrawlSource setSourceEnabled(@PathVariable Integer id, @RequestParam boolean enabled) {
        CrawlSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Source not found: " + id));
        source.setEnabled(enabled);
        return sourceRepository.save(source);
    }

    @PostMapping("/sources")
    public CrawlSource addSource(@RequestBody CreateSourceRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("Name and URL are required");
        }
        String slug = request.name().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isBlank()) slug = "seed-" + System.currentTimeMillis();
        if (sourceRepository.findBySlug(slug).isPresent()) {
            throw new IllegalArgumentException("A seed with slug already exists: " + slug);
        }
        CrawlSource source = new CrawlSource();
        source.setSlug(slug);
        source.setName(request.name().trim());
        source.setUrl(request.url().trim());
        source.setSourceKind(request.sourceKind() == null || request.sourceKind().isBlank() ? "html" : request.sourceKind().trim());
        source.setEnabled(request.enabled() == null || request.enabled());
        source.setRateLimitRpm(12);
        source.setCrawlDelayMs(2000);
        source.setPerHostConcurrency(1);
        source.setRobotsMode("honor");
        source.setNotes("Added from dev dashboard.");
        return sourceRepository.save(source);
    }

    @DeleteMapping("/questions")
    public void deleteAllQuestions() {
        for (InterviewQuestion question : questionRepository.findAll()) {
            if (question.getId() != null) questionRepository.deleteById(question.getId());
        }
    }

    public record CreateSourceRequest(String name, String url, String sourceKind, Boolean enabled) {}

    @GetMapping("/questions")
    public List<InterviewQuestion> questions() {
        return questionRepository.findAll().stream()
                .sorted((a, b) -> Integer.compare(
                        b.getId() == null ? 0 : b.getId(),
                        a.getId() == null ? 0 : a.getId()))
                .limit(100)
                .toList();
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download() throws Exception {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("sources", sourceRepository.findAll());
        data.put("questions", questionRepository.findAll());
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=interviewhq-dynamodb-data.json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/crawl")
    public void crawl() {
        crawlRunner.runOnce();
    }
}
