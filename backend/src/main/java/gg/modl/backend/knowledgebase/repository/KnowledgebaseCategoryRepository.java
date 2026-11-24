package gg.modl.backend.knowledgebase.repository;

import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgebaseCategoryRepository {
    void insert(KnowledgebaseCategory category);

    boolean hasAny();

    List<KnowledgebaseCategory> findAllOrdered();

    List<KnowledgebaseCategory> findVisibleOrdered();

    Optional<KnowledgebaseCategory> findById(String id);

    List<KnowledgebaseCategory> findByIds(Collection<String> ids);

    int findMaxOrdinal();

    Optional<KnowledgebaseCategory> update(String id, KnowledgebaseCategoryPatch patch);

    boolean deleteById(String id);

    void reorder(List<String> ids);
}
