package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.InterviewPost;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class InterviewPostRepository extends DynamoRepository<InterviewPost,Integer> {
    public InterviewPostRepository(DynamoDbRepositorySupport db){super(db);}
    public InterviewPost save(InterviewPost e){if(e.getId()==null)e.setId(db.nextId("interview-post"));if(e.getCreatedAt()==null)e.setCreatedAt(Instant.now());return db.save(e,"POST#"+e.getSourceId()+"#"+db.hashKey(e.getUrl()),"ENTITY");}
    public Optional<InterviewPost> findById(Integer id){return findAll().stream().filter(e->id.equals(e.getId())).findFirst();}
    public List<InterviewPost> findAll(){return db.scan(InterviewPost.class);}
    public Optional<InterviewPost> findBySourceIdAndUrl(Integer sourceId,String url){return db.find(InterviewPost.class,"POST#"+sourceId+"#"+db.hashKey(url),"ENTITY");}
    public List<InterviewPost> findByExtractedFalseOrderByPostedAtDescIdDesc(Pageable pageable){int limit=pageable==null?50:pageable.getPageSize();return findAll().stream().filter(e->!e.isExtracted()).sorted(Comparator.comparing(InterviewPost::getPostedAt,Comparator.nullsLast(Comparator.naturalOrder())).reversed().thenComparing(InterviewPost::getId,Comparator.nullsLast(Comparator.reverseOrder()))).limit(limit).toList();}
    protected void deleteKey(Integer id){findById(id).ifPresent(e->db.delete("POST#"+e.getSourceId()+"#"+db.hashKey(e.getUrl()),"ENTITY"));}
}