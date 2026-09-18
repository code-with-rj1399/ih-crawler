package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.Company;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class CompanyRepository extends DynamoRepository<Company, Integer> {
    public CompanyRepository(DynamoDbRepositorySupport db) { super(db); }
    public Company save(Company e) {
        if (e.getId()==null) e.setId(db.nextId("company"));
        if (e.getCreatedAt()==null) e.setCreatedAt(Instant.now());
        if (e.getAliases()==null) e.setAliases(new ArrayList<>());
        return db.save(e,"COMPANY#"+e.getSlug(),"ENTITY");
    }
    public Optional<Company> findById(Integer id){ return findAll().stream().filter(e->id.equals(e.getId())).findFirst(); }
    public List<Company> findAll(){ return db.scan(Company.class); }
    public Optional<Company> findBySlug(String slug){ return db.find(Company.class,"COMPANY#"+slug,"ENTITY"); }
    public Optional<Company> findByNameIgnoreCase(String name){ return findAll().stream().filter(e->e.getName()!=null&&e.getName().equalsIgnoreCase(name)).findFirst(); }
    protected void deleteKey(Integer id){ findById(id).ifPresent(e->db.delete("COMPANY#"+e.getSlug(),"ENTITY")); }
}