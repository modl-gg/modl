package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class ContentMapper {
    private static final int ID_LIMIT = 36;
    private static final String ORPHAN_CATEGORY_NAME = "Imported articles";
    private static final String ORPHAN_CATEGORY_SLUG = "imported-articles";

    private final ImportContext context;
    private final ImportFit fit;
    private final Map<String, String> categoryIds = new HashMap<>();
    private final Set<String> categorySlugs = new HashSet<>();
    private final Set<String> articleSlugs = new HashSet<>();
    private final List<KnowledgebaseCategory> categories = new ArrayList<>();
    private @Nullable KnowledgebaseCategory orphanCategory;

    public ContentMapper(ImportContext context) {
        this.context = context;
        this.fit = context.fit();
        for (ArchiveDocument source : context.index().categories()) {
            category(source).ifPresent(categories::add);
        }
    }

    public List<KnowledgebaseCategory> categories() {
        return List.copyOf(categories);
    }

    public Optional<KnowledgebaseCategory> orphanCategory() {
        return Optional.ofNullable(orphanCategory);
    }

    private Optional<KnowledgebaseCategory> category(ArchiveDocument source) {
        String sourceId = source.id();
        String name = fit.columnIfPresent(KNOWLEDGEBASE_CATEGORIES.NAME, source.text("name"));
        if (sourceId == null || name == null || name.isBlank()) {
            context.issues().report(IssueCode.RECORD_REJECTED, "category");
            context.issues().skipped(ImportSubject.CATEGORIES);
            return Optional.empty();
        }
        String id = context.ids().adopt(IdKind.CATEGORY, "", sourceId, ID_LIMIT);
        categoryIds.put(sourceId, id);
        Instant created = Objects.requireNonNullElse(source.instant("createdAt"), context.exportTime());
        context.issues().imported(ImportSubject.CATEGORIES);
        return Optional.of(KnowledgebaseCategory.builder()
            .id(id)
            .name(name)
            .slug(uniqueSlug(fit.columnIfPresent(KNOWLEDGEBASE_CATEGORIES.SLUG, source.text("slug")), name, id, categorySlugs))
            .description(fit.textIfPresent(source.string("description")))
            .ordinal(Objects.requireNonNullElse(source.intValue("ordinal"), 0))
            .isVisible(!Boolean.FALSE.equals(source.boolOrNull("isVisible")))
            .createdAt(created)
            .updatedAt(Objects.requireNonNullElse(source.instant("updatedAt"), created))
            .build());
    }

    public Optional<KnowledgebaseArticle> article(ArchiveDocument source) {
        String sourceId = source.id();
        String title = fit.columnIfPresent(KNOWLEDGEBASE_ARTICLES.TITLE, source.text("title"));
        if (sourceId == null || title == null || title.isBlank()) {
            context.issues().report(IssueCode.RECORD_REJECTED, "article");
            context.issues().skipped(ImportSubject.ARTICLES);
            return Optional.empty();
        }
        String id = context.ids().adopt(IdKind.ARTICLE, "", sourceId, ID_LIMIT);
        String categoryId = categoryIds.get(source.idOf("categoryId"));
        if (categoryId == null) {
            categoryId = orphanCategory().map(KnowledgebaseCategory::getId).orElseGet(this::createOrphanCategory);
            context.issues().report(IssueCode.ARTICLE_ORPHANED, sourceId);
        }
        Instant created = Objects.requireNonNullElse(source.instant("createdAt"), context.exportTime());
        context.issues().imported(ImportSubject.ARTICLES);
        return Optional.of(KnowledgebaseArticle.builder()
            .id(id)
            .title(title)
            .slug(uniqueSlug(fit.columnIfPresent(KNOWLEDGEBASE_ARTICLES.SLUG, source.text("slug")), title, id, articleSlugs))
            .content(fit.text(context.urls().rewriteText(Objects.requireNonNullElse(source.string("content"), ""))))
            .categoryId(categoryId)
            .ordinal(Objects.requireNonNullElse(source.intValue("ordinal"), 0))
            .isVisible(!Boolean.FALSE.equals(source.boolOrNull("isVisible")))
            .createdAt(created)
            .updatedAt(Objects.requireNonNullElse(source.instant("updatedAt"), created))
            .build());
    }

    public List<HomepageCard> cards() {
        List<HomepageCard> cards = new ArrayList<>();
        for (ArchiveDocument source : context.index().cards()) {
            String sourceId = source.id();
            String title = fit.columnIfPresent(HOMEPAGE_CARDS.TITLE, source.text("title"));
            if (sourceId == null || title == null) {
                context.issues().skipped(ImportSubject.CARDS);
                continue;
            }
            String categorySource = source.idOf("categoryId");
            String categoryId = categorySource == null ? null : categoryIds.get(categorySource);
            if (categorySource != null && categoryId == null) {
                context.issues().report(IssueCode.REFERENCE_DANGLING, sourceId);
            }
            Instant created = Objects.requireNonNullElse(source.instant("createdAt"), context.exportTime());
            cards.add(HomepageCard.builder()
                .id(context.ids().adopt(IdKind.CARD, "", sourceId, ID_LIMIT))
                .title(title)
                .description(fit.text(context.urls().rewriteText(Objects.requireNonNullElse(source.string("description"), ""))))
                .icon(fit.columnIfPresent(HOMEPAGE_CARDS.ICON, source.text("icon")))
                .iconColor(fit.columnIfPresent(HOMEPAGE_CARDS.ICON_COLOR, source.text("iconColor")))
                .actionType(fit.columnIfPresent(HOMEPAGE_CARDS.ACTION_TYPE, source.text("actionType")))
                .actionUrl(context.urls().safeUrl(source.string("actionUrl")))
                .categoryId(categoryId)
                .backgroundColor(fit.columnIfPresent(HOMEPAGE_CARDS.BACKGROUND_COLOR, source.text("backgroundColor")))
                .ordinal(Objects.requireNonNullElse(source.intValue("ordinal"), 0))
                .isEnabled(!Boolean.FALSE.equals(source.boolOrNull("isEnabled")))
                .createdAt(created)
                .updatedAt(Objects.requireNonNullElse(source.instant("updatedAt"), created))
                .build());
            context.issues().imported(ImportSubject.CARDS);
        }
        return cards;
    }

    private String createOrphanCategory() {
        String id = context.ids().derive(IdKind.CATEGORY, "orphans", ORPHAN_CATEGORY_SLUG);
        orphanCategory = KnowledgebaseCategory.builder()
            .id(id)
            .name(ORPHAN_CATEGORY_NAME)
            .slug(uniqueSlug(ORPHAN_CATEGORY_SLUG, ORPHAN_CATEGORY_NAME, id, categorySlugs))
            .ordinal(Integer.MAX_VALUE / 2)
            .isVisible(false)
            .createdAt(context.acceptedAt())
            .updatedAt(context.acceptedAt())
            .build();
        return id;
    }

    private String uniqueSlug(@Nullable String slug, String fallback, String id, Set<String> taken) {
        String base = slug == null || slug.isBlank() ? fallback.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") : slug;
        if (base.isBlank()) {
            base = "item";
        }
        String candidate = base;
        if (!taken.add(candidate.toLowerCase(Locale.ROOT))) {
            String suffix = "-" + Long.toHexString(ImportedIds.hash64(id) & 0xffffffL);
            candidate = fit.cut(base, 255 - suffix.length()) + suffix;
            taken.add(candidate.toLowerCase(Locale.ROOT));
            context.issues().report(IssueCode.SLUG_RENAMED, base);
        }
        return candidate;
    }
}
