package gg.modl.backend.knowledgebase.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class JooqKnowledgebaseCategoryRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant LATER = NOW.plusSeconds(60);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void categoriesRoundTripInAStableOrder(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseCategoryRepository repository = new JooqKnowledgebaseCategoryRepository(database.dsl());
            assertThat(repository.hasAny()).isFalse();
            assertThat(repository.findMaxOrdinal()).isEqualTo(-1);
            KnowledgebaseCategory described = category("b", 1, NOW, true);
            described.setDescription("Guides 📘");
            database.unitOfWork().write(() -> {
                repository.insert(category("late", 1, LATER, true));
                repository.insert(described);
                repository.insert(category("hidden", 0, NOW, false));
                repository.insert(category("a", 1, NOW, true));
            });

            assertThat(repository.hasAny()).isTrue();
            assertThat(repository.findMaxOrdinal()).isEqualTo(1);
            assertThat(repository.findById("b")).contains(described);
            assertThat(repository.findAllOrdered()).extracting(KnowledgebaseCategory::getId)
                .containsExactly("hidden", "a", "b", "late");
            assertThat(repository.findVisibleOrdered()).extracting(KnowledgebaseCategory::getId)
                .containsExactly("a", "b", "late");
        }
    }

    @SqlContractTest
    void findByIdsAcceptsMoreIdsThanOneChunk(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseCategoryRepository repository = new JooqKnowledgebaseCategoryRepository(database.dsl());
            List<String> ids = IntStream.range(0, 501).mapToObj(index -> "category-" + index).toList();
            database.unitOfWork().write(() -> ids.forEach(id -> repository.insert(category(id, 0, NOW, true))));

            assertThat(repository.findByIds(ids)).extracting(KnowledgebaseCategory::getId).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(repository.findByIds(List.of())).isEmpty();
        }
    }

    @SqlContractTest
    void anUpdateChangesOnlyThePatchedColumns(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseCategoryRepository repository = new JooqKnowledgebaseCategoryRepository(database.dsl());
            KnowledgebaseCategory category = category("category", 2, NOW, true);
            database.unitOfWork().write(() -> repository.insert(category));

            KnowledgebaseCategoryPatch patch = KnowledgebaseCategoryPatch.builder()
                .name("Renamed").slug("renamed").visible(false).updatedAt(LATER).build();
            Optional<KnowledgebaseCategory> updated = database.unitOfWork().write(() -> repository.update("category", patch));

            category.setName("Renamed");
            category.setSlug("renamed");
            category.setVisible(false);
            category.setUpdatedAt(LATER);
            assertThat(updated).contains(category);
            Optional<KnowledgebaseCategory> missing = database.unitOfWork().write(() -> repository.update("missing", patch));
            assertThat(missing).isEmpty();
        }
    }

    @SqlContractTest
    void aSlugBelongsToOneCategory(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseCategoryRepository repository = new JooqKnowledgebaseCategoryRepository(database.dsl());
            database.unitOfWork().write(() -> repository.insert(category("rules", 0, NOW, true)));
            KnowledgebaseCategory second = category("second", 1, NOW, true);
            second.setSlug("rules");

            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.insert(second)))
                .isInstanceOf(DuplicateRowException.class);
            assertThat(repository.findAllOrdered()).extracting(KnowledgebaseCategory::getId).containsExactly("rules");
        }
    }

    @SqlContractTest
    void reorderAndDeleteChangeTheStoredRows(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseCategoryRepository repository = new JooqKnowledgebaseCategoryRepository(database.dsl());
            database.unitOfWork().write(() -> {
                repository.insert(category("a", 0, NOW, true));
                repository.insert(category("b", 1, NOW, true));
            });

            database.unitOfWork().write(() -> repository.reorder(List.of("b", "a")));

            assertThat(repository.findAllOrdered())
                .extracting(KnowledgebaseCategory::getId, KnowledgebaseCategory::getOrdinal)
                .containsExactly(tuple("b", 0), tuple("a", 1));
            assertThat(database.unitOfWork().write(() -> repository.deleteById("a")).booleanValue()).isTrue();
            assertThat(database.unitOfWork().write(() -> repository.deleteById("a")).booleanValue()).isFalse();
            assertThat(repository.findById("a")).isEmpty();
        }
    }

    private static KnowledgebaseCategory category(String id, int ordinal, Instant createdAt, boolean visible) {
        return KnowledgebaseCategory.builder()
            .id(id)
            .name("Category " + id)
            .slug(id)
            .ordinal(ordinal)
            .isVisible(visible)
            .createdAt(createdAt)
            .updatedAt(createdAt)
            .build();
    }
}
