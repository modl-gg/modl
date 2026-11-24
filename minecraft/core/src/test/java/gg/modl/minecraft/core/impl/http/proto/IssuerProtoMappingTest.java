package gg.modl.minecraft.core.impl.http.proto;

import com.google.gson.Gson;
import gg.modl.minecraft.api.PlayerNoteKind;
import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.api.http.request.CreatePlayerNoteRequest;
import gg.modl.minecraft.api.http.request.PardonPunishmentRequest;
import gg.modl.minecraft.api.http.request.PunishmentCreateRequest;
import gg.modl.proto.modl.v1.CreateEvidenceUploadTokenRequest;
import gg.modl.proto.modl.v1.DismissReportRequest;
import gg.modl.proto.modl.v1.ResolveReportRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssuerProtoMappingTest {
    @Test
    void aConsoleActionCarriesTheExplicitMarkerAndNoStaffId() {
        gg.modl.proto.modl.v1.PardonPunishmentRequest pardon = PunishmentProtoMapper.toProto(
                new PardonPunishmentRequest("PUNISH01", "Console", ActionIssuer.console(), null, null));

        assertTrue(pardon.getConsole());
        assertFalse(pardon.hasIssuerId());
    }

    @Test
    void aStaffActionCarriesItsStaffIdAndNoConsoleMarker() {
        DismissReportRequest dismissal = TicketProtoMapper.toDismissReportRequest("Mod", null, ActionIssuer.staff("staff-3"));
        CreateEvidenceUploadTokenRequest token = PunishmentProtoMapper.toUploadTokenRequest("Mod", ActionIssuer.staff("staff-3"));

        assertEquals("staff-3", dismissal.getIssuerId());
        assertFalse(dismissal.getConsole());
        assertEquals("staff-3", token.getIssuerId());
        assertFalse(token.getConsole());
    }

    @Test
    void notesAndWarningsNameTheirKindWithoutAPrefixInTheText() {
        String target = "123e4567-e89b-12d3-a456-426614174000";
        CreatePlayerNoteRequest warning =
                new CreatePlayerNoteRequest(target, "Mod", ActionIssuer.staff("staff-3"), PlayerNoteKind.WARNING, "Spam");
        CreatePlayerNoteRequest note =
                new CreatePlayerNoteRequest(target, "Mod", ActionIssuer.staff("staff-3"), PlayerNoteKind.NOTE, "Spam");

        assertEquals(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_WARNING, PlayerProtoMapper.toProto(warning).getKind());
        assertEquals(gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_NOTE, PlayerProtoMapper.toProto(note).getKind());
        assertEquals("Spam", PlayerProtoMapper.toProto(warning).getText());
        assertEquals("WARNING", new Gson().toJsonTree(warning).getAsJsonObject().get("kind").getAsString());
        assertEquals("NOTE", new Gson().toJsonTree(note).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void creationNotesAndResolutionNameTheirIssuer() {
        gg.modl.proto.modl.v1.CreatePunishmentRequest consoleBan = PunishmentProtoMapper.toProto(new PunishmentCreateRequest(
                "123e4567-e89b-12d3-a456-426614174000", "Console", ActionIssuer.console(), "Reason", null, null, 2,
                null, null, null, null));
        gg.modl.proto.modl.v1.CreatePlayerNoteRequest staffNote = PlayerProtoMapper.toProto(new CreatePlayerNoteRequest(
                "123e4567-e89b-12d3-a456-426614174000", "Mod", ActionIssuer.staff("staff-3"), PlayerNoteKind.NOTE, "Note"));
        ResolveReportRequest resolution = TicketProtoMapper.toResolveReportRequest(
                "Mod", "Accepted", "PUNISH01", ActionIssuer.staff("staff-3"));

        assertTrue(consoleBan.getConsole());
        assertFalse(consoleBan.hasIssuerId());
        assertEquals("staff-3", staffNote.getIssuerId());
        assertFalse(staffNote.getConsole());
        assertEquals("staff-3", resolution.getIssuerId());
        assertFalse(resolution.getConsole());
    }
}
