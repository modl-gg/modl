package gg.modl.backend.settings.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.BeanValidationRunner;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentCategory;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeEditor;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.proto.modl.v1.PanelPunishmentTypesResponse;
import gg.modl.proto.modl.v1.PanelResource;
import gg.modl.proto.modl.v1.PunishmentTypeRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS + "/punishment-types")
@RequiresPanelPermission(rule = PanelAccessRule.PUNISHMENT_TYPE_ACCESS)
@RequiredArgsConstructor
public class PanelPunishmentTypeController {
    private final PunishmentTypeService punishmentTypeService;
    private final PunishmentTypeEditor punishmentTypeEditor;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final BeanValidationRunner validationRunner;

    @GetMapping
    public PanelPunishmentTypesResponse getPunishmentTypes(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        return PanelSettingsProtoMapper.toPunishmentTypesResponse(types);
    }

    @GetMapping("/{ordinal}")
    public ResponseEntity<gg.modl.proto.modl.v1.PunishmentType> getPunishmentType(
        @PathVariable int ordinal,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return punishmentTypeService.getPunishmentTypeByOrdinal(server, ordinal)
            .map(PanelSettingsProtoMapper::toPunishmentType)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{ordinal}")
    public gg.modl.proto.modl.v1.PunishmentType updatePunishmentType(
        @PathVariable int ordinal,
        @RequestBody PunishmentTypeRequest requestBody,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentType updatedType = PanelSettingsProtoMapper.fromPunishmentTypeRequest(requestBody);
        validationRunner.validate(updatedType);

        PunishmentType result = punishmentTypeEditor.updatePunishmentType(server, ordinal, updatedType);
        invalidatePunishmentTypes(server, ordinal);
        return PanelSettingsProtoMapper.toPunishmentType(result);
    }

    @PostMapping
    public gg.modl.proto.modl.v1.PunishmentType createPunishmentType(
        @RequestBody PunishmentTypeRequest requestBody,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentType newType = PanelSettingsProtoMapper.fromPunishmentTypeRequest(requestBody);
        validationRunner.validate(newType);
        PunishmentType created = punishmentTypeEditor.createPunishmentType(server, newType);
        invalidatePunishmentTypes(server, created.getOrdinal());
        return PanelSettingsProtoMapper.toPunishmentType(created);
    }

    @PostMapping("/reset")
    public PanelPunishmentTypesResponse resetPunishmentTypes(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<PunishmentType> types = punishmentTypeEditor.resetToDefaults(server);
        invalidatePunishmentTypes(server, null);
        return PanelSettingsProtoMapper.toPunishmentTypesResponse(types);
    }

    @DeleteMapping("/{ordinal}")
    public ResponseEntity<?> deletePunishmentType(
        @PathVariable int ordinal,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        if (ordinal <= PunishmentCategory.MAX_CORE_ORDINAL) {
            throw new ValidationException("Cannot delete core administrative punishment types");
        }

        boolean deleted = punishmentTypeEditor.deletePunishmentType(server, ordinal);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        invalidatePunishmentTypes(server, ordinal);
        return ResponseEntity.ok(Map.of("message", "Punishment type deleted successfully"));
    }

    private void invalidatePunishmentTypes(Server server, @Nullable Integer ordinal) {
        realtimeEventPublisher.invalidatePanel(
            server,
            PanelResource.PANEL_RESOURCE_PUNISHMENT_TYPES,
            ordinal != null ? String.valueOf(ordinal) : null
        );
    }

}
