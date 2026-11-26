package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.storage.ImportedUrls;
import gg.modl.backend.instance.ServerNames;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.DeletedPunishmentType;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentCategory;
import gg.modl.backend.settings.data.PunishmentPoints;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.data.SupportedLanguages;
import gg.modl.backend.settings.data.TicketFormSettings;
import gg.modl.backend.settings.data.TicketLabelSettings;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.webhook.DiscordWebhookUrls;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

public final class SettingsImport {
    public static final String GENERAL = SettingsType.GENERAL.id();
    public static final String PUNISHMENT_TYPES = SettingsType.PUNISHMENT_TYPES.id();
    public static final String STATUS_THRESHOLDS = SettingsType.STATUS_THRESHOLDS.id();
    public static final String TICKET_FORMS = SettingsType.TICKET_FORMS.id();
    public static final String QUICK_RESPONSES = SettingsType.QUICK_RESPONSES.id();
    public static final String TICKET_LABELS = SettingsType.TICKET_LABELS.id();
    public static final String WEBHOOKS = SettingsType.WEBHOOKS.id();
    public static final String AI_MODERATION = SettingsType.AI_MODERATION.id();
    public static final String REPLAY_RETENTION = SettingsType.REPLAY_RETENTION.id();
    public static final Set<String> HOSTED_ONLY_REFUSED = Set.of("apiKeys", "domain", "aiApiKey");
    public static final Set<String> IMPORTED = idsWhere(true);
    public static final Set<String> REFUSED = Stream.concat(idsWhere(false).stream(), HOSTED_ONLY_REFUSED.stream())
        .collect(Collectors.toUnmodifiableSet());

    private static final int MIN_FIRST_CUSTOM_ORDINAL = PunishmentCategory.MAX_CORE_ORDINAL + 1;
    private static final int MAX_ORDINAL = 1_000_000;
    private static final int MAX_TYPES = 1_000;
    private static final int MAX_URL_LENGTH = 2048;
    private static final String HOMEPAGE_ICON_URL = "homepageIconUrl";
    private static final String PANEL_ICON_URL = "panelIconUrl";
    private static final String DEFAULT_LANGUAGE = "defaultLanguage";
    private static final String TYPE_NAME_PREFIX = "Type ";
    private static final int MAX_SETTING_CHARACTERS = 2 * 1024 * 1024;
    private static final JsonColumns SIZES = new JsonColumns();

    private final ArchiveIndex index;
    private final Validator validator;
    private final ImportedUrls urls;
    private final IssueLog issues;
    private final ImportFit fit;
    private final Set<String> unreadable = new TreeSet<>();

    public SettingsImport(ArchiveIndex index, Validator validator, ImportedUrls urls, ImportFit fit) {
        this.index = index;
        this.validator = validator;
        this.urls = urls;
        this.issues = fit.issues();
        this.fit = fit;
    }

    public static boolean isImported(SettingsType type) {
        return switch (type) {
            case GENERAL, PUNISHMENT_TYPES, STATUS_THRESHOLDS, TICKET_FORMS, QUICK_RESPONSES, TICKET_LABELS, WEBHOOKS,
                 AI_MODERATION, REPLAY_RETENTION -> true;
            case DELETED_PUNISHMENT_TYPES, DATA_RETENTION, SIGN_IN, MAIL, AI_PROVIDER, AI_USAGE, PLAYER_ADDRESSES,
                 ADDRESS_FORWARDING, OPERATOR_LIMITS, STORAGE_BACKEND, PUBLIC_THEME, PUBLIC_CUSTOM_CSS, PUBLIC_HOMEPAGE,
                 HOSTED_IMPORT, HOSTED_IMPORT_CHOICES, HOSTED_IMPORT_ALIASES, REPLAYS -> false;
        };
    }

    private static Set<String> idsWhere(boolean imported) {
        return Arrays.stream(SettingsType.values())
            .filter(type -> isImported(type) == imported)
            .map(SettingsType::id)
            .collect(Collectors.toUnmodifiableSet());
    }

