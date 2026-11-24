package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.PublicUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportNotices {
    private static final String IMPORT_PAGE = "/import";

    private final OperatorNotice notice;
    private final PublicUrls publicUrls;

    public void started() {
        notice.publish("Importing data from modl.gg. Bans and mutes take effect as they are imported.");
    }

    public void resumedAfterRestart() {
        notice.publish("Resuming the import from modl.gg after a restart.");
    }

    void paused(ImportReason reason) {
        notice.publish("The import from modl.gg paused: {}. Open {} to resume it.", reason.pauseSentence().orElseThrow(), page());
    }

    void finished(ImportTally tally) {
        notice.publish("The import from modl.gg finished: {} players, {} punishments, {} tickets and {} files. See the report at {}.",
            tally.count(ImportSubject.PLAYERS).imported(),
            tally.count(ImportSubject.PUNISHMENTS).imported(),
            tally.count(ImportSubject.TICKETS).imported(),
            tally.count(ImportSubject.FILES).imported(),
            page());
    }

    private String page() {
        return (publicUrls.knownOrigin().isPresent() ? publicUrls.setupPage() : publicUrls.hostNeutralSetupPage()) + IMPORT_PAGE;
    }
}
