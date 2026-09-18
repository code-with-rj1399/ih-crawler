package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.CrawlJob;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlJobRepository extends DynamoRepository<CrawlJob,Integer> {
    public CrawlJobRepository(DynamoDbRepositorySupport db){super(db);}
    public CrawlJob save(CrawlJob e){if(e.getId()==null)e.setId(db.nextId("crawl-job"));if(e.getCreatedAt()==null)e.setCreatedAt(Instant.now());if(e.getStatus()==null)e.setStatus(CrawlJob.QUEUED);if(e.getTrigger()==null)e.setTrigger("manual");return db.save(e,"JOB#"+e.getId(),"ENTITY");}
    public Optional<CrawlJob> findById(Integer id){return db.find(CrawlJob.class,"JOB#"+id,"ENTITY");}
    public List<CrawlJob> findAll(){return db.scan(CrawlJob.class);}
    public boolean existsByStatus(String status){return findAll().stream().anyMatch(e->status.equals(e.getStatus()));}
    public List<CrawlJob> findTop50ByOrderByCreatedAtDesc(){return findAll().stream().sorted(Comparator.comparing(CrawlJob::getCreatedAt,Comparator.nullsLast(Comparator.naturalOrder())).reversed()).limit(50).toList();}
    protected void deleteKey(Integer id){db.delete("JOB#"+id,"ENTITY");}
}