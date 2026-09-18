package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlSourceRepository extends DynamoRepository<CrawlSource,Integer> {
    public CrawlSourceRepository(DynamoDbRepositorySupport db){super(db);}
    public CrawlSource save(CrawlSource e){Instant n=Instant.now();if(e.getId()==null)e.setId(db.nextId("crawl-source"));if(e.getCreatedAt()==null)e.setCreatedAt(n);e.setUpdatedAt(n);if(e.getParserConfig()==null)e.setParserConfig(new java.util.LinkedHashMap<>());if(e.getRobotsMode()==null)e.setRobotsMode("honor");return db.save(e,"SOURCE#"+e.getSlug(),"ENTITY");}
    public Optional<CrawlSource> findById(Integer id){return findAll().stream().filter(e->id.equals(e.getId())).findFirst();}
    public List<CrawlSource> findAll(){return db.scan(CrawlSource.class);}
    public List<CrawlSource> findByEnabledTrueOrderByIdAsc(){return findAll().stream().filter(CrawlSource::isEnabled).sorted(Comparator.comparing(CrawlSource::getId,Comparator.nullsLast(Comparator.naturalOrder()))).toList();}
    public Optional<CrawlSource> findBySlug(String slug){return db.find(CrawlSource.class,"SOURCE#"+slug,"ENTITY");}
    protected void deleteKey(Integer id){findById(id).ifPresent(e->db.delete("SOURCE#"+e.getSlug(),"ENTITY"));}
}