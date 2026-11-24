package gg.modl.backend.instance;

import static org.mockito.Mockito.mock;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.instance.repository.JooqInstanceRepository;
import gg.modl.backend.instance.repository.JooqInstanceSecretsRepository;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.settings.repository.JooqSettingsRepository;
import gg.modl.backend.settings.service.PunishmentTypeCatalog;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;

public final class InstanceTestKit implements AutoCloseable {
    public static final String SERVER_NAME = "Server";

    private final SqlTestDatabase database;
    private final JooqInstanceRepository instanceRepository;
    private final JooqInstanceSecretsRepository secretsRepository;
    private final JooqSettingsRepository settingsRepository;
    private final SettingsDocumentService documents;
    private final InstanceContext instanceContext;
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RealtimeEventDispatcher dispatcher = mock(RealtimeEventDispatcher.class);

    private InstanceTestKit(SqlTestDatabase database) {
        this.database = database;
        this.instanceRepository = new JooqInstanceRepository(database.dsl(), database.insertIfAbsent(), database.upserts());
        this.secretsRepository = new JooqInstanceSecretsRepository(database.dsl(), database.insertIfAbsent());
        this.settingsRepository =
            new JooqSettingsRepository(database.dsl(), database.insertIfAbsent(), database.upserts(), database.clock());
        this.documents = new SettingsDocumentService(settingsRepository, database.unitOfWork(), database.jsonColumns());
        this.instanceContext = new InstanceContext(instanceRepository);
    }

    public static InstanceTestKit start(DialectTarget target, Path dataDirectory) {
        return start(target, dataDirectory, WriteAttemptHook.NONE);
    }

    public static InstanceTestKit startWithInjectedRetries(Path dataDirectory) {
        return start(TestDialects.SQLITE, dataDirectory, new RetryInjection());
    }

    public static InstanceTestKit start(DialectTarget target, Path dataDirectory, WriteAttemptHook hook) {
        InstanceTestKit kit = new InstanceTestKit(SqlTestDatabase.start(target, dataDirectory, hook));
        kit.write(() -> {
            kit.instanceRepository.ensure(SERVER_NAME, kit.clock().instant());
            return kit.secretsRepository.ensure("auth-secret", "signing-key", kit.clock().instant());
        });
        kit.instanceContext.refresh();
        return kit;
    }

    public SqlTestDatabase database() {
        return database;
    }

    public MutableClock clock() {
        return database.clock();
    }

    public UnitOfWork unitOfWork() {
        return database.unitOfWork();
    }

    public <T> T write(Supplier<T> unit) {
        return database.unitOfWork().write(unit);
    }

    public JooqInstanceRepository instanceRepository() {
        return instanceRepository;
    }

    public JooqInstanceSecretsRepository secretsRepository() {
        return secretsRepository;
    }

    public JooqSettingsRepository settingsRepository() {
        return settingsRepository;
    }

    public SettingsDocumentService documents() {
        return documents;
    }

    public InstanceContext instanceContext() {
        return instanceContext;
    }

    public Server server() {
        return instanceContext.instance();
    }

    public ApplicationEventPublisher events() {
        return events;
    }

    public RealtimeEventDispatcher dispatcher() {
        return dispatcher;
    }

    public PublicUrls publicUrls(InstanceProperties properties) {
        return new PublicUrls(properties, instanceContext, 8080);
    }

    public InstanceService instanceService() {
        return instanceService(unconfigured());
    }

    public InstanceService instanceService(InstanceProperties properties) {
        return new InstanceService(instanceRepository, unitOfWork(), instanceContext, publicUrls(properties), events, clock());
    }

    public ServerTimestampService timestamps() {
        return new ServerTimestampService(instanceRepository, unitOfWork(), dispatcher, instanceContext, clock());
    }

    public PunishmentTypeCatalog catalog() {
        return new PunishmentTypeCatalog(settingsRepository, unitOfWork(), database.jsonColumns(), clock());
    }

    public void store(String type, Object value) {
        String json = database.jsonColumns().write(value);
        write(() -> {
            settingsRepository.upsert(type, json);
            return json;
        });
    }

    public Map<String, Object> stored(String type) {
        return documents.getRawState(type).data();
    }

    public long version(String type) {
        return documents.getRawState(type).version();
    }

    public static InstanceProperties unconfigured() {
        return new InstanceProperties(null, null, null, null, null, null);
    }

    @Override
    public void close() {
        database.close();
    }
}
