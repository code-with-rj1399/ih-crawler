package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.adapters.SourceAdapterRegistry;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.*;
import ai.interviewhq.crawler.extract.ExperienceExtraction;
import ai.interviewhq.crawler.extract.TwoStepOpenAiExtractor;
import ai.interviewhq.crawler.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class CrawlRunner {
    private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);
    private final CrawlSourceRepository sourceRepository;
    private final InterviewPostRepository postRepository;
    private final InterviewQuestionRepository questionRepository;
    private final CrawlJobRepository jobRepository;
    private final CrawlSubRunRepository subRunRepository;
    private final SourceAdapterRegistry adapterRegistry;
    private final PoliteFetcher fetcher;
    private final TwoStepOpenAiExtractor extractor;
    private final CrawlerSettings settings;
    private final ChromiumSiteCrawler chromiumSiteCrawler;

    public CrawlRunner(CrawlSourceRepository sourceRepository, InterviewPostRepository postRepository,
                       InterviewQuestionRepository questionRepository, CrawlJobRepository jobRepository,
                       CrawlSubRunRepository subRunRepository, SourceAdapterRegistry adapterRegistry,
                       PoliteFetcher fetcher, TwoStepOpenAiExtractor extractor, CrawlerSettings settings,
                       ChromiumSiteCrawler chromiumSiteCrawler) {
        this.sourceRepository=sourceRepository; this.postRepository=postRepository; this.questionRepository=questionRepository;
        this.jobRepository=jobRepository; this.subRunRepository=subRunRepository; this.adapterRegistry=adapterRegistry;
        this.fetcher=fetcher; this.extractor=extractor; this.settings=settings; this.chromiumSiteCrawler=chromiumSiteCrawler;
    }

    @Scheduled(cron = "${crawler.cron:0 0 * * * *}", zone = "${crawler.cron-zone:UTC}")
    public void scheduledRun() { if (!settings.getBoolean(CrawlerSettings.CRON_ENABLED,true)) { log.info("Scheduled crawler run skipped: disabled by admin configuration"); return; } runOnce("scheduled"); }
    public synchronized void runOnce() { runOnce("manual"); }

    public synchronized void runOnce(String trigger) {
        Instant started=Instant.now(); List<CrawlSource> sources=sourceRepository.findByEnabledTrueOrderByIdAsc();
        CrawlJob job=new CrawlJob(); job.setTrigger(trigger); job.setStatus(CrawlJob.RUNNING); job.setStartedAt(started); job.setLookbackHours(settings.lookbackHours()); job.setSourcesPlanned(sources.size()); jobRepository.save(job);
        int maxConcurrentTasks=settings.maxConcurrentTasks(); int poolSize=Math.min(maxConcurrentTasks,Math.max(1,sources.size()));
        ExecutorService executor=Executors.newFixedThreadPool(poolSize,r->{Thread t=new Thread(r);t.setName("crawler-task-"+t.getId());t.setDaemon(true);return t;});
        try {
            List<Future<?>> futures=new ArrayList<>(); for(CrawlSource source:sources) futures.add(executor.submit(()->crawlSource(job.getId(),source)));
            for(Future<?> f:futures) try{f.get();}catch(Exception e){log.error("Crawl task failed",e);}
            List<CrawlSubRun> runs=subRunRepository.findByJobIdOrderByIdAsc(job.getId());
            int ok=0,failed=0,pages=0,skipped=0,posts=0,questions=0,blocked=0;
            for(CrawlSubRun r:runs){if("succeeded".equals(r.getStatus()))ok++;else failed++;pages+=r.getPagesFetched();skipped+=r.getPagesSkipped();posts+=r.getPostsExtracted();questions+=r.getQuestionsUpserted();blocked+=r.getBlockedCount();}
            job.setSourcesOk(ok);job.setSourcesFailed(failed);job.setPagesFetched(pages);job.setPagesSkipped(skipped);job.setPostsExtracted(posts);job.setQuestionsUpserted(questions);job.setBlockedCount(blocked);job.setFinishedAt(Instant.now()); job.setStatus(failed==0?CrawlJob.SUCCEEDED:(ok==0?CrawlJob.FAILED:CrawlJob.PARTIAL)); jobRepository.save(job);
        } catch(Exception e) { job.setStatus(CrawlJob.FAILED); job.setErrorSummary(e.getMessage()); job.setFinishedAt(Instant.now()); jobRepository.save(job); throw new IllegalStateException("Crawler run failed",e); }
        finally {executor.shutdown();}
    }

    private void crawlSource(Integer jobId,CrawlSource source){
        CrawlSubRun sub=new CrawlSubRun();sub.setJobId(jobId);sub.setSourceId(source.getId());sub.setSourceSlug(source.getSlug());sub.setStatus("running");sub.setStartedAt(Instant.now());subRunRepository.save(sub);
        Instant cutoff=Instant.now().minus(settings.lookbackHours(),ChronoUnit.HOURS);int cap=Math.max(1,settings.extractMaxPostsPerSource());
        int[] processed={0},savedPosts={0},savedQuestions={0},skipped={0},modelCalls={0},blocked={0};Set<String> seenHashes=new HashSet<>();
        try {
            SourceAdapter adapter=adapterRegistry.require(source.getSourceKind());
            try{adapter.crawlStreaming(source,cutoff,fetcher,e->processEntry(source,e,cutoff,cap,processed,savedPosts,savedQuestions,skipped,modelCalls,seenHashes));}
            catch(FetchBlockedException ex){blocked[0]++;chromiumSiteCrawler.crawlStreaming(source,cutoff,cap,3,e->processEntry(source,e,cutoff,cap,processed,savedPosts,savedQuestions,skipped,modelCalls,seenHashes));}
            sub.setStatus("succeeded");
        } catch(Exception e){sub.setStatus("failed");sub.setErrorSummary(e.getMessage());log.error("Source crawl failed: source={}",source.getSlug(),e);}
        finally{sub.setFinishedAt(Instant.now());sub.setPagesFetched(processed[0]);sub.setPagesSkipped(skipped[0]);sub.setPostsExtracted(savedPosts[0]);sub.setQuestionsUpserted(savedQuestions[0]);sub.setModelCalls(modelCalls[0]);sub.setBlockedCount(blocked[0]);subRunRepository.save(sub);}
    }

    private void processEntry(CrawlSource source,ParsedEntry entry,Instant cutoff,int cap,int[] processed,int[] savedPosts,int[] savedQuestions,int[] skipped,int[] modelCalls,Set<String> seenHashes){
        if(processed[0]>=cap){skipped[0]++;return;} processed[0]++; if(!isEligible(entry,cutoff)||entry.bodyText()==null||entry.bodyText().isBlank()){skipped[0]++;return;}
        String postUrl=entry.canonicalUrl()!=null?entry.canonicalUrl():entry.url();
        try{TwoStepOpenAiExtractor.ExtractionResult result=extractor.extract(source,postUrl,entry.title(),entry.author(),entry.publishedAt(),entry.bodyText()); ExperienceExtraction experience=result.experience(); modelCalls[0]+=experience!=null&&!experience.questions().isEmpty()?2:1; if(experience==null){skipped[0]++;return;}
            InterviewPost post=new InterviewPost();post.setSourceId(source.getId());post.setUrl(postUrl);post.setTitle(experience.title());post.setAuthor(experience.author());post.setPostedAt(experience.postedAt()!=null?experience.postedAt():entry.publishedAt());post.setSummary(experience.summary());post.setRawCompany(experience.company());post.setRawRole(experience.role());post.setExperienceLevel(experience.level());post.setLocation(experience.location());post.setCandidateYoE(experience.candidateYoE());postRepository.save(post);savedPosts[0]++;
            for(InterviewQuestion q:result.questions()){String hash=q.getDedupeHash();if(hash!=null&&!seenHashes.add(hash))continue;questionRepository.save(q);savedQuestions[0]++;}
        }catch(Exception e){log.error("AI extraction failed for {}",postUrl,e);skipped[0]++;}
    }
    private boolean isEligible(ParsedEntry entry,Instant cutoff){Instant publishedAt=entry.publishedAt();return publishedAt!=null&&!publishedAt.isBefore(cutoff);}
}
