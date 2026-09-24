package ai.interviewhq.crawler.admin;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.domain.CrawlJob;
import ai.interviewhq.crawler.domain.CrawlJobLog;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.CrawlSubRun;
import ai.interviewhq.crawler.domain.CrawlerConfig;
import ai.interviewhq.crawler.repo.CrawlJobLogRepository;
import ai.interviewhq.crawler.repo.CrawlJobRepository;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import ai.interviewhq.crawler.repo.CrawlSubRunRepository;
import ai.interviewhq.crawler.repo.CrawlerConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = "${admin.ui.origin:http://localhost:3000}")
public class AdminController {
    private final CrawlerConfigRepository configRepository;
    private final CrawlerSettings settings;
    private final CrawlSourceRepository sourceRepository;
    private final CrawlJobRepository jobRepository;
    private final CrawlSubRunRepository subRunRepository;
    private final CrawlJobLogRepository logRepository;

    public AdminController(CrawlerConfigRepository configRepository, CrawlerSettings settings,
                           CrawlSourceRepository sourceRepository, CrawlJobRepository jobRepository,
                           CrawlSubRunRepository subRunRepository, CrawlJobLogRepository logRepository) {
        this.configRepository=configRepository; this.settings=settings; this.sourceRepository=sourceRepository;
        this.jobRepository=jobRepository; this.subRunRepository=subRunRepository; this.logRepository=logRepository;
    }

    @GetMapping("/crawl/config")
    public Map<String,Object> getConfig(){
        Map<String,String> values=settings.asMap();
        values.putIfAbsent(CrawlerSettings.CRON_ENABLED,"true");
        values.putIfAbsent(CrawlerSettings.CRAWL_LOOKBACK_HOURS,String.valueOf(settings.lookbackHours()));
        values.putIfAbsent(CrawlerSettings.CRON_INTERVAL_MINUTES,"60");
        return new LinkedHashMap<>(Map.of("values",values,"cronEnabled",bool(values.get(CrawlerSettings.CRON_ENABLED)),"lookbackHours",Integer.parseInt(values.get(CrawlerSettings.CRAWL_LOOKBACK_HOURS))));
    }

    @PutMapping("/crawl/config")
    public Map<String,Object> updateConfig(@RequestBody ConfigRequest request){
        if(request.cronEnabled()!=null) save(CrawlerSettings.CRON_ENABLED,String.valueOf(request.cronEnabled()),"Enable/disable scheduled crawler execution");
        if(request.lookbackHours()!=null){if(request.lookbackHours()<1 || request.lookbackHours()>720) throw bad("lookbackHours must be between 1 and 720"); save(CrawlerSettings.CRAWL_LOOKBACK_HOURS,String.valueOf(request.lookbackHours()),"Crawler lookback window in hours");}
        if(request.cronIntervalMinutes()!=null){if(request.cronIntervalMinutes()<1 || request.cronIntervalMinutes()>1440) throw bad("cronIntervalMinutes must be between 1 and 1440"); save(CrawlerSettings.CRON_INTERVAL_MINUTES,String.valueOf(request.cronIntervalMinutes()),"Configured crawl interval in minutes");}
        return getConfig();
    }

    @GetMapping("/seeds") public List<CrawlSource> seeds(){return sourceRepository.findAll();}

    @PostMapping("/seeds")
    @ResponseStatus(HttpStatus.CREATED)
    public CrawlSource addSeed(@RequestBody SeedRequest request){
        if(request.slug()==null || request.slug().isBlank() || request.url()==null || request.url().isBlank()) throw bad("slug and url are required");
        if(sourceRepository.findBySlug(request.slug()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Seed slug already exists");
        CrawlSource s=new CrawlSource(); s.setSlug(request.slug().trim()); s.setName(request.name()==null?request.slug():request.name()); s.setUrl(request.url().trim()); s.setSourceKind(request.sourceKind()==null?"GENERIC":request.sourceKind()); s.setEnabled(request.enabled()==null || request.enabled()); s.setRateLimitRpm(request.rateLimitRpm()==null?8:request.rateLimitRpm()); s.setCrawlDelayMs(request.crawlDelayMs()==null?1500:request.crawlDelayMs()); return sourceRepository.save(s);
    }

    @PutMapping("/seeds/{id}/enabled")
    public CrawlSource setSeedEnabled(@PathVariable Integer id,@RequestBody EnabledRequest request){
        CrawlSource s=sourceRepository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Seed not found"));
        s.setEnabled(request.enabled()); return sourceRepository.save(s);
    }

    @GetMapping("/crawls") public List<CrawlJob> crawls(){return jobRepository.findTop50ByOrderByCreatedAtDesc();}

    @GetMapping("/crawls/{id}")
    public Map<String,Object> crawl(@PathVariable Integer id){
        CrawlJob job=jobRepository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Crawl not found"));
        return Map.of("crawl",job,"subRuns",subRunRepository.findByJobIdOrderByIdAsc(id),"logs",logRepository.findByJobIdOrderByIdAsc(id));
    }

    @GetMapping("/crawls/{id}/subruns") public List<CrawlSubRun> subRuns(@PathVariable Integer id){return subRunRepository.findByJobIdOrderByIdAsc(id);}

    private void save(String key,String value,String description){configRepository.save(new CrawlerConfig(key,value,description));}
    private static boolean bool(String value){return "true".equalsIgnoreCase(value)||"1".equals(value)||"yes".equalsIgnoreCase(value);}
    private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    public record ConfigRequest(Boolean cronEnabled,Integer lookbackHours,Integer cronIntervalMinutes){}
    public record SeedRequest(String slug,String name,String url,String sourceKind,Boolean enabled,Integer rateLimitRpm,Integer crawlDelayMs){}
    public record EnabledRequest(boolean enabled){}
}
