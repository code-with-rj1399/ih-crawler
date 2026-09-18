package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.CrawlPage;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlPageRepository extends DynamoRepository<CrawlPage,Integer> {
    public CrawlPageRepository(DynamoDbRepositorySupport db){super(db);}
    public CrawlPage save(CrawlPage e){if(e.getId()==null)e.setId(db.nextId("crawl-page"));if(e.getFetchedAt()==null)e.setFetchedAt(Instant.now());return db.save(e,"PAGE#"+db.hashKey(e.getUrl()),"ENTITY");}
    public Optional<CrawlPage> findById(Integer id){return findAll().stream().filter(e->id.equals(e.getId())).findFirst();}
    public List<CrawlPage> findAll(){return db.scan(CrawlPage.class);}
    public Optional<CrawlPage> findByUrl(String url){return db.find(CrawlPage.class,"PAGE#"+db.hashKey(url),"ENTITY");}
    protected void deleteKey(Integer id){findById(id).ifPresent(e->db.delete("PAGE#"+db.hashKey(e.getUrl()),"ENTITY"));}
}