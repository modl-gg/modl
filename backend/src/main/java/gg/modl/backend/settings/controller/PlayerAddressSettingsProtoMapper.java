package gg.modl.backend.settings.controller;

import gg.modl.backend.infrastructure.proto.ProtoMapperSupport;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.probe.IpLookupProbeOutcome;
import gg.modl.backend.iplookup.probe.IpLookupProbeResult;
import gg.modl.backend.iplookup.settings.AddressForwardingNotice;
import gg.modl.backend.iplookup.settings.PlayerAddressSettings;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsView;
import gg.modl.proto.modl.v1.ForwardingNoticeView;
import gg.modl.proto.modl.v1.PlayerAddressLookupTestOutcome;
import gg.modl.proto.modl.v1.PlayerAddressLookupTestResponse;
import gg.modl.proto.modl.v1.PlayerAddressSettingsResponse;
import java.util.Arrays;

final class PlayerAddressSettingsProtoMapper {
    private PlayerAddressSettingsProtoMapper() {
    }

    static PlayerAddressSettingsResponse toResponse(PlayerAddressSettingsView view) {
        PlayerAddressSettingsResponse.Builder response = PlayerAddressSettingsResponse.newBuilder()
            .setLookupsActive(view.lookupsActive())
            .setLookupChoicePending(view.choicePending())
            .setSharedAccountLimit(view.sharedAccountLimit())
            .setMinSharedAccountLimit(PlayerAddressSettings.MIN_SHARED_ACCOUNT_LIMIT)
            .setMaxSharedAccountLimit(PlayerAddressSettings.MAX_SHARED_ACCOUNT_LIMIT)
            .setForwardingNotice(toNotice(view.notice()));
        Arrays.stream(IpLookupProvider.values()).map(IpLookupProvider::id).forEach(response::addProviders);
        Arrays.stream(IpLookupProvider.values()).filter(provider -> !provider.needsKey())
            .map(IpLookupProvider::id).forEach(response::addKeylessProviders);
        view.providersWithKeys().stream().map(IpLookupProvider::id).forEach(response::addProvidersWithKeys);
        if (view.provider() != null) {
            response.setProvider(view.provider().id());
        }
        return response.build();
    }

    static PlayerAddressLookupTestResponse toResponse(IpLookupProbeResult result) {
        PlayerAddressLookupTestResponse.Builder response = PlayerAddressLookupTestResponse.newBuilder()
            .setOutcome(toProto(result.outcome()))
            .setTestAddress(result.testAddress())
            .setLatencyMs(result.latency().toMillis())
            .setKeyChecked(result.keyChecked());
        if (result.upstreamStatus() != null) {
            response.setUpstreamStatus(result.upstreamStatus());
        }
        return response.build();
    }

    private static PlayerAddressLookupTestOutcome toProto(IpLookupProbeOutcome outcome) {
        return switch (outcome) {
            case WORKING -> PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_WORKING;
            case NO_VERDICT -> PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_NO_VERDICT;
            case REFUSED -> PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_REFUSED;
            case DAILY_LIMIT_REACHED -> PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_DAILY_LIMIT_REACHED;
            case UNEXPECTED_ANSWER -> PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_UNEXPECTED_ANSWER;
            case UNREACHABLE -> PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_UNREACHABLE;
            case BUSY -> PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_BUSY;
        };
    }

    private static ForwardingNoticeView toNotice(AddressForwardingNotice notice) {
        ForwardingNoticeView.Builder builder = ForwardingNoticeView.newBuilder()
            .setShowing(notice.showing())
            .setReason(notice.reason() == null ? "" : notice.reason().name());
        if (notice.serverName() != null) {
            builder.setServerName(notice.serverName());
        }
        if (notice.detectedAt() != null) {
            builder.setDetectedAt(ProtoMapperSupport.toTimestamp(notice.detectedAt()));
        }
        return builder.build();
    }
}