    public SettingsImportPlan plan(List<PunishmentType> currentTypes) {
        Optional<ArchiveDocument> general = data(GENERAL);
        Map<String, Map<String, Object>> documents = new TreeMap<>();
        general.ifPresent(document -> documents.put(GENERAL, general(document)));
        decoded(STATUS_THRESHOLDS, OffenderThresholdSettings.class, OffenderThresholdSettings::defaults)
            .ifPresent(settings -> documents.put(STATUS_THRESHOLDS, SettingsCodec.toDocument(settings)));
        decoded(TICKET_FORMS, TicketFormSettings.class, TicketFormSettings::new)
            .ifPresent(settings -> documents.put(TICKET_FORMS, SettingsCodec.toDocument(settings)));
        decoded(QUICK_RESPONSES, QuickResponseSettings.class, QuickResponseSettings::new)
            .ifPresent(settings -> documents.put(QUICK_RESPONSES, SettingsCodec.toDocument(settings)));
        decoded(TICKET_LABELS, TicketLabelSettings.class, TicketLabelSettings::new)
            .ifPresent(settings -> documents.put(TICKET_LABELS, SettingsCodec.toDocument(settings)));
        webhooks(general).ifPresent(settings -> documents.put(WEBHOOKS, SettingsCodec.toDocument(settings)));
        aiModeration().ifPresent(settings -> documents.put(AI_MODERATION, SettingsCodec.toDocument(settings)));
        ImportedTypes types = types(currentTypes);
        ReplayRetentionSettings replayRetention = decoded(REPLAY_RETENTION, ReplayRetentionSettings.class,
            ReplayRetentionSettings::defaults).orElse(null);
        Set<String> imported = index.settingsByType().keySet().stream()
            .filter(type -> IMPORTED.contains(type) && !unreadable.contains(type))
            .collect(Collectors.toCollection(TreeSet::new));
        return new SettingsImportPlan(serverName(general), types, documents, replayRetention, imported, unreadable);
    }

    private Optional<String> serverName(Optional<ArchiveDocument> general) {
        return general.map(document -> document.string("serverDisplayName"))
            .or(() -> Optional.ofNullable(index.server().serverName()))
            .flatMap(ServerNames::normalize);
    }

    private Map<String, Object> general(ArchiveDocument document) {
        Map<String, Object> general = new LinkedHashMap<>();
        general.put(HOMEPAGE_ICON_URL, Objects.requireNonNullElse(fit.cutIfPresent(urls.safeUrl(document.string(HOMEPAGE_ICON_URL)), MAX_URL_LENGTH), ""));
        general.put(PANEL_ICON_URL, Objects.requireNonNullElse(fit.cutIfPresent(urls.safeUrl(document.string(PANEL_ICON_URL)), MAX_URL_LENGTH), ""));
        String language = document.string(DEFAULT_LANGUAGE);
        general.put(DEFAULT_LANGUAGE, language != null && SupportedLanguages.isSupported(language) ? language : SupportedLanguages.DEFAULT);
        return general;
    }

    private Optional<WebhookSettings> webhooks(Optional<ArchiveDocument> general) {
        Optional<WebhookSettings> decoded = decoded(WEBHOOKS, WebhookSettings.class, WebhookSettings::new);
        String fallbackUrl = general.map(document -> document.string("discordWebhookUrl")).orElse(null);
        if (decoded.isEmpty() && (fallbackUrl == null || fallbackUrl.isBlank())) {
            return Optional.empty();
        }
        WebhookSettings settings = decoded.orElseGet(WebhookSettings::new);
        String url = settings.getDiscordWebhookUrl();
        if ((url == null || url.isBlank()) && fallbackUrl != null) {
            url = fallbackUrl;
        }
        String allowedUrl = url != null && DiscordWebhookUrls.isAllowed(url.trim()) ? url.trim() : "";
        settings.setDiscordWebhookUrl(allowedUrl);
        if (url != null && !url.isBlank() && allowedUrl.isEmpty()) {
            issues.report(IssueCode.URL_UNSAFE, "webhook");
        }
        settings.setAvatarUrl(urls.safeUrl(settings.getAvatarUrl()));
        if (settings.isEnabled()) {
            issues.report(IssueCode.WEBHOOK_DISABLED, "");
        }
        settings.setEnabled(false);
        return Optional.of(settings);
    }

