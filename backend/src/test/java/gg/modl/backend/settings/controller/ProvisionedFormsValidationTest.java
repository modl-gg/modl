package gg.modl.backend.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.Message;
import gg.modl.backend.homepage.repository.HomepageCardRepository;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseCategoryRepository;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.service.ServerProvisioningService;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.data.TicketFormSettings;
import gg.modl.backend.settings.service.PunishmentTypeEditor;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.proto.modl.v1.PatchTicketFormSettingsRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvisionedFormsValidationTest {
    private final Validator validator = ValidatorFactory.newBuilder().build();

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void theProvisionedTicketFormsCanBeSavedUnchanged() throws ValidationException {
        new ServerProvisioningService(kit.settingsRepository(), mock(KnowledgebaseCategoryRepository.class),
            mock(KnowledgebaseArticleRepository.class), mock(HomepageCardRepository.class), mock(PunishmentTypeEditor.class),
            mock(RoleService.class), kit.unitOfWork(), kit.database().jsonColumns(), kit.clock())
            .provision(kit.server());
        TicketFormSettings forms = SettingsCodec.of(TicketFormSettings.class, TicketFormSettings::new).decodeStrict(kit.stored("ticketForms"));

        assertValid(PatchTicketFormSettingsRequest.newBuilder()
            .setExpectedVersion(kit.version("ticketForms"))
            .setSettings(PanelSettingsProtoMapper.toTicketFormSettings(forms))
            .build());
    }

    @Test
    void theDefaultAppealFormsCanBeSavedUnchanged() throws ValidationException {
        for (PunishmentType type : DefaultPunishmentTypes.getAll()) {
            assertValid(PanelSettingsProtoMapper.toPunishmentType(type));
        }
    }

    private void assertValid(Message message) throws ValidationException {
        assertThat(validator.validate(message).getViolations()).as(message.getDescriptorForType().getName()).isEmpty();
    }
}
