package gg.modl.backend.homepage.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.repository.HomepageCardPatch;
import gg.modl.backend.homepage.repository.HomepageCardRepository;
import gg.modl.backend.infrastructure.validation.RequiredText;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.service.KnowledgebaseArticleService;
import gg.modl.backend.knowledgebase.service.KnowledgebaseCategoryService;
import gg.modl.backend.infrastructure.validation.SafeUrls;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.CreateCardRequest;
import gg.modl.proto.modl.v1.UpdateCardRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public Optional<HomepageCard> getCardById(Server server, String id) {
        return homepageCardRepository.findById(id);
    }

    public HomepageCard createCard(Server server, CreateCardRequest request) {
        String actionUrl = request.hasActionUrl() ? request.getActionUrl() : null;
        SafeUrls.requireSafe(actionUrl, "Invalid card URL");
        return unitOfWork.write(() -> insertCard(request, actionUrl));
    }

    private HomepageCard insertCard(CreateCardRequest request, @Nullable String actionUrl) {
        String id = EntityIds.next();
        Instant now = clock.instant();
        homepageCardRepository.insert(HomepageCard.builder()
            .id(id)
            .title(request.getTitle())
            .description(request.hasDescription() ? request.getDescription() : null)
            .icon(request.hasIcon() ? request.getIcon() : null)
            .iconColor(request.hasIconColor() ? request.getIconColor() : null)
            .actionType(request.hasActionType() ? request.getActionType() : null)
            .actionUrl(actionUrl)
            .actionButtonText(request.hasActionButtonText() ? request.getActionButtonText() : null)
            .categoryId(request.hasCategoryId() ? request.getCategoryId() : null)
            .backgroundColor(request.hasBackgroundColor() ? request.getBackgroundColor() : null)
            .ordinal(homepageCardRepository.findMaxOrdinal() + 1)
            .isEnabled(!request.hasIsEnabled() || request.getIsEnabled())
            .createdAt(now)
            .updatedAt(now)
            .build());
        return homepageCardRepository.findById(id).orElseThrow();
    }

    public Optional<HomepageCard> updateCard(Server server, String id, UpdateCardRequest request) {
        String actionUrl = request.hasActionUrl() ? request.getActionUrl() : null;
        SafeUrls.requireSafe(actionUrl, "Invalid card URL");
        HomepageCardPatch patch = HomepageCardPatch.builder()
            .title(RequiredText.notBlankIfPresent(request.hasTitle() ? request.getTitle() : null, "The card title"))
            .description(request.hasDescription() ? request.getDescription() : null)
            .icon(request.hasIcon() ? request.getIcon() : null)
            .iconColor(request.hasIconColor() ? request.getIconColor() : null)
            .actionType(request.hasActionType() ? request.getActionType() : null)
            .actionUrl(actionUrl)
            .actionButtonText(request.hasActionButtonText() ? request.getActionButtonText() : null)
            .categoryId(request.hasCategoryId() ? request.getCategoryId() : null)
            .backgroundColor(request.hasBackgroundColor() ? request.getBackgroundColor() : null)
            .enabled(request.hasIsEnabled() ? request.getIsEnabled() : null)
            .updatedAt(clock.instant())
            .build();
        return unitOfWork.write(() -> homepageCardRepository.update(id, patch));
    }

    public boolean deleteCard(Server server, String id) {
        return unitOfWork.write(() -> homepageCardRepository.deleteById(id));
    }

    public void reorderCards(Server server, List<String> ids) {
        unitOfWork.write(() -> homepageCardRepository.reorder(ids));
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
