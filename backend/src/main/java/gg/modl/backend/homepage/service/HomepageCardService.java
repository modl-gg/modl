package gg.modl.backend.homepage.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.data.HomepageCardEdit;
import gg.modl.backend.homepage.repository.HomepageCardPatch;
import gg.modl.backend.homepage.repository.HomepageCardRepository;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.service.KnowledgebaseArticleService;
import gg.modl.backend.knowledgebase.service.KnowledgebaseCategoryService;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HomepageCardService {
    private static final String CATEGORY_DROPDOWN_ACTION_TYPE = "category_dropdown";

    private final HomepageCardRepository homepageCardRepository;
    private final KnowledgebaseCategoryService categoryService;
    private final KnowledgebaseArticleService articleService;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<HomepageCard> getVisibleCards(Server server) {
        return homepageCardRepository.findVisibleOrdered();
    }

    public List<EnrichedCardWithArticles> getVisibleCardsEnrichedWithArticles(Server server) {
        return unitOfWork.read(() -> enrichWithArticles(server));
    }

    private List<EnrichedCardWithArticles> enrichWithArticles(Server server) {
        List<HomepageCard> cards = getVisibleCards(server);

        List<String> categoryIds = distinctCategoryIds(cards,
            card -> CATEGORY_DROPDOWN_ACTION_TYPE.equals(card.getActionType()));

        Map<String, KnowledgebaseCategory> categoriesById = categoriesByIdMap(server, categoryIds);

        Map<String, List<KnowledgebaseArticle>> articlesByCategoryId =
            articleService.getVisibleArticlesGroupedByCategoryIds(server, categoryIds);

        return cards.stream()
            .map(card -> {
                KnowledgebaseCategory category = null;
                if (CATEGORY_DROPDOWN_ACTION_TYPE.equals(card.getActionType())
                    && card.getCategoryId() != null && !card.getCategoryId().isEmpty()) {
                    category = categoriesById.get(card.getCategoryId());
                }
                List<KnowledgebaseArticle> articles = category != null
                    ? articlesByCategoryId.getOrDefault(category.getId(), List.of())
                    : List.of();
                return new EnrichedCardWithArticles(card, category, articles);
            })
            .toList();
    }

    public boolean replaceWith(List<HomepageCardEdit> edits) {
        List<HomepageCard> before = homepageCardRepository.findAllOrdered();
        Set<String> existingIds = before.stream().map(HomepageCard::getId).collect(Collectors.toSet());
        Set<String> keptIds = edits.stream().map(HomepageCardEdit::id).filter(existingIds::contains).collect(Collectors.toSet());
        before.stream().map(HomepageCard::getId).filter(id -> !keptIds.contains(id)).forEach(homepageCardRepository::deleteById);
        Instant now = clock.instant();
        List<String> order = new ArrayList<>();
        for (HomepageCardEdit edit : edits) {
            if (edit.id() != null && keptIds.contains(edit.id())) {
                homepageCardRepository.update(edit.id(), patchOf(edit, now));
                order.add(edit.id());
            } else {
                String id = EntityIds.next();
                homepageCardRepository.insert(newCard(id, edit, order.size(), now));
                order.add(id);
            }
        }
        homepageCardRepository.reorder(order);
        return !contentOf(before).equals(contentOf(homepageCardRepository.findAllOrdered()));
    }

    private static HomepageCardPatch patchOf(HomepageCardEdit edit, Instant now) {
        return HomepageCardPatch.builder()
            .title(edit.title())
            .description(edit.description())
            .icon(edit.icon())
            .actionType(edit.action().storedType())
            .actionUrl(edit.actionUrl())
            .categoryId(edit.categoryId())
            .enabled(edit.enabled())
            .updatedAt(now)
            .build();
    }

    private static HomepageCard newCard(String id, HomepageCardEdit edit, int ordinal, Instant now) {
        return HomepageCard.builder()
            .id(id)
            .title(edit.title())
            .description(edit.description())
            .icon(edit.icon())
            .actionType(edit.action().storedType())
            .actionUrl(edit.actionUrl())
            .categoryId(edit.categoryId())
            .ordinal(ordinal)
            .isEnabled(edit.enabled())
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    private static List<List<Object>> contentOf(List<HomepageCard> cards) {
        return cards.stream()
            .map(card -> Arrays.<Object>asList(card.getId(), card.getTitle(), card.getDescription(), card.getIcon(), card.getActionType(),
                card.getActionUrl(), card.getCategoryId(), card.isEnabled()))
            .toList();
    }

    public List<EnrichedCard> getAllCardsEnriched(Server server) {
        return unitOfWork.read(() -> enrichWithCategories(server));
    }

    private List<EnrichedCard> enrichWithCategories(Server server) {
        List<HomepageCard> cards = getAllCards(server);

        List<String> categoryIds = distinctCategoryIds(cards, card -> true);

        Map<String, KnowledgebaseCategory> categoriesById = categoriesByIdMap(server, categoryIds);

        return cards.stream()
            .map(card -> {
                EmbeddedCategory embedded = null;
                if (card.getCategoryId() != null && !card.getCategoryId().isEmpty()) {
                    KnowledgebaseCategory cat = categoriesById.get(card.getCategoryId());
                    if (cat != null) {
                        embedded = new EmbeddedCategory(cat.getId(), cat.getName(), cat.getSlug());
                    }
                }
                return new EnrichedCard(card, embedded);
            })
            .toList();
    }

    public List<HomepageCard> getAllCards(Server server) {
        return homepageCardRepository.findAllOrdered();
    }

    private List<String> distinctCategoryIds(List<HomepageCard> cards, Predicate<HomepageCard> filter) {
        return cards.stream()
            .filter(filter)
            .map(HomepageCard::getCategoryId)
            .filter(id -> id != null && !id.isEmpty())
            .distinct()
            .toList();
    }

    private Map<String, KnowledgebaseCategory> categoriesByIdMap(Server server, List<String> categoryIds) {
        return categoryService.getCategoriesByIds(server, categoryIds)
            .stream()
            .collect(Collectors.toMap(KnowledgebaseCategory::getId, Function.identity()));
    }

    public record EmbeddedCategory(String id, String name, String slug) {}

    public record EnrichedCard(
        HomepageCard card,
        @Nullable EmbeddedCategory category
    ) {}

    public record EnrichedCardWithArticles(
        HomepageCard card,
        @Nullable KnowledgebaseCategory category,
        List<KnowledgebaseArticle> articles
    ) {}
}
