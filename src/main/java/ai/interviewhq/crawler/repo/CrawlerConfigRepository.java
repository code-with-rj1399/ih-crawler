package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.CrawlerConfig;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlerConfigRepository extends DynamoRepository<CrawlerConfig,String> {
    public CrawlerConfigRepository(DynamoDbRepositorySupport db){super(db);}
    public CrawlerConfig save(CrawlerConfig e){e.setUpdatedAt(Instant.now());return db.save(e,"CONFIG#"+e.getKey(),"ENTITY");}
    public Optional<CrawlerConfig> findById(String key){return db.find(CrawlerConfig.class,"CONFIG#"+key,"ENTITY");}
    public List<CrawlerConfig> findAll(){return db.scan(CrawlerConfig.class);}
    protected void deleteKey(String key){db.delete("CONFIG#"+key,"ENTITY");}
}