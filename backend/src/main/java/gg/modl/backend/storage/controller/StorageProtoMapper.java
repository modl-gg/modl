package gg.modl.backend.storage.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.longValue;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.stringValue;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.storage.dto.response.PresignUploadResponse;
import gg.modl.backend.storage.dto.response.UploadResponse;
import gg.modl.proto.modl.v1.MediaConfigResponse;
import gg.modl.proto.modl.v1.MediaSupportedTypes;
import java.util.List;
import java.util.Map;

final class StorageProtoMapper {

    private StorageProtoMapper() {
    }

    static MediaConfigResponse toMediaConfigResponse(Map<String, Object> supportedTypes,
                                                     Map<String, Object> fileSizeLimits,
                                                     String publicBaseUrl) {
        MediaConfigResponse.Builder builder = MediaConfigResponse.newBuilder()
            .setStorageConfigured(true)
            .setPublicBaseUrl(stringValue(publicBaseUrl));
        supportedTypes.forEach((key, value) -> builder.putSupportedTypes(key, toMediaSupportedTypes(value)));
        fileSizeLimits.forEach((key, value) -> builder.putFileSizeLimits(key, longValue(value)));
        return builder.build();
    }

    static gg.modl.proto.modl.v1.PresignUploadResponse toPresignUploadResponse(PresignUploadResponse model) {
        gg.modl.proto.modl.v1.PresignUploadResponse.Builder builder = gg.modl.proto.modl.v1.PresignUploadResponse.newBuilder()
            .setPresignedUrl(stringValue(model.presignedUrl()))
            .setKey(stringValue(model.key()))
            .setMethod(stringValue(model.method()));
        if (model.expiresAt() != null) {
            builder.setExpiresAt(toTimestamp(model.expiresAt()));
        }
        if (model.requiredHeaders() != null) {
            builder.putAllRequiredHeaders(model.requiredHeaders());
        }
        return builder.build();
    }

    static gg.modl.proto.modl.v1.UploadResponse toUploadResponse(UploadResponse model) {
        return gg.modl.proto.modl.v1.UploadResponse.newBuilder()
            .setKey(stringValue(model.key()))
            .setUrl(stringValue(model.url()))
            .setFileName(stringValue(model.fileName()))
            .setSize(model.size())
            .setContentType(stringValue(model.contentType()))
            .build();
    }

    private static MediaSupportedTypes toMediaSupportedTypes(Object value) {
        MediaSupportedTypes.Builder builder = MediaSupportedTypes.newBuilder();
        if (value instanceof List<?> types) {
            types.forEach(type -> builder.addTypes(stringValue(type)));
        }
        return builder.build();
    }
}
