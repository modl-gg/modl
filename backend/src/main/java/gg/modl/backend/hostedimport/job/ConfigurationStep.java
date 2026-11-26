package gg.modl.backend.hostedimport.job;

import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.hostedimport.mapping.ArchiveCollection;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.ContentMapper;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.role.service.RoleService;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfigurationStep implements PhaseStep {
    private static final long CONFIGURED = 1;

    private final ConfigurationWriter writer;
    private final RoleService roleService;
    private final Clock clock;

    @Override
    public ImportPhase phase() {
        return ImportPhase.CONFIGURATION;
    }

    @Override
    public long total(ArchiveIndex index) {
        return CONFIGURED + index.documents(ArchiveCollection.ARTICLES);
    }

    @Override
    public void run(PhaseRun run) {
        ContentMapper content = new ContentMapper(run.context());
        if (run.cursor() < CONFIGURED) {
            List<HomepageCard> cards = content.cards();
            run.plan().countConfiguration(run.issues());
            run.commitState(CONFIGURED, () -> {
                writer.write(run.server(), run.plan(), content, cards, clock.instant());
                roleService.createDefaultRoles(run.server());
            });
        } else {
            run.issues().clear();
        }
        CollectionImport.<KnowledgebaseArticle>of(ArchiveCollection.ARTICLES, ImportSubject.ARTICLES)
            .positionOffset(CONFIGURED)
            .remapsCommittedLines(true)
            .mapper((document, line) -> content.article(document))
            .write(articles -> writer.writeArticles(articles, content))
            .build()
            .run(run);
    }
}
