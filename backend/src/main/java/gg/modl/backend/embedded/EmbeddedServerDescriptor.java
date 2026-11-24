package gg.modl.backend.embedded;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.update.ReleaseBuild;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

final class EmbeddedServerDescriptor {
    private static final String LOCAL_URL = "localUrl";
    private static final String PUBLIC_URL = "publicUrl";
    private static final String VERSION = "version";
    private static final String LOCAL_PORT_PROPERTY = "local.server.port";
    private static final String BIND_ADDRESS_PROPERTY = "server.address";
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final Set<String> WILDCARD_ADDRESSES = Set.of("0.0.0.0", "::", "[::]");

    private final String localUrl;
    private final PublicUrls publicUrls;

    EmbeddedServerDescriptor(ConfigurableApplicationContext context) {
        this.localUrl = localUrl(context.getEnvironment());
        this.publicUrls = context.getBean(PublicUrls.class);
    }

    Map<String, String> describe() {
        return Map.of(
            LOCAL_URL, localUrl,
            PUBLIC_URL, publicUrls.panel(),
            VERSION, version()
        );
    }

    static String version() {
        return ReleaseBuild.current().versionText();
    }

    private static String localUrl(Environment environment) {
        String bindAddress = Optional.ofNullable(environment.getProperty(BIND_ADDRESS_PROPERTY))
            .map(String::trim)
            .filter(address -> !address.isEmpty() && !WILDCARD_ADDRESSES.contains(address))
            .orElse(LOOPBACK_ADDRESS);
        String host = bindAddress.contains(":") && !bindAddress.startsWith("[") ? "[" + bindAddress + "]" : bindAddress;
        return "http://" + host + ":" + environment.getRequiredProperty(LOCAL_PORT_PROPERTY);
    }
}