    private Optional<AIModerationSettings> aiModeration() {
        Optional<AIModerationSettings> decoded = decoded(AI_MODERATION, AIModerationSettings.class, AIModerationSettings::new);
        decoded.ifPresent(settings -> {
            if (settings.isEnableAIReview() || settings.isEnableAutomatedActions()) {
                issues.report(IssueCode.AI_DISABLED, "");
            }
            settings.setEnableAIReview(false);
            settings.setEnableAutomatedActions(false);
            Map<String, AIModerationSettings.AIPunishmentConfig> configs = new TreeMap<>();
            Objects.requireNonNullElse(settings.getAiPunishmentConfigs(), Map.<String, AIModerationSettings.AIPunishmentConfig>of())
                .forEach((ordinal, config) -> {
                    if (config != null && isInteger(ordinal) && validator.validate(config).isEmpty()) {
                        configs.put(ordinal, config);
                    } else {
                        issues.report(IssueCode.VALUE_DEFAULTED, "aiPunishmentConfigs." + ordinal);
                    }
                });
            settings.setAiPunishmentConfigs(new LinkedHashMap<>(configs));
        });
        return decoded;
    }

    private ImportedTypes types(List<PunishmentType> currentTypes) {
        Optional<ArchiveDocument> archived = index.setting(PUNISHMENT_TYPES);
        if (archived.isEmpty()) {
            return new ImportedTypes(currentTypes, tombstones(currentTypes), false);
        }
        if (!(archived.get().raw("data") instanceof List<?> sources)) {
            unreadable(PUNISHMENT_TYPES);
            return new ImportedTypes(currentTypes, tombstones(currentTypes), false);
        }
        List<PunishmentType> types = new ArrayList<>();
        Set<Integer> ordinals = new HashSet<>();
        for (Object source : sources) {
            if (types.size() >= MAX_TYPES) {
                issues.report(IssueCode.TYPE_INVALID, "");
                break;
            }
            Optional<PunishmentType> type = type(source);
            if (type.isEmpty() || !ordinals.add(type.get().getOrdinal())) {
                issues.report(IssueCode.TYPE_INVALID, type.map(PunishmentType::getName).orElse(""));
                continue;
            }
            types.add(type.get());
        }
        if (types.isEmpty()) {
            unreadable(PUNISHMENT_TYPES);
            return new ImportedTypes(currentTypes, tombstones(currentTypes), false);
        }
        for (PunishmentType core : DefaultPunishmentTypes.getAll()) {
            if (PunishmentCategory.isCoreOrdinal(core.getOrdinal()) && ordinals.add(core.getOrdinal())) {
                types.add(core);
            }
        }
        int nextId = types.stream().map(PunishmentType::getId).filter(Objects::nonNull).reduce(0, Math::max);
        List<PunishmentType> numbered = new ArrayList<>();
        for (PunishmentType type : types) {
            numbered.add(type.getId() != null ? type : type.toBuilder().id(++nextId).build());
        }
        numbered.sort(Comparator.comparing(PunishmentType::getOrdinal, Comparator.nullsLast(Comparator.naturalOrder())));
        return new ImportedTypes(numbered, tombstones(numbered), true);
    }

    private List<DeletedPunishmentType> tombstones(List<PunishmentType> types) {
        Set<Integer> present = new HashSet<>();
        types.forEach(type -> present.add(type.getOrdinal()));
        List<DeletedPunishmentType> tombstones = new ArrayList<>();
        for (Integer ordinal : new TreeSet<>(index.referencedOrdinals())) {
            if (ordinal >= MIN_FIRST_CUSTOM_ORDINAL && ordinal <= MAX_ORDINAL && !present.contains(ordinal)) {
                tombstones.add(new DeletedPunishmentType(ordinal, null, TYPE_NAME_PREFIX + ordinal, null));
                issues.report(IssueCode.TYPE_TOMBSTONE, Integer.toString(ordinal));
            }
        }
        return tombstones;
    }

