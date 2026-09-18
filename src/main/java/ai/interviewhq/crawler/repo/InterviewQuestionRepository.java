package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class InterviewQuestionRepository extends DynamoRepository<InterviewQuestion,Integer> {
    public InterviewQuestionRepository(DynamoDbRepositorySupport db){super(db);}
    public InterviewQuestion save(InterviewQuestion e){if(e.getId()==null)e.setId(db.nextId("interview-question"));Instant n=Instant.now();if(e.getCreatedAt()==null)e.setCreatedAt(n);if(e.getExtractedAt()==null)e.setExtractedAt(n);if(e.getTopics()==null)e.setTopics(new java.util.ArrayList<>());return db.save(e,"QUESTION#"+e.getDedupeHash(),"ENTITY");}
    public Optional<InterviewQuestion> findById(Integer id){return findAll().stream().filter(e->id.equals(e.getId())).findFirst();}
    public List<InterviewQuestion> findAll(){return db.scan(InterviewQuestion.class);}
    public Optional<InterviewQuestion> findByDedupeHash(String h){return db.find(InterviewQuestion.class,"QUESTION#"+h,"ENTITY");}
    public List<InterviewQuestion> search(String company,Pageable pageable){int limit=pageable==null?50:pageable.getPageSize();return findAll().stream().filter(q->company==null||(q.getCompany()!=null&&q.getCompany().equalsIgnoreCase(company))).sorted(Comparator.comparing(InterviewQuestion::getAskedAt,Comparator.nullsLast(Comparator.naturalOrder())).reversed().thenComparing(InterviewQuestion::getId,Comparator.nullsLast(Comparator.reverseOrder()))).limit(limit).toList();}
    protected void deleteKey(Integer id){findById(id).ifPresent(e->db.delete("QUESTION#"+e.getDedupeHash(),"ENTITY"));}
}