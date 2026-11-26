package gg.modl.backend.instance.setup;

import gg.modl.proto.modl.v1.CheckPublicUrlResponse;
import gg.modl.proto.modl.v1.SetupEnvironment;
import gg.modl.proto.modl.v1.SetupStatusResponse;
import gg.modl.proto.modl.v1.SetupStepState;
import gg.modl.proto.modl.v1.SetupWizardState;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import java.util.Optional;

final class SetupProtoMapper {
    private SetupProtoMapper() {
    }

    static SetupStatusResponse toStatusResponse(SetupStatus status) {
        return SetupStatusResponse.newBuilder()
            .setClaimed(status.claimed())
            .setPhase(toProto(status.phase()))
            .setHost(toProto(status.host()))
            .build();
    }

    static SetupWizardState toWizardState(SetupWizard wizard) {
        SetupWizardState.Builder state = SetupWizardState.newBuilder()
            .setCompleted(wizard.completed())
            .setEnvironment(SetupEnvironment.newBuilder()
                .setHost(toProto(wizard.environment().host()))
                .setReverseProxySuspected(wizard.environment().reverseProxySuspected()));
        wizard.steps().forEach((step, status) -> state.addSteps(SetupStepState.newBuilder()
            .setStep(toProto(step))
            .setStatus(toProto(status))));
        return state.build();
    }

    static CheckPublicUrlResponse toCheckResponse(PublicUrlCheck check) {
        return CheckPublicUrlResponse.newBuilder()
            .setVerdict(switch (check.verdict()) {
                case REACHABLE -> gg.modl.proto.modl.v1.PublicUrlVerdict.PUBLIC_URL_VERDICT_REACHABLE;
                case UNREACHABLE -> gg.modl.proto.modl.v1.PublicUrlVerdict.PUBLIC_URL_VERDICT_UNREACHABLE;
                case OTHER_SERVICE -> gg.modl.proto.modl.v1.PublicUrlVerdict.PUBLIC_URL_VERDICT_OTHER_SERVICE;
                case INVALID -> gg.modl.proto.modl.v1.PublicUrlVerdict.PUBLIC_URL_VERDICT_INVALID;
            })
            .setNormalizedUrl(check.normalizedUrl())
            .build();
    }

    static Optional<SetupStepStatus> fromProto(gg.modl.proto.modl.v1.SetupStepStatus status) {
        return switch (status) {
            case SETUP_STEP_STATUS_DONE -> Optional.of(SetupStepStatus.DONE);
            case SETUP_STEP_STATUS_SKIPPED -> Optional.of(SetupStepStatus.SKIPPED);
            case SETUP_STEP_STATUS_PENDING, SETUP_STEP_STATUS_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
        };
    }

    private static gg.modl.proto.modl.v1.SetupPhase toProto(SetupPhase phase) {
        return switch (phase) {
            case UNCLAIMED -> gg.modl.proto.modl.v1.SetupPhase.SETUP_PHASE_UNCLAIMED;
            case OWNER_SIGN_IN -> gg.modl.proto.modl.v1.SetupPhase.SETUP_PHASE_OWNER_SIGN_IN;
            case READY -> gg.modl.proto.modl.v1.SetupPhase.SETUP_PHASE_READY;
        };
    }

    private static gg.modl.proto.modl.v1.SetupHost toProto(SetupHost host) {
        return switch (host) {
            case PLUGIN -> gg.modl.proto.modl.v1.SetupHost.SETUP_HOST_PLUGIN;
            case STANDALONE -> gg.modl.proto.modl.v1.SetupHost.SETUP_HOST_STANDALONE;
        };
    }

    private static gg.modl.proto.modl.v1.SetupStep toProto(SetupStep step) {
        return switch (step) {
            case ACCOUNT -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_ACCOUNT;
            case IMPORT -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_IMPORT;
            case IDENTITY -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_IDENTITY;
            case ADDRESS -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_ADDRESS;
            case EMAIL -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_EMAIL;
            case SERVERS -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_SERVERS;
            case MINECRAFT_ACCOUNT -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_MINECRAFT_ACCOUNT;
            case STAFF -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_STAFF;
            case PLAYER_ADDRESSES -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_PLAYER_ADDRESSES;
            case AI -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_AI;
            case REPLAYS -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_REPLAYS;
            case CONTENT -> gg.modl.proto.modl.v1.SetupStep.SETUP_STEP_CONTENT;
        };
    }

    private static gg.modl.proto.modl.v1.SetupStepStatus toProto(SetupStepStatus status) {
        return switch (status) {
            case PENDING -> gg.modl.proto.modl.v1.SetupStepStatus.SETUP_STEP_STATUS_PENDING;
            case DONE -> gg.modl.proto.modl.v1.SetupStepStatus.SETUP_STEP_STATUS_DONE;
            case SKIPPED -> gg.modl.proto.modl.v1.SetupStepStatus.SETUP_STEP_STATUS_SKIPPED;
        };
    }
}
