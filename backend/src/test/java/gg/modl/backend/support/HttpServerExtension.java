package gg.modl.backend.support;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

final class HttpServerExtension implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        if (!TestServerCredentials.usesExternalServer()) {
            InProcessServer.get();
            return;
        }
        assumeTrue(TestServerCredentials.isPublicApiAvailable(), TestServerCredentials.publicApiUnavailableReason());
        assumeTrue(TestServerCredentials.isPanelApiAvailable(), TestServerCredentials.panelApiUnavailableReason());
    }
}
