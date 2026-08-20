package com.cognizant.storeops.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Storage base for the repository test doubles.
 *
 * <p>Service-layer unit tests assert this module's business rules, not Hibernate's mapping. Running
 * them against a fake keeps them in-process and fast; the JPA implementations are covered by the
 * {@code @SpringBootTest} integration tests, which exercise the real H2 schema.
 *
 * <p>Insertion order is preserved so assertions can be written positionally.
 *
 * @param <T> aggregate type, always an immutable record
 * @param <I> identifier type
 */
public abstract class FakeRepository<T, I> {

    private final Map<I, T> store = new LinkedHashMap<>();
    private final Function<T, I> identity;

    protected FakeRepository(final Function<T, I> identity) {
        this.identity = identity;
    }

    public T save(final T entity) {
        store.put(identity.apply(entity), entity);
        return entity;
    }

    public Optional<T> findById(final I id) {
        return id == null ? Optional.empty() : Optional.ofNullable(store.get(id));
    }

    public boolean existsById(final I id) {
        return id != null && store.containsKey(id);
    }

    public List<T> findAll() {
        return List.copyOf(store.values());
    }

    protected List<T> findMatching(final Predicate<T> predicate) {
        return store.values().stream().filter(predicate).toList();
    }

    public boolean deleteById(final I id) {
        return id != null && store.remove(id) != null;
    }

    public long count() {
        return store.size();
    }
}
