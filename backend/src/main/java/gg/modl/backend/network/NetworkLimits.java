package gg.modl.backend.network;

import java.time.Duration;

public final class NetworkLimits {
    public static final int MAX_MEMBERS = 256;
    public static final int MEMBER_ACTIONS_PER_SECOND = 20;
    public static final int PROXY_COMMANDS_PER_SECOND = 1;
    public static final int FORWARDED_ACTIONS_PER_CORE_PER_SECOND = 200;
    public static final int CAPTURE_REQUESTS_PER_SECOND = 5;
    public static final int HOSTED_PLAYER_EVENTS_PER_SECOND = 10;
    public static final int MODERATION_STATES_PER_SECOND = 20;
    public static final int PENDING_ACTIONS_PER_MEMBER = 64;
    public static final Duration ACTION_RESULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int PENDING_CAPTURES_PER_CORE = 64;
    public static final Duration CAPTURE_DEADLINE = Duration.ofMinutes(10);
    public static final Duration RETIRE_AFTER = Duration.ofMinutes(2);
    public static final int HOSTED_PLAYERS_PER_CONNECTION = 5000;

    private NetworkLimits() {
    }
}
