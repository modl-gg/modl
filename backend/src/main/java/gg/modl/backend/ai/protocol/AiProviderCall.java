package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.http.AiHttpRequest;
import gg.modl.backend.ai.http.AiHttpResponse;
import gg.modl.backend.ai.http.AiHttpTransport;
import gg.modl.backend.ai.http.AiTransportException;
import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import java.net.URI;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
class AiProviderCall {
    static final int MAX_MODEL_PAGES = 10;
    static final int MAX_MODELS = 2000;
    private static final Set<Integer> LIST_UNSUPPORTED_STATUSES = Set.of(404, 405, 501);

    private final AiHttpTransport transport;

    JsonNode completion(AiHttpRequest request, AiDeadline deadline) throws AiCallFailedException {
        AiHttpResponse response = send(request, deadline);
        if (!response.successful()) {
            throw new AiCallFailedException(AiErrorClassifier.classify(response));
        }
        return body(response);
    }

    JsonNode listing(AiHttpRequest request, AiDeadline deadline) throws AiCallFailedException {
        AiHttpResponse response = send(request, deadline);
        if (LIST_UNSUPPORTED_STATUSES.contains(response.status())) {
            throw new AiCallFailedException(AiFailureKind.LIST_UNSUPPORTED);
        }
        if (!response.successful()) {
            AiCallFailure failure = AiErrorClassifier.classify(response);
            throw new AiCallFailedException(failure.kind() == AiFailureKind.MODEL_NOT_FOUND
                ? AiCallFailure.of(AiFailureKind.LIST_UNSUPPORTED) : failure);
        }
        return body(response);
    }

    static URI resolve(URI baseUrl, String path) {
        return URI.create(baseUrl.toString() + path);
    }

    private AiHttpResponse send(AiHttpRequest request, AiDeadline deadline) throws AiCallFailedException {
        AiHttpResponse response;
        try {
            response = transport.exchange(request, deadline);
        } catch (AiTransportException failure) {
            throw new AiCallFailedException(AiErrorClassifier.transport(failure.kind()));
        }
        if (response.redirect()) {
            throw new AiCallFailedException(AiCallFailure.of(AiFailureKind.REDIRECT).withStatus(response.status()));
        }
        return response;
    }

    private static JsonNode body(AiHttpResponse response) throws AiCallFailedException {
        return AiJson.parse(response.body())
            .filter(JsonNode::isObject)
            .orElseThrow(() -> new AiCallFailedException(AiFailureKind.INVALID_RESPONSE));
    }
}
