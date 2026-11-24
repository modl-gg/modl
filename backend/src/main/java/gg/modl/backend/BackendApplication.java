package gg.modl.backend;

import gg.modl.backend.cli.DotenvFile;
import gg.modl.backend.cli.InvalidDotenvException;
import gg.modl.backend.cli.ModlCli;
import gg.modl.backend.update.guard.UpdateFailureListener;
import gg.modl.backend.update.launcher.UpdateLauncher;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class BackendApplication {
    private static final String OVERRIDES_SOURCE = "modlOverrides";
    private static final int CONFIGURATION_ERROR = 1;

    public static void main(String[] args) {
        Map<String, Object> dotenv;
        try {
            dotenv = DotenvFile.fromWorkingDirectory();
        } catch (InvalidDotenvException e) {
            System.err.println("modl: " + e.getMessage());
            System.exit(CONFIGURATION_ERROR);
            return;
        }
        if (UpdateLauncher.takeOver(args, dotenv)) {
            return;
        }
        if (ModlCli.handles(args)) {
            System.exit(ModlCli.run(args, dotenv));
        }
        application(dotenv).run(args);
    }

    public static SpringApplication application(Map<String, Object> highestPriorityProperties) {
        SpringApplication application = new SpringApplication(BackendApplication.class);
        application.addListeners(new HighestPriorityProperties(Map.copyOf(highestPriorityProperties)), new UpdateFailureListener());
        return application;
    }

    private record HighestPriorityProperties(Map<String, Object> properties)
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
        private static final int BEFORE_ENVIRONMENT_POST_PROCESSORS = Ordered.HIGHEST_PRECEDENCE + 5;

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            if (!properties.isEmpty()) {
                event.getEnvironment().getPropertySources().addFirst(new MapPropertySource(OVERRIDES_SOURCE, properties));
            }
        }

        @Override
        public int getOrder() {
            return BEFORE_ENVIRONMENT_POST_PROCESSORS;
        }
    }
}
