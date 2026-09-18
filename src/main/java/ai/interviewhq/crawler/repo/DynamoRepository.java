package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class DynamoRepository<T, K> {
    protected final DynamoDbRepositorySupport db;
    protected DynamoRepository(DynamoDbRepositorySupport db) { this.db = db; }
    public abstract T save(T entity);
    public List<T> saveAll(Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        for (T entity : entities) saved.add(save(entity));
        return saved;
    }
    public abstract Optional<T> findById(K id);
    public abstract List<T> findAll();
    public void deleteById(K id) { deleteKey(id); }
    protected abstract void deleteKey(K id);
    public boolean existsById(K id) { return findById(id).isPresent(); }
}
