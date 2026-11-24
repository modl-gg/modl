package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.proto.modl.v1.Topic;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RealtimeTopics {
    private static final Set<Topic> SYNC_TOPICS = Collections.unmodifiableSet(EnumSet.of(
        Topic.TOPIC_MINECRAFT_PERMISSIONS,
        Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES,
        Topic.TOPIC_MINECRAFT_STAFF_NOTIFICATIONS,
        Topic.TOPIC_MINECRAFT_PUNISHMENTS,
        Topic.TOPIC_MINECRAFT_PLAYER_NOTIFICATIONS,
        Topic.TOPIC_MINECRAFT_STAFF_2FA,
        Topic.TOPIC_MINECRAFT_STAT_WIPES));
    private static final Set<Topic> CORE_NETWORK_TOPICS = Collections.unmodifiableSet(EnumSet.of(
        Topic.TOPIC_NETWORK_MEMBER_ACTIONS,
        Topic.TOPIC_NETWORK_REPLAY_CAPTURE,
        Topic.TOPIC_NETWORK_ROSTER));
    private static final Set<Topic> MEMBER_NETWORK_TOPICS = Collections.unmodifiableSet(EnumSet.of(
        Topic.TOPIC_NETWORK_MODERATION,
        Topic.TOPIC_NETWORK_REPLAY_CAPTURE));

    private RealtimeTopics() {
    }

    public static Set<Topic> syncTopicsGrantedBy(StartupResponse startup) {
        if (startup == null || !startup.enablesRealtime()) return Collections.emptySet();
        return grantedAmong(startup, SYNC_TOPICS);
    }

    public static Set<Topic> coreNetworkTopicsGrantedBy(StartupResponse startup) {
        return networkTopicsGrantedBy(startup, CORE_NETWORK_TOPICS);
    }

    public static Set<Topic> memberNetworkTopicsGrantedBy(StartupResponse startup) {
        return networkTopicsGrantedBy(startup, MEMBER_NETWORK_TOPICS);
    }

    public static Set<Topic> union(Set<Topic> first, Set<Topic> second) {
        Set<Topic> combined = new LinkedHashSet<>(first);
        combined.addAll(second);
        return Collections.unmodifiableSet(combined);
    }

    private static Set<Topic> networkTopicsGrantedBy(StartupResponse startup, Set<Topic> roleTopics) {
        if (startup == null || !startup.enablesRealtime() || !startup.runsNetworkHub()) return Collections.emptySet();
        return grantedAmong(startup, roleTopics);
    }

    private static Set<Topic> grantedAmong(StartupResponse startup, Set<Topic> wanted) {
        Set<Topic> parsed = new LinkedHashSet<>();
        List<String> topicNames = startup.getRealtimeTopics();
        if (topicNames == null) return Collections.unmodifiableSet(parsed);
        for (String topicName : topicNames) {
            if (topicName == null || topicName.trim().isEmpty()) continue;
            try {
                Topic topic = Topic.valueOf(topicName.trim());
                if (wanted.contains(topic)) parsed.add(topic);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Collections.unmodifiableSet(parsed);
    }
}
