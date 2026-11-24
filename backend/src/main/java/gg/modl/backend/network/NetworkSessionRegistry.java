package gg.modl.backend.network;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NetworkSessionRegistry implements LiveNetworkView {
    private final ConcurrentMap<String, NetworkSession> sessions = new ConcurrentHashMap<>();

    synchronized Registration register(NetworkSession session) {
        List<NetworkSession> replaced = session.isMember() ? liveMembersNamed(session.name()) : List.of();
        if (session.isMember() && liveMemberCount() - replaced.size() >= NetworkLimits.MAX_MEMBERS) {
            return Registration.REFUSED;
        }
        sessions.put(session.connectionId(), session);
        return new Registration(true, replaced);
    }

    synchronized Optional<NetworkSession> remove(String connectionId) {
        return Optional.ofNullable(sessions.remove(connectionId));
    }

    synchronized void whileRegistered(String connectionId, Runnable action) {
        if (sessions.containsKey(connectionId)) {
            action.run();
        }
    }

    Optional<NetworkSession> get(String connectionId) {
        return Optional.ofNullable(sessions.get(connectionId));
    }

    List<NetworkSession> members() {
        return sessions.values().stream().filter(NetworkSession::isOpen).filter(NetworkSession::isMember).toList();
    }

    List<NetworkSession> cores() {
        return sessions.values().stream().filter(NetworkSession::isOpen).filter(NetworkSession::isCore).toList();
    }

    Optional<NetworkSession> memberNamed(String serverName) {
        return members().stream().filter(member -> member.isNamed(serverName)).findFirst();
    }

    List<NetworkSession> usingKey(String keyId) {
        return sessions.values().stream().filter(session -> keyId.equals(session.keyId())).toList();
    }

    @Override
    public List<LiveNetworkSession> sessions() {
        return sessions.values().stream().filter(NetworkSession::isOpen).map(NetworkSession::toLiveView).toList();
    }

    @Override
    public Set<String> connectedKeyIds() {
        return sessions.values().stream()
            .filter(NetworkSession::isOpen)
            .map(NetworkSession::keyId)
            .filter(keyId -> keyId != null)
            .collect(Collectors.toUnmodifiableSet());
    }

    private List<NetworkSession> liveMembersNamed(String serverName) {
        return sessions.values().stream()
            .filter(NetworkSession::isMember)
            .filter(member -> !member.isEnded())
            .filter(member -> member.isNamed(serverName))
            .toList();
    }

    private long liveMemberCount() {
        return sessions.values().stream().filter(NetworkSession::isMember).filter(member -> !member.isEnded()).count();
    }

    record Registration(boolean admitted, List<NetworkSession> replaced) {
        static final Registration REFUSED = new Registration(false, List.of());
    }
}
