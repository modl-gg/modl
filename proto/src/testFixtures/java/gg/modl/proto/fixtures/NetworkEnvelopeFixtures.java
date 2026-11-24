package gg.modl.proto.fixtures;

import com.google.protobuf.InvalidProtocolBufferException;
import gg.modl.proto.modl.v1.RealtimeEnvelope;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public enum NetworkEnvelopeFixtures {
    CORE_MODERATION_STATE("core-to-hub.moderation-state"),
    MEMBER_MODERATION_STATE("hub-to-member.moderation-state"),
    MEMBER_ACTION("member-to-hub.member-action"),
    CORE_MEMBER_ACTION("hub-to-core.member-action"),
    CORE_ACTION_RESULT("core-to-hub.member-action-result"),
    MEMBER_ACTION_RESULT("hub-to-member.member-action-result"),
    CORE_CAPTURE_REQUEST("core-to-hub.replay-capture-request"),
    MEMBER_CAPTURE_REQUEST("hub-to-member.replay-capture-request"),
    MEMBER_CAPTURE_RESULT("member-to-hub.replay-capture-result"),
    CORE_CAPTURE_RESULT("hub-to-core.replay-capture-result"),
    CORE_HOSTED_PLAYERS("core-to-hub.hosted-players"),
    MEMBER_HOSTED_PLAYERS("member-to-hub.hosted-players"),
    CORE_ROSTER("hub-to-core.network-roster"),
    CORE_STAT_WIPES("core-to-hub.stat-wipe-push"),
    MEMBER_STAT_WIPES("hub-to-member.stat-wipe-push");

    public static final String STAFF = "0f8fad5b-d9cb-469f-a165-70867728950e";
    public static final String TARGET = "1f8fad5b-d9cb-469f-a165-70867728950e";
    public static final String CORE_INSTANCE = "proxy-instance";
    public static final String MEMBER_NAME = "lobby";

    private static final String DIRECTORY = "/network/";
    private static final String EXTENSION = ".binpb";

    private final String stem;

    NetworkEnvelopeFixtures(String stem) {
        this.stem = stem;
    }

    public String fileName() {
        return stem + EXTENSION;
    }

    public byte[] bytes() {
        String resource = DIRECTORY + fileName();
        try (InputStream input = NetworkEnvelopeFixtures.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + resource);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return bytes.toByteArray();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + resource, unreadable);
        }
    }

    public RealtimeEnvelope envelope() {
        try {
            return RealtimeEnvelope.parseFrom(bytes());
        } catch (InvalidProtocolBufferException malformed) {
            throw new IllegalStateException("Malformed fixture " + fileName(), malformed);
        }
    }
}
