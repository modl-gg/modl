package gg.modl.backend.homepage.repository;

import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;

import gg.modl.backend.content.ContentRows;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.HomepageCardsRecord;
import gg.modl.backend.homepage.data.HomepageCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqHomepageCardRepository implements HomepageCardRepository {
    private static final List<SortField<?>> DISPLAY_ORDER =
        List.of(HOMEPAGE_CARDS.ORDINAL.asc(), HOMEPAGE_CARDS.CREATED_AT.asc(), HOMEPAGE_CARDS.ID.asc());
    private static final int NO_ORDINAL = -1;

    private final DSLContext dsl;

    @Override
    public void insert(HomepageCard card) {
        HomepageCardsRecord row = new HomepageCardsRecord();
        row.setId(TextLimits.require(HOMEPAGE_CARDS.ID, card.getId()));
        row.setTitle(TextLimits.require(HOMEPAGE_CARDS.TITLE, card.getTitle()));
        row.setDescription(card.getDescription());
        row.setIcon(TextLimits.requireIfPresent(HOMEPAGE_CARDS.ICON, card.getIcon()));
        row.setIconColor(TextLimits.requireIfPresent(HOMEPAGE_CARDS.ICON_COLOR, card.getIconColor()));
        row.setActionType(TextLimits.requireIfPresent(HOMEPAGE_CARDS.ACTION_TYPE, card.getActionType()));
        row.setActionUrl(card.getActionUrl());
        row.setActionButtonText(TextLimits.requireIfPresent(HOMEPAGE_CARDS.ACTION_BUTTON_TEXT, card.getActionButtonText()));
        row.setCategoryId(TextLimits.requireIfPresent(HOMEPAGE_CARDS.CATEGORY_ID, existingCategoryId(card.getCategoryId())));
        row.setBackgroundColor(TextLimits.requireIfPresent(HOMEPAGE_CARDS.BACKGROUND_COLOR, card.getBackgroundColor()));
        row.setOrdinal(card.getOrdinal());
        row.setIsEnabled(card.isEnabled());
        row.setCreatedAt(card.getCreatedAt());
        row.setUpdatedAt(card.getUpdatedAt());
        dsl.insertInto(HOMEPAGE_CARDS).set(row).execute();
    }

    @Override
    public boolean hasAny() {
        return dsl.fetchExists(HOMEPAGE_CARDS);
    }

    @Override
    public List<HomepageCard> findAllOrdered() {
        return dsl.selectFrom(HOMEPAGE_CARDS)
            .orderBy(DISPLAY_ORDER)
            .fetch()
            .map(JooqHomepageCardRepository::toCard);
    }

    @Override
    public List<HomepageCard> findVisibleOrdered() {
        return dsl.selectFrom(HOMEPAGE_CARDS)
            .where(HOMEPAGE_CARDS.IS_ENABLED.eq(true))
            .orderBy(DISPLAY_ORDER)
            .fetch()
            .map(JooqHomepageCardRepository::toCard);
    }

    @Override
    public Optional<HomepageCard> findById(String id) {
        return dsl.selectFrom(HOMEPAGE_CARDS)
            .where(HOMEPAGE_CARDS.ID.eq(id))
            .fetchOptional()
            .map(JooqHomepageCardRepository::toCard);
    }

    @Override
    public int findMaxOrdinal() {
        return dsl.select(DSL.coalesce(DSL.max(HOMEPAGE_CARDS.ORDINAL), NO_ORDINAL))
            .from(HOMEPAGE_CARDS)
            .fetchSingle()
            .value1();
    }

    @Override
    public Optional<HomepageCard> update(String id, HomepageCardPatch patch) {
        Map<Field<?>, Object> changes = new LinkedHashMap<>();
        changes.put(HOMEPAGE_CARDS.UPDATED_AT, patch.updatedAt());
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.TITLE, TextLimits.requireIfPresent(HOMEPAGE_CARDS.TITLE, patch.title()));
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.DESCRIPTION, patch.description());
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.ICON, TextLimits.requireIfPresent(HOMEPAGE_CARDS.ICON, patch.icon()));
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.ICON_COLOR,
            TextLimits.requireIfPresent(HOMEPAGE_CARDS.ICON_COLOR, patch.iconColor()));
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.ACTION_TYPE,
            TextLimits.requireIfPresent(HOMEPAGE_CARDS.ACTION_TYPE, patch.actionType()));
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.ACTION_URL, patch.actionUrl());
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.ACTION_BUTTON_TEXT,
            TextLimits.requireIfPresent(HOMEPAGE_CARDS.ACTION_BUTTON_TEXT, patch.actionButtonText()));
        if (patch.categoryId() != null) {
            changes.put(HOMEPAGE_CARDS.CATEGORY_ID,
                TextLimits.requireIfPresent(HOMEPAGE_CARDS.CATEGORY_ID, existingCategoryId(patch.categoryId())));
        }
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.BACKGROUND_COLOR,
            TextLimits.requireIfPresent(HOMEPAGE_CARDS.BACKGROUND_COLOR, patch.backgroundColor()));
        ContentRows.putIfPresent(changes, HOMEPAGE_CARDS.IS_ENABLED, patch.enabled());
        dsl.update(HOMEPAGE_CARDS).set(changes).where(HOMEPAGE_CARDS.ID.eq(id)).execute();
        return findById(id);
    }

    @Override
    public boolean deleteById(String id) {
        return dsl.deleteFrom(HOMEPAGE_CARDS).where(HOMEPAGE_CARDS.ID.eq(id)).execute() > 0;
    }

    @Override
    public void reorder(List<String> ids) {
        ContentRows.renumber(dsl, HOMEPAGE_CARDS, HOMEPAGE_CARDS.ID, HOMEPAGE_CARDS.ORDINAL, ids, DSL.noCondition());
    }

    @Nullable
    private String existingCategoryId(@Nullable String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        return dsl.select(KNOWLEDGEBASE_CATEGORIES.ID)
            .from(KNOWLEDGEBASE_CATEGORIES)
            .where(KNOWLEDGEBASE_CATEGORIES.ID.eq(categoryId.stripTrailing()))
            .fetchOne(KNOWLEDGEBASE_CATEGORIES.ID);
    }

    private static HomepageCard toCard(HomepageCardsRecord row) {
        return HomepageCard.builder()
            .id(row.getId())
            .title(row.getTitle())
            .description(row.getDescription())
            .icon(row.getIcon())
            .iconColor(row.getIconColor())
            .actionType(row.getActionType())
            .actionUrl(row.getActionUrl())
            .actionButtonText(row.getActionButtonText())
            .categoryId(row.getCategoryId())
            .backgroundColor(row.getBackgroundColor())
            .ordinal(row.getOrdinal())
            .isEnabled(row.getIsEnabled())
            .createdAt(row.getCreatedAt())
            .updatedAt(row.getUpdatedAt())
            .build();
    }
}
