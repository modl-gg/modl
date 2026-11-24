package gg.modl.backend.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.proto.modl.v1.AddAppealReplyRequest;
import gg.modl.proto.modl.v1.AddReplyRequest;
import org.junit.jupiter.api.Test;

class StaffReplyWireTest {
    @Test
    void aStaffReplyCannotNameItsOwnReplyType() {
        assertThat(AddReplyRequest.getDescriptor().findFieldByName("type")).isNull();
        assertThat(AddAppealReplyRequest.getDescriptor().findFieldByName("type")).isNull();
    }

    @Test
    void aStaffReplyCannotNameItsAuthor() {
        assertThat(AddReplyRequest.getDescriptor().findFieldByName("name")).isNull();
    }
}
