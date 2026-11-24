package gg.modl.backend.homepage.repository;

import gg.modl.backend.homepage.data.HomepageCard;
import java.util.List;
import java.util.Optional;

public interface HomepageCardRepository {
    void insert(HomepageCard card);

    boolean hasAny();

    List<HomepageCard> findAllOrdered();

    List<HomepageCard> findVisibleOrdered();

    Optional<HomepageCard> findById(String id);

    int findMaxOrdinal();

    Optional<HomepageCard> update(String id, HomepageCardPatch patch);

    boolean deleteById(String id);

    void reorder(List<String> ids);
}
