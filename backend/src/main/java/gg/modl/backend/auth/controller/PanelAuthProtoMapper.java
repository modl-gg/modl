package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.staff.data.Staff;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import gg.modl.proto.modl.v1.PanelPermissionsResponse;
import gg.modl.proto.modl.v1.PanelProfileResponse;
import gg.modl.proto.modl.v1.PanelSessionInfoResponse;
import gg.modl.proto.modl.v1.PanelSessionsResponse;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.addressValue;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.setOptionalString;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.stringValue;

public final class PanelAuthProtoMapper {
    private static final DateTimeFormatter SESSION_TIME_FORMAT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);


    private PanelAuthProtoMapper() {
    }

    public static PanelAuthResponse toAuthResponse(boolean success, String message) {
        return PanelAuthResponse.newBuilder()
            .setSuccess(success)
            .setMessage(stringValue(message))
            .build();
    }

    public static PanelAuthResponse toIssuedSessionResponse(String message, IssuedSession issued) {
        return PanelAuthResponse.newBuilder()
            .setSuccess(true)
            .setMessage(stringValue(message))
            .setSessionKey(issued.sessionKey())
            .build();
    }

    static PanelProfileResponse toProfileResponse(Staff staff, String role) {
        String minecraftUsername = staff.getAssignedMinecraftUsername() != null
            ? staff.getAssignedMinecraftUsername()
            : staff.getUsername();
        PanelProfileResponse.Builder builder = PanelProfileResponse.newBuilder()
            .setEmail(stringValue(staff.getEmail()))
            .setUsername(stringValue(staff.getUsername()))
            .setRole(stringValue(role))
            .setMinecraftUsername(stringValue(minecraftUsername))
            .setMinecraftUuid(stringValue(staff.getAssignedMinecraftUuid()))
            .setLanguage(stringValue(staff.getLanguage()))
            .setDateFormat(stringValue(staff.getDateFormat()));
        setOptionalString(builder::setId, staff.getId());
        return builder.build();
    }

    static PanelProfileResponse toSuperAdminProfileResponse(String email, String username, String language, String dateFormat) {
        return PanelProfileResponse.newBuilder()
            .setEmail(stringValue(email))
            .setUsername(stringValue(username))
            .setRole(RoleAuthorization.SUPER_ADMIN_ROLE_NAME)
            .setMinecraftUsername(stringValue(username))
            .setLanguage(stringValue(language))
            .setDateFormat(stringValue(dateFormat))
            .build();
    }

    static PanelPermissionsResponse toPermissionsResponse(List<String> permissions) {
        return PanelPermissionsResponse.newBuilder()
            .addAllPermissions(permissions)
            .build();
    }

    static PanelSessionsResponse toSessionsResponse(List<PanelSession> sessions, String currentPublicId) {
        PanelSessionsResponse.Builder builder = PanelSessionsResponse.newBuilder();
        for (PanelSession session : sessions) {
            builder.addSessions(toSessionInfo(session, currentPublicId));
        }
        return builder.build();
    }

    private static PanelSessionInfoResponse toSessionInfo(PanelSession session, String currentPublicId) {
        PanelSessionInfoResponse.Builder builder = PanelSessionInfoResponse.newBuilder()
            .setId(stringValue(session.publicId()))
            .setIpAddress(addressValue(session.ipAddress()))
            .setUserAgent(stringValue(session.userAgent()))
            .setIsCurrent(session.publicId().equals(currentPublicId));
        setOptionalString(builder::setCreatedAt, formatIso(session.createdAt()));
        setOptionalString(builder::setExpiresAt, formatIso(session.expiresAt()));
        return builder.build();
    }

    private static @Nullable String formatIso(@Nullable Instant instant) {
        return instant != null ? SESSION_TIME_FORMAT.format(instant) : null;
    }
}