    private Optional<PunishmentType> type(Object source) {
        Optional<ArchiveDocument> document = ArchiveDocument.of(source);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> plain = SettingsTrees.withIds(document.get().fields());
        Object typeId = document.get().raw("_id") != null ? document.get().raw("_id") : document.get().raw("id");
        Long numericId = ArchiveDocument.longOf(typeId);
        plain.put("id", numericId == null || numericId > Integer.MAX_VALUE || numericId < 0 ? null : numericId.intValue());
        alias(plain, "isCustomizable", "customizable");
        alias(plain, "isAppealable", "appealable");
        try {
            PunishmentType type = SettingsCodec.of(PunishmentType.class, PunishmentType::new).decodeStrict(plain);
            if (type.getOrdinal() == null || type.getOrdinal() < 0 || type.getOrdinal() > MAX_ORDINAL
                || type.getName() == null || type.getName().isBlank()) {
                return Optional.empty();
            }
            PunishmentType clamped = clampPoints(type.toBuilder().name(fit.cut(type.getName().trim(), 128)).build());
            return validator.validate(clamped).isEmpty() ? Optional.of(clamped) : Optional.empty();
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static PunishmentType clampPoints(PunishmentType type) {
        PunishmentPoints points = type.getPoints();
        return type.toBuilder()
            .points(points == null ? null : new PunishmentPoints(points(points.low()), points(points.regular()), points(points.severe())))
            .singleSeverityPoints(type.getSingleSeverityPoints() == null ? null : points(type.getSingleSeverityPoints()))
            .customPoints(type.getCustomPoints() == null ? null : points(type.getCustomPoints()))
            .build();
    }

    private static int points(int value) {
        return Math.clamp(value, RequestValidationLimits.PUNISHMENT_POINTS_MIN, RequestValidationLimits.PUNISHMENT_POINTS_MAX);
    }

    private static void alias(Map<String, Object> fields, String legacy, String current) {
        if (!fields.containsKey(current) && fields.containsKey(legacy)) {
            fields.put(current, fields.get(legacy));
        }
        fields.remove(legacy);
    }

    private Optional<ArchiveDocument> data(String type) {
        Optional<ArchiveDocument> archived = index.setting(type);
        Optional<ArchiveDocument> data = archived.flatMap(document -> document.document("data"));
        if (archived.isPresent() && data.isEmpty()) {
            unreadable(type);
        }
        return data;
    }

    private void unreadable(String type) {
        if (unreadable.add(type)) {
            issues.report(IssueCode.SETTING_UNREADABLE, type);
        }
    }

    private <T> Optional<T> decoded(String type, Class<T> target, Supplier<T> defaults) {
        Optional<ArchiveDocument> data = data(type);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        try {
            T settings = SettingsCodec.of(target, defaults).decodeStrict(SettingsTrees.withIds(data.get().fields()));
            if (SIZES.write(settings).length() > MAX_SETTING_CHARACTERS) {
                unreadable(type);
                return Optional.empty();
            }
            return Optional.of(settings);
        } catch (JacksonException | IllegalArgumentException e) {
            unreadable(type);
            return Optional.empty();
        }
    }

    private static boolean isInteger(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public record SettingsImportPlan(Optional<String> serverName, ImportedTypes types, Map<String, Map<String, Object>> documents,
                                     @Nullable ReplayRetentionSettings archivedReplayRetention, Set<String> importedTypes,
                                     Set<String> unreadableTypes) {
        public SettingsImportPlan {
            importedTypes = Set.copyOf(importedTypes);
            unreadableTypes = Set.copyOf(unreadableTypes);
        }
    }
}
