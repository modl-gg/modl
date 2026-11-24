package gg.modl.backend.infrastructure.proto;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.Message;
import gg.modl.proto.modl.v1.AppealForm;
import gg.modl.proto.modl.v1.CreateStaffRequest;
import gg.modl.proto.modl.v1.FormField;
import gg.modl.proto.modl.v1.InviteStaffRequest;
import gg.modl.proto.modl.v1.PresignUploadRequest;
import gg.modl.proto.modl.v1.PunishmentTypeRequest;
import gg.modl.proto.modl.v1.ReorderRolesRequest;
import gg.modl.proto.modl.v1.TicketForm;
import gg.modl.proto.modl.v1.UpdateWebhookSettingsRequest;
import java.util.Collections;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RequestBoundsRulesTest {
    private final Validator validator = ValidatorFactory.newBuilder().build();

    @Test
    void requestListsAndTextsThatWereUnboundedAreRefused() throws ValidationException {
        assertInvalid(InviteStaffRequest.newBuilder().setRole("Helper")
            .addAllEmails(IntStream.range(0, 51).mapToObj(index -> index + "@example.com").toList()).build());
        assertInvalid(CreateStaffRequest.newBuilder().setEmail("not an address").setUsername("name").build());
        assertInvalid(UpdateWebhookSettingsRequest.newBuilder().setDiscordAdminRoleId("@everyone").build());
        assertInvalid(TicketForm.newBuilder().addFields(FormField.newBuilder()
            .addAllOptions(Collections.nCopies(101, "option"))).build());
        assertInvalid(PunishmentTypeRequest.newBuilder().setName("n".repeat(65)).setCategory("Gameplay")
            .setAppealForm(AppealForm.newBuilder().build()).build());
        assertInvalid(ReorderRolesRequest.newBuilder()
            .addAllRoleOrder(Collections.nCopies(201, ReorderRolesRequest.RoleOrderItem.newBuilder().setId("r").build())).build());
        assertInvalid(PresignUploadRequest.newBuilder().setUploadType("ticket").setFileName("f".repeat(256))
            .setContentType("image/png").setFileSize(1).build());
    }

    @Test
    void ordinaryRequestsStillPass() throws ValidationException {
        assertThat(validator.validate(InviteStaffRequest.newBuilder().setRole("Helper").addEmails("new@example.com").build())
            .isSuccess()).isTrue();
        assertThat(validator.validate(UpdateWebhookSettingsRequest.newBuilder().setDiscordAdminRoleId("123456789012345678").build())
            .isSuccess()).isTrue();
        assertThat(validator.validate(CreateStaffRequest.newBuilder().setEmail("new@example.com").setUsername("new").build())
            .isSuccess()).isTrue();
    }

    private void assertInvalid(Message message) throws ValidationException {
        assertThat(validator.validate(message).isSuccess()).as(message.getDescriptorForType().getName()).isFalse();
    }
}
