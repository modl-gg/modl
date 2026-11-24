package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.model.RetentionChoice;
import gg.modl.backend.hostedimport.model.RetentionPlan;
import gg.modl.backend.settings.data.DataRetentionSettings;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import org.jspecify.annotations.Nullable;
import java.util.List;

public final class RetentionPlanner {
    private static final int MARGIN_DAYS = 30;
    private static final int MAX_REPLAY_DAYS = 365;
    private static final double STORED_ROW_FACTOR = 2.5;

    private RetentionPlanner() {
    }

    public static List<RetentionPlan> plans(ArchiveIndex.RetentionFacts facts, DataRetentionSettings current,
                                            ReplayRetentionSettings currentReplays, @Nullable ReplayRetentionSettings archivedReplays) {
        return List.of(keepHistory(facts, current, archivedReplays), standard(facts, current, currentReplays, archivedReplays));
    }

    public static RetentionPlan plan(RetentionChoice choice, ArchiveIndex.RetentionFacts facts, DataRetentionSettings current,
                                     ReplayRetentionSettings currentReplays, @Nullable ReplayRetentionSettings archivedReplays) {
        return choice == RetentionChoice.KEEP_HISTORY
            ? keepHistory(facts, current, archivedReplays)
            : standard(facts, current, currentReplays, archivedReplays);
    }

    private static RetentionPlan keepHistory(ArchiveIndex.RetentionFacts facts, DataRetentionSettings current,
                                             @Nullable ReplayRetentionSettings archivedReplays) {
        int oldestPlayerLog = Math.max(facts.chatLogs().oldestAgeDays(), facts.commandLogs().oldestAgeDays());
        int chatDays = Math.max(current.getChatLogDays(), DataRetentionSettings.CHAT_LOG_PERIOD.clamp(Math.max(MARGIN_DAYS, oldestPlayerLog + MARGIN_DAYS)));
        int auditDays = Math.max(current.getAuditLogDays(), DataRetentionSettings.AUDIT_LOG_PERIOD.clamp(
            Math.max(365, facts.auditLogs().oldestAgeDays() + MARGIN_DAYS)));
        int oldestReplay = facts.replays().oldestAgeDays();
        boolean replaysEnabled;
        int replayDays;
        if (archivedReplays != null && (!archivedReplays.isEnabled() || archivedReplays.getDays() >= oldestReplay)) {
            replaysEnabled = archivedReplays.isEnabled();
            replayDays = Math.clamp(archivedReplays.getDays(), 1, MAX_REPLAY_DAYS);
        } else if (oldestReplay + MARGIN_DAYS <= MAX_REPLAY_DAYS) {
            replaysEnabled = true;
            replayDays = oldestReplay + MARGIN_DAYS;
        } else {
            replaysEnabled = false;
            replayDays = MAX_REPLAY_DAYS;
        }
        return plan(RetentionChoice.KEEP_HISTORY, facts, chatDays, auditDays, replaysEnabled, replayDays, true);
    }

    private static RetentionPlan standard(ArchiveIndex.RetentionFacts facts, DataRetentionSettings current,
                                          ReplayRetentionSettings currentReplays, @Nullable ReplayRetentionSettings archivedReplays) {
        ReplayRetentionSettings replays = archivedReplays != null ? archivedReplays : currentReplays;
        return plan(RetentionChoice.STANDARD, facts, current.getChatLogDays(), current.getAuditLogDays(), replays.isEnabled(),
            Math.clamp(replays.getDays(), 1, MAX_REPLAY_DAYS), false);
    }

    private static RetentionPlan plan(RetentionChoice choice, ArchiveIndex.RetentionFacts facts, int chatDays, int auditDays,
                                      boolean replaysEnabled, int replayDays, boolean keepsIpHistory) {
        long chatGone = facts.chatLogs().olderThan(chatDays);
        long commandGone = facts.commandLogs().olderThan(chatDays);
        long auditGone = facts.auditLogs().olderThan(auditDays);
        long replaysGone = replaysEnabled ? facts.replays().olderThan(replayDays) : 0;
        long ipGone = keepsIpHistory ? 0 : facts.ipEntriesBeyondCutoff();
        long estimated = (long) (STORED_ROW_FACTOR * (kept(facts.chatBytes(), facts.chatLogs(), chatGone)
            + kept(facts.commandBytes(), facts.commandLogs(), commandGone)
            + kept(facts.auditBytes(), facts.auditLogs(), auditGone)
            + kept(facts.replayBytes(), facts.replays(), replaysGone)));
        return new RetentionPlan(choice, chatDays, auditDays, replaysEnabled, replayDays, keepsIpHistory,
            chatGone + commandGone + auditGone + ipGone, replaysGone, estimated, estimated);
    }

    private static double kept(long bytes, AgeHistogram rows, long gone) {
        return rows.total() == 0 ? 0 : (double) bytes * (rows.total() - gone) / rows.total();
    }
}
