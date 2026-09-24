package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.CrawlSubRun;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlSubRunRepository extends DynamoRepository<CrawlSubRun,Integer> {
    public CrawlSubRunRepository(DynamoDbRepositorySupport db){super(db);}
    public CrawlSubRun save(CrawlSubRun e){if(e.getId()==null)e.setId(db.nextId("crawl-subrun"));if(e.getCreatedAt()==null)e.setCreatedAt(Instant.now());return db.save(e,"SUBRUN#"+e.getJobId(),String.format("SOURCE#%010d#%010d",e.getSourceId(),e.getId()));}
    public Optional<CrawlSubRun> findById(Integer id){return findAll().stream().filter(e->id.equals(e.getId())).findFirst();}
    public List<CrawlSubRun> findAll(){return db.scan(CrawlSubRun.class);}
    public List<CrawlSubRun> findByJobIdOrderByIdAsc(Integer jobId){return db.query(CrawlSubRun.class,"SUBRUN#"+jobId).stream().sorted(Comparator.comparing(CrawlSubRun::getId,Comparator.nullsLast(Comparator.naturalOrder()))).toList();}
    protected void deleteKey(Integer id){findById(id).ifPresent(e->db.delete("SUBRUN#"+e.getJobId(),String.format("SOURCE#%010d#%010d",e.getSourceId(),e.getId())));}
}
