package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.core.util.JavaRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SignedVelocitySupportTest {
    private static final int JAVA_17 = 17;
    private static final int JAVA_25 = 25;

    private final int shippedRequirement = JavaRuntime.featureVersionRequiredBy(
            SignedVelocitySupportTest.class.getClassLoader(), SignedVelocityBootstrap.INTEGRATION_CLASS);
    private final SignedVelocitySupport shipped = new SignedVelocitySupport(shippedRequirement);

    @Test
    void theShippedIntegrationRunsOnEveryPaperFrom1191OnJava17AndNewer() {
        assertTrue(shipped.isSupportedBy(JAVA_17, className -> true));
        assertTrue(shipped.isSupportedBy(JAVA_25, className -> true));
    }

    @Test
    void anOlderJavaThanTheIntegrationNeedsIsRejectedWithoutLookingAtTheServer() {
        int olderJava = shippedRequirement - 1;

        assertFalse(shipped.isSupportedBy(olderJava,
                className -> fail("looked up " + className + " although Java " + olderJava + " cannot load the integration")));
    }

    @Test
    void everyRequiredServerClassMustBePresent() {
        SignedVelocitySupport.REQUIRED_SERVER_CLASSES.forEach(missing ->
                assertFalse(shipped.isSupportedBy(JAVA_17, className -> !className.equals(missing)), missing));
    }
}
