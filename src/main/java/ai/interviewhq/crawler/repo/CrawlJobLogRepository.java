package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.CrawlJobLog;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlJobLogRepository extends DynamoRepository<CrawlJobLog,Integer> {
    public CrawlJobLogRepository(DynamoDbRepositorySupport db){super(db);}
    public CrawlJobLog save(CrawlJobLog e){if(e.getId()==null)e.setId(db.nextId("crawl-job-log"));if(e.getCreatedAt()==null)e.setCreatedAt(Instant.now());if(e.getMeta()==null)e.setMeta(new java.util.LinkedHashMap<>());if(e.getLevel()==null)e.setLevel("info");if(e.getEventCode()==null)e.setEventCode("note");return db.save(e,"JOBLOG#"+e.getJobId(),String.format("LOG#%013d#%010d",e.getCreatedAt().toEpochMilli(),e.getId()));}
    public Optional<CrawlJobLog> findById(Integer id){return findAll().stream().filter(e->id.equals(e.getId())).findFirst();}
    public List<CrawlJobLog> findAll(){return db.scan(CrawlJobLog.class);}
    public List<CrawlJobLog> findByJobIdOrderByIdAsc(Integer jobId){return db.query(CrawlJobLog.class,"JOBLOG#"+jobId).stream().sorted(Comparator.comparing(CrawlJobLog::getId,Comparator.nullsLast(Comparator.naturalOrder()))).toList();}
    protected void deleteKey(Integer id){findById(id).ifPresent(e->db.delete("JOBLOG#"+e.getJobId(),String.format("LOG#%013d#%010d",e.getCreatedAt().toEpochMilli(),e.getId())));}
}