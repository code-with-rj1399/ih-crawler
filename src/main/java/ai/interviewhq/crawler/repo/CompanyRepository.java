package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Integer> {

    Optional<Company> findBySlug(String slug);

    Optional<Company> findByNameIgnoreCase(String name);
}
