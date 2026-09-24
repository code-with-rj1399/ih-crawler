package ai.interviewhq.crawler.dev;

import ai.interviewhq.crawler.crawl.CrawlRunner;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.extract.ExtractionPromptService;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.InterviewQuestionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dev/api")
@Profile({"dev", "local"})
public class DevController {
    private final CrawlRunner crawlRunner;
    private final CrawlSourceRepository sourceRepository;
    private final InterviewQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;
    private final ExtractionPromptService promptService;
    private final DevPromptTester promptTester;

    public DevController(CrawlRunner crawlRunner, CrawlSourceRepository sourceRepository,
                         InterviewQuestionRepository questionRepository,
                         ObjectMapper objectMapper,
                         ExtractionPromptService promptService,
                         DevPromptTester promptTester) {
        this.crawlRunner = crawlRunner;
        this.sourceRepository = sourceRepository;
        this.questionRepository = questionRepository;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
        this.promptTester = promptTester;
    }

    @GetMapping("/dev")
    public org.springframework.web.servlet.view.RedirectView page() { return new org.springframework.web.servlet.view.RedirectView("/dev/index.html"); }

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of(
                "model", System.getenv().getOrDefault("OPENAI_MODEL", "gpt-5-nano"),
                "reasoningEffort", System.getenv().getOrDefault("OPENAI_REASONING_EFFORT", "low")
        );
    }

    @GetMapping("/sources")
    public List<CrawlSource> sources() { return sourceRepository.findAll(); }

    @PatchMapping("/sources/{id}/enabled")
    public CrawlSource setSourceEnabled(@PathVariable Integer id, @RequestParam boolean enabled) {
        CrawlSource source = sourceRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Source not found: " + id));
        source.setEnabled(enabled);
        return sourceRepository.save(source);
    }

    @PostMapping("/sources/disable-all")
    public void disableAllSources() {
        for (CrawlSource source : sourceRepository.findAll()) {
            if (source.isEnabled()) { source.setEnabled(false); sourceRepository.save(source); }
        }
    }

    @PostMapping("/sources")
    public CrawlSource addSource(@RequestBody CreateSourceRequest request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("Name and URL are required");
        }
        String slug = request.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isBlank()) slug = "seed-" + System.currentTimeMillis();
        if (sourceRepository.findBySlug(slug).isPresent()) throw new IllegalArgumentException("A seed with slug already exists: " + slug);
        CrawlSource source = new CrawlSource();
        source.setSlug(slug); source.setName(request.name().trim()); source.setUrl(request.url().trim());
        source.setSourceKind(request.sourceKind() == null || request.sourceKind().isBlank() ? "html" : request.sourceKind().trim());
        source.setEnabled(request.enabled() == null || request.enabled()); source.setRateLimitRpm(12); source.setCrawlDelayMs(2000);
        source.setPerHostConcurrency(1); source.setRobotsMode("honor"); source.setNotes("Added from dev dashboard.");
        return sourceRepository.save(source);
    }

    @GetMapping("/prompts")
    public Map<String, String> getPrompts() {
        return Map.of(
                "experiencePrompt", promptService.getExperiencePrompt(),
                "questionMetadataPrompt", promptService.getQuestionMetadataPrompt()
        );
    }

    @PutMapping("/prompts/experience")
    public Map<String, String> updateExperiencePrompt(@RequestBody PromptRequest request) {
        promptService.setExperiencePrompt(request == null ? null : request.prompt());
        return Map.of("prompt", promptService.getExperiencePrompt());
    }

    @PutMapping("/prompts/question-metadata")
    public Map<String, String> updateQuestionMetadataPrompt(@RequestBody PromptRequest request) {
        promptService.setQuestionMetadataPrompt(request == null ? null : request.prompt());
        return Map.of("prompt", promptService.getQuestionMetadataPrompt());
    }

    @PostMapping("/prompts/reset")
    public Map<String, String> resetPrompts() {
        promptService.resetToDefault();
        return Map.of(
                "experiencePrompt", promptService.getExperiencePrompt(),
                "questionMetadataPrompt", promptService.getQuestionMetadataPrompt()
        );
    }

    @PostMapping("/prompt-test/step1")
    public JsonNode testStep1(@RequestBody PromptTestRequest request) throws Exception {
        String prompt = promptService.getExperiencePrompt();
        if (request != null && request.prompt() != null && !request.prompt().isBlank()) prompt = request.prompt();
        prompt = prompt.replace("{{page_title}}", safe(request == null ? null : request.pageTitle()))
                .replace("{{page_content}}", safe(request == null ? null : request.pageContent()));
        return promptTester.test(prompt);
    }

    @PostMapping("/prompt-test/step2")
    public JsonNode testStep2(@RequestBody PromptTestRequest request) throws Exception {
        String prompt = promptService.getQuestionMetadataPrompt();
        if (request != null && request.prompt() != null && !request.prompt().isBlank()) prompt = request.prompt();
        prompt = prompt.replace("{{questions_json}}", safe(request == null ? null : request.questionsJson()))
                .replace("{{question_text}}", safe(request == null ? null : request.questionsJson()));
        return promptTester.test(prompt);
    }

    @GetMapping("/questions")
    public List<InterviewQuestion> questions() {
        return questionRepository.findAll().stream().sorted((a, b) -> Integer.compare(b.getId() == null ? 0 : b.getId(), a.getId() == null ? 0 : a.getId())).limit(100).toList();
    }

    @DeleteMapping("/questions")
    public ResponseEntity<Map<String, Object>> deleteAllQuestions() {
        int deleted = questionRepository.deleteAll(); return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download() throws Exception {
        Map<String, Object> data = new java.util.LinkedHashMap<>(); data.put("sources", sourceRepository.findAll()); data.put("questions", questionRepository.findAll());
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=interviewhq-dynamodb-data.json").contentType(MediaType.APPLICATION_JSON).body(json);
    }

    @PostMapping("/crawl")
    public void crawl() { crawlRunner.runOnce(); }

    private static String safe(String value) { return value == null ? "" : value; }

    public record CreateSourceRequest(String name, String url, String sourceKind, Boolean enabled) {}
    public record PromptRequest(String prompt) {}
    public record PromptTestRequest(String prompt, String pageTitle, String pageContent, String questionsJson) {}
}
