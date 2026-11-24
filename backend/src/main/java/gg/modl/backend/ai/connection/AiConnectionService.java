package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.connection.AiProviderSettings.StoredConnection;
import gg.modl.backend.ai.connection.AiSettingsView.ConnectionInfo;
import gg.modl.backend.ai.http.AiBaseUrlPolicy;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnectionState;
import gg.modl.backend.ai.model.AiConnectionState.Managed;
import gg.modl.backend.ai.model.AiConnections;
import gg.modl.backend.ai.model.AiProviderDefinition;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRuntime;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.ai.protocol.AiShapeMemory;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.log.service.PanelActionAuditor;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class AiConnectionService implements AiConnections {
    public static final String PROVIDER_UNKNOWN = "AI_PROVIDER_UNKNOWN";
    public static final String NOT_READY = "AI_NOT_READY";
    public static final String MANAGED_BY_CONFIG = "AI_MANAGED_BY_CONFIG";
    public static final String LIMITS_INVALID = "AI_LIMITS_INVALID";
    private static final String LIMITS_CHANGED = "The AI usage limits were changed by another user. Reload and retry.";

    private final AiSettingsStore store;
    private final AiProviderCatalog catalog;
    private final AiBaseUrlPolicy baseUrlPolicy;
    private final AiConnectionResolver resolver;
    private final AiShapeMemory shapeMemory;
    private final AiRuntime runtime;
    private final UnitOfWork unitOfWork;
    private final PanelActionAuditor auditor;
    private final InstanceContext instance;

    public AiConnectionService(AiEnvironmentProperties environment, AiSettingsStore store, AiProviderCatalog catalog,
                               AiBaseUrlPolicy baseUrlPolicy, AiShapeMemory shapeMemory, AiRuntime runtime, UnitOfWork unitOfWork,
                               PanelActionAuditor auditor, InstanceContext instance) {
        this.store = store;
        this.catalog = catalog;
        this.baseUrlPolicy = baseUrlPolicy;
        this.resolver = new AiConnectionResolver(environment, catalog, baseUrlPolicy);
        this.shapeMemory = shapeMemory;
        this.runtime = runtime;
        this.unitOfWork = unitOfWork;
        this.auditor = auditor;
        this.instance = instance;
    }

    @Override
    public AiConnectionState state() {
        return resolver.resolve(store.current());
    }

    public AiStatusView status() {
        AiConnectionState state = state();
        return new AiStatusView(state.ready().isPresent(), state.readiness(),
            state.provider() == null ? null : state.provider().displayName(),
            state.connection().map(AiConnection::model).orElse(null));
    }

    public AiSettingsView view() {
        AiProviderSettings stored = store.current();
        AiConnectionState state = resolver.resolve(stored);
        String environmentProvider = resolver.environmentProvider();
        List<ConnectionInfo> connections = new ArrayList<>();
        for (AiProviderDefinition provider : catalog.all()) {
            if (stored.connection(provider.id()).isPresent() || provider.id().equals(environmentProvider)) {
                resolver.effective(provider, stored, state.limits()).connection()
                    .ifPresent(connection -> connections.add(info(connection, stored)));
            }
        }
        String active = environmentProvider != null ? environmentProvider
            : catalog.find(stored.activeProvider()).map(AiProviderDefinition::id).orElse(null);
        return new AiSettingsView(catalog.all(), active, connections, state.managed(), state.readiness(), state.limits(),
            stored.limitsVersion());
    }

    public AiSettingsView saveConnection(String actorEmail, String providerId, AiConnectionInput input) {
        AiProviderDefinition provider = provider(providerId);
        String givenBaseUrl = blankToNull(input.baseUrl());
        String givenApiKey = input.apiKey();
        String givenModel = input.model();
        String baseUrl = givenBaseUrl == null ? null : providerUrl(provider, givenBaseUrl).toString();
        String apiKey = givenApiKey == null ? null : validatedKey(givenApiKey.trim());
        String model = givenModel == null ? null : validatedModel(provider, givenModel.trim());
        requireWritable(provider, baseUrl != null, input.apiKey() != null, input.model() != null);
        AiSettingsChange change = mutate(current -> {
            AiProviderSettings saved = withSavedConnection(current, provider, baseUrl, apiKey, model);
            return input.activate() ? activated(saved, provider) : saved;
        });
        change.describeConnection(provider).ifPresent(description -> audit(actorEmail, description));
        return view();
    }

    public AiSettingsView deleteConnection(String actorEmail, String providerId) {
        AiProviderDefinition provider = provider(providerId);
        requireNoConfiguredProvider();
        AiSettingsChange change = mutate(current -> current.withoutConnection(provider.id()));
        if (change.removed(provider.id())) {
            audit(actorEmail, "Removed the " + provider.displayName() + " AI connection");
        }
        return view();
    }

    public AiSettingsView updateSettings(String actorEmail, @Nullable String activeProvider, @Nullable AiLimitsWrite limitsUpdate) {
        if (limitsUpdate != null && !limitsUpdate.limits().withinBounds()) {
            throw new ValidationException("The AI usage limits are out of range", LIMITS_INVALID);
        }
        Optional<AiProviderDefinition> activating = Optional.ofNullable(blankToNull(activeProvider)).map(this::provider);
        if (activeProvider != null) {
            requireNoConfiguredProvider();
        }
        AiSettingsChange change = mutate(current -> {
            AiProviderSettings next = limitsUpdate == null ? current : withLimits(current, limitsUpdate);
            if (activeProvider == null) {
                return next;
            }
            return activating.map(provider -> activated(next, provider)).orElseGet(() -> next.withActiveProvider(null));
        });
        change.describeSettings(activating.orElse(null)).ifPresent(description -> audit(actorEmail, description));
        return view();
    }

    public AiConnection draft(AiDraft draft, boolean requireModel) {
        AiProviderDefinition provider = provider(draft.provider());
        AiProviderSettings stored = store.current();
        Optional<AiConnection> base = resolver.effective(provider, stored, stored.effectiveLimits()).connection();
        String draftBaseUrl = blankToNull(draft.baseUrl());
        String draftApiKey = blankToNull(draft.apiKey());
        String draftModel = blankToNull(draft.model());
        URI baseUrl = draftBaseUrl != null
            ? draftUrl(provider, draftBaseUrl, base)
            : base.map(AiConnection::baseUrl).or(provider::defaultBaseUrl)
                .orElseThrow(() -> new ValidationException("Enter a base URL", AiBaseUrlPolicy.INVALID));
        String apiKey = draftApiKey != null
            ? validatedKey(draftApiKey.trim())
            : base.filter(connection -> connection.baseUrl().equals(baseUrl)).map(AiConnection::apiKey).orElse(null);
        String model = draftModel != null
            ? validatedModel(provider, draftModel.trim())
            : base.map(AiConnection::model).orElse(null);
        if ((provider.requiresKey() && apiKey == null) || (requireModel && model == null)) {
            throw new ValidationException("Add the required key and choose a model first", NOT_READY);
        }
        return new AiConnection(provider, baseUrl, apiKey, model, null);
    }

    public boolean isEffective(AiConnection connection) {
        return state().connection().map(AiConnection::fingerprint).filter(connection.fingerprint()::equals).isPresent();
    }

    @Override
    public void recordShape(AiConnection used, AiRequestShape shape, @Nullable Instant verifiedAt) {
        store.update(current -> {
            Optional<AiConnection> effective = resolver.resolve(current).connection();
            if (effective.isEmpty() || !effective.get().fingerprint().equals(used.fingerprint())
                || (shape.equals(effective.get().shape()) && verifiedAt == null)) {
                return current;
            }
            String providerId = used.provider().id();
            StoredConnection saved = current.connection(providerId).orElse(StoredConnection.EMPTY);
            return current.withConnection(providerId,
                saved.withShape(shape, used.fingerprint(), verifiedAt == null ? null : verifiedAt.toEpochMilli()));
        });
    }

    private AiProviderSettings withSavedConnection(AiProviderSettings current, AiProviderDefinition provider, @Nullable String baseUrl,
                                                   @Nullable String apiKey, @Nullable String model) {
        StoredConnection saved = current.connection(provider.id()).orElse(StoredConnection.EMPTY);
        String url = baseUrl != null ? baseUrl : urlForSave(current, provider, saved, blankToNull(apiKey) != null);
        String key = apiKey == null ? (Objects.equals(saved.baseUrl(), url) ? saved.apiKey() : null) : blankToNull(apiKey);
        String chosenModel = model == null ? saved.model() : blankToNull(model);
        if (Objects.equals(url, saved.baseUrl()) && Objects.equals(key, saved.apiKey()) && Objects.equals(chosenModel, saved.model())) {
            return current.withConnection(provider.id(), saved);
        }
        AiProviderSettings changed = current.withConnection(provider.id(),
            new StoredConnection(url, key, chosenModel, null, null, null));
        return resolver.effective(provider, changed, changed.effectiveLimits()).connection()
            .flatMap(connection -> shapeMemory.recall(connection).map(verified -> changed.withConnection(provider.id(),
                changed.connection(provider.id()).orElseThrow()
                    .withShape(verified.shape(), connection.fingerprint(), verified.verifiedAt().toEpochMilli()))))
            .orElse(changed);
    }

    @Nullable
    private String urlForSave(AiProviderSettings current, AiProviderDefinition provider, StoredConnection saved, boolean newKey) {
        Optional<URI> effective = newKey
            ? resolver.effective(provider, current, current.effectiveLimits()).connection().map(AiConnection::baseUrl)
            : Optional.empty();
        return effective.map(URI::toString)
            .or(() -> Optional.ofNullable(saved.baseUrl()))
            .or(() -> provider.defaultBaseUrl().map(URI::toString))
            .orElse(null);
    }

    private static AiProviderSettings withLimits(AiProviderSettings current, AiLimitsWrite update) {
        if (current.limitsVersion() != update.expectedVersion()) {
            throw new StaleClientVersionException(LIMITS_CHANGED, current.limitsVersion());
        }
        return current.withLimits(update.limits());
    }

    private AiProviderSettings activated(AiProviderSettings settings, AiProviderDefinition provider) {
        AiProviderSettings next = resolver.environmentProvider() == null ? settings.withActiveProvider(provider.id()) : settings;
        AiConnectionState state = resolver.resolve(next);
        if (state.ready().isEmpty() || state.provider() == null || !state.provider().id().equals(provider.id())) {
            throw new ValidationException("Add the required key and choose a model first", NOT_READY);
        }
        return next;
    }

    private AiSettingsChange mutate(UnaryOperator<AiProviderSettings> change) {
        return unitOfWork.write(() -> {
            AiProviderSettings before = store.current();
            AiProviderSettings after = store.update(change);
            unitOfWork.afterCommit(runtime::reset);
            return new AiSettingsChange(before, after);
        });
    }

    private void audit(String actorEmail, String description) {
        auditor.recordStaffAction(instance.instance(), actorEmail, description);
    }

    private void requireWritable(AiProviderDefinition provider, boolean baseUrl, boolean apiKey, boolean model) {
        Managed managed = resolver.managed();
        String environmentProvider = resolver.environmentProvider();
        if (environmentProvider == null) {
            if (managed.any()) {
                throw managedByConfig();
            }
            return;
        }
        if (!environmentProvider.equals(provider.id()) || (baseUrl && managed.baseUrl()) || (apiKey && managed.apiKey())
            || (model && managed.model())) {
            throw managedByConfig();
        }
    }

    private void requireNoConfiguredProvider() {
        if (resolver.managed().any()) {
            throw managedByConfig();
        }
    }

    private AiProviderDefinition provider(String providerId) {
        return catalog.find(providerId)
            .orElseThrow(() -> new ValidationException("This AI provider is not supported", PROVIDER_UNKNOWN));
    }

    private URI draftUrl(AiProviderDefinition provider, String raw, Optional<AiConnection> base) {
        URI url = baseUrlPolicy.require(raw.trim());
        return base.map(AiConnection::baseUrl).filter(url::equals).orElseGet(() -> providerUrl(provider, raw));
    }

    private URI providerUrl(AiProviderDefinition provider, String raw) {
        URI url = baseUrlPolicy.require(raw.trim());
        if (!provider.editableBaseUrl() && !provider.offers(url)) {
            throw new ValidationException("This provider accepts only its listed endpoints", AiBaseUrlPolicy.INVALID);
        }
        return url;
    }

    private static String validatedKey(String key) {
        if (!key.isEmpty() && !AiCredentialRules.validKey(key)) {
            throw new ValidationException("The API key contains characters that are not allowed", AiCredentialRules.KEY_INVALID);
        }
        return key;
    }

    private static String validatedModel(AiProviderDefinition provider, String model) {
        if (!model.isEmpty() && !AiCredentialRules.validModel(provider, model)) {
            throw new ValidationException("The model ID is not valid", AiCredentialRules.MODEL_INVALID);
        }
        return model;
    }

    private static ConnectionInfo info(AiConnection connection, AiProviderSettings stored) {
        AiRequestShape shape = connection.shape();
        Instant verifiedAt = shape == null ? null : stored.connection(connection.provider().id())
            .map(StoredConnection::verifiedAt).map(Instant::ofEpochMilli).orElse(null);
        return new ConnectionInfo(connection.provider().id(), connection.baseUrl().toString(), connection.apiKey() != null,
            connection.model(), shape == null ? null : shape.outputMode(), verifiedAt);
    }

    private static ConflictException managedByConfig() {
        return new ConflictException("This setting is managed by the server configuration", MANAGED_BY_CONFIG);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
