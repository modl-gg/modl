package gg.modl.backend.serverkey.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyChangedEvent;
import gg.modl.backend.serverkey.ServerKeyErrors;
import gg.modl.backend.serverkey.ServerKeyRevokedEvent;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.repository.ServerKeyRepository;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ServerKeyService {
    public static final int LIMIT = 256;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final ServerKeyRepository repository;
    private final ServerKeySecrets secrets;
    private final UnitOfWork unitOfWork;
    private final ApplicationEventPublisher events;
    private final PanelActionAuditor auditor;
    private final Clock clock;

    public ServerKeyService(ServerKeyRepository repository, ServerKeySecrets secrets, UnitOfWork unitOfWork,
                            ApplicationEventPublisher events, PanelActionAuditor auditor, Clock clock) {
        this.repository = repository;
        this.secrets = secrets;
        this.unitOfWork = unitOfWork;
        this.events = events;
        this.auditor = auditor;
        this.clock = clock;
    }

    public List<ServerKey> list() {
        return unitOfWork.read(repository::findAll);
    }

    public CreatedServerKey create(Server server, String name, ServerKeyScope scope, boolean allowProxyCommands,
                                   String actorEmail) {
        requireName(name);
        ServerKeySecrets.IssuedSecret issued = secrets.issue();
        ServerKey key = new ServerKey(EntityIds.next(), name, scope, scope.isMemberOnly() && allowProxyCommands,
            issued.hash(), issued.hint(), actorEmail, clock.instant(), null);
        unitOfWork.write(() -> {
            if (repository.count() >= LIMIT) {
                throw new ConflictException("A modl instance can have at most " + LIMIT + " server keys", ServerKeyErrors.LIMIT);
            }
            if (repository.existsByName(name) || !repository.insertIfAbsent(key)) {
                throw new ConflictException("A server key named " + name + " already exists", ServerKeyErrors.NAME_TAKEN);
            }
            unitOfWork.afterCommit(() -> {
                events.publishEvent(new ServerKeyChangedEvent(key));
                auditor.recordStaffAction(server, actorEmail, "Created server key " + name + " (" + describe(key) + ")");
            });
        });
        return new CreatedServerKey(key, issued.secret());
    }

    public ServerKey setAllowProxyCommands(Server server, String id, boolean allow, String actorEmail) {
        ServerKey updated = unitOfWork.write(() -> {
            ServerKey current = repository.findById(id).orElseThrow(ServerKeyService::notFound);
            if (allow && !current.scope().isMemberOnly()) {
                throw new ValidationException("Only behind-proxy keys take the /proxycmd setting", ServerKeyErrors.SCOPE);
            }
            if (current.allowProxyCommands() == allow) {
                return current;
            }
            repository.setAllowProxyCommands(id, allow);
            ServerKey changed = current.withAllowProxyCommands(allow);
            unitOfWork.afterCommit(() -> {
                events.publishEvent(new ServerKeyChangedEvent(changed));
                auditor.recordStaffAction(server, actorEmail, (allow ? "Allowed" : "Disallowed")
                    + " /proxycmd for server key " + changed.name());
            });
            return changed;
        });
        return updated;
    }

    public void revoke(Server server, String id, String actorEmail) {
        unitOfWork.write(() -> {
            ServerKey revoked = repository.findById(id).orElseThrow(ServerKeyService::notFound);
            repository.delete(id);
            unitOfWork.afterCommit(() -> {
                events.publishEvent(new ServerKeyRevokedEvent(revoked));
                auditor.recordStaffAction(server, actorEmail, "Revoked server key " + revoked.name());
            });
        });
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ValidationException("A server key name is 1 to 64 letters, digits, dots, dashes, or underscores",
                "INVALID_ARGUMENT");
        }
    }

    private static String describe(ServerKey key) {
        if (!key.scope().isMemberOnly()) {
            return "proxy or standalone";
        }
        return key.allowProxyCommands() ? "behind a proxy, /proxycmd allowed" : "behind a proxy";
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Server key not found", "NOT_FOUND");
    }
}
