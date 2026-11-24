package gg.modl.backend.ai.model;

public interface AiProtocolClient {
    AiProtocol protocol();

    AiModelListing listModels(AiConnection connection, AiDeadline deadline);

    AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiRequestShape shape, AiDeadline deadline);
}
