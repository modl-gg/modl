package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.repository.HomepageCardPatch;
import gg.modl.backend.homepage.repository.HomepageCardRepository;
import gg.modl.backend.hostedimport.mapping.ContentMapper;
import gg.modl.backend.hostedimport.mapping.ImportedTypes;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseCategoryRepository;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.settings.repository.SettingsRepository;
import gg.modl.backend.settings.service.PunishmentTypeCatalog;
import gg.modl.backend.settings.service.SettingsCodec;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfigurationWriter {
    private final UnitOfWork unitOfWork;
    private final PunishmentTypeCatalog typeCatalog;
    private final StaffRoleRepository roleRepository;
    private final PermissionService permissionService;
    private final ServerTimestampService timestamps;
    private final SettingsRepository settingsRepository;
    private final InstanceService instanceService;
    private final InstanceProperties instanceProperties;
    private final KnowledgebaseCategoryRepository categoryRepository;
    private final KnowledgebaseArticleRepository articleRepository;
    private final HomepageCardRepository cardRepository;
    private final JsonColumns jsonColumns;

    void write(Server server, ImportPlan plan, ContentMapper content, List<HomepageCard> cards, Instant now) {
        ImportedTypes types = plan.settings().types();
        if (types.fromArchive() || !types.tombstones().isEmpty()) {
            typeCatalog.replaceImported(types.types(), types.tombstones());
            timestamps.updatePunishmentTypesTimestamp(server);
        }
        ImportPlanFactory.REPLACED_DEFAULT_ROLES.forEach(roleRepository::deleteById);
        plan.roles().roles().forEach(roleRepository::insertIfAbsent);
        timestamps.updateStaffPermissionsTimestamp(server);
        unitOfWork.afterCommit(permissionService::evictPermissionCache);
        plan.settings().documents().forEach((type, document) -> settingsRepository.upsert(type, toJson(document)));
        if (instanceProperties.configuredServerName().isEmpty()) {
            plan.settings().serverName().ifPresent(instanceService::changeServerName);
        }
        writeContent(content, cards, now, plan);
    }

    private void writeContent(ContentMapper content, List<HomepageCard> cards, Instant now, ImportPlan plan) {
        List<KnowledgebaseCategory> categories = content.categories();
        boolean replacesKnowledgebase = !categories.isEmpty() || plan.context().index().articleCount() > 0;
        Map<String, String> slugByCard = new HashMap<>();
        if (replacesKnowledgebase) {
            Map<String, String> slugByCategory = new HashMap<>();
            List<KnowledgebaseCategory> seeded = categoryRepository.findAllOrdered();
            seeded.forEach(category -> slugByCategory.put(category.getId(), category.getSlug()));
            cardRepository.findAllOrdered().forEach(card -> {
                String slug = card.getCategoryId() == null ? null : slugByCategory.get(card.getCategoryId());
                if (slug != null) {
                    slugByCard.put(card.getId(), slug);
                }
            });
            seeded.forEach(category -> categoryRepository.deleteById(category.getId()));
            categories.forEach(categoryRepository::insert);
        }
        if (!cards.isEmpty()) {
            cardRepository.findAllOrdered().forEach(card -> cardRepository.deleteById(card.getId()));
            cards.forEach(cardRepository::insert);
            return;
        }
        Map<String, String> categoryBySlug = new HashMap<>();
        categories.forEach(category -> categoryBySlug.put(category.getSlug(), category.getId()));
        slugByCard.forEach((cardId, slug) -> {
            String categoryId = categoryBySlug.get(slug);
            if (categoryId != null) {
                cardRepository.update(cardId, HomepageCardPatch.builder().categoryId(categoryId).updatedAt(now).build());
            }
        });
    }

    void writeArticles(List<KnowledgebaseArticle> articles, ContentMapper content) {
        content.orphanCategory().ifPresent(orphans -> {
            if (categoryRepository.findById(orphans.getId()).isEmpty()) {
                categoryRepository.insert(orphans);
            }
        });
        articles.forEach(articleRepository::insert);
    }

    private String toJson(Map<String, Object> document) {
        return jsonColumns.write(SettingsCodec.toDocument(document));
    }
}
