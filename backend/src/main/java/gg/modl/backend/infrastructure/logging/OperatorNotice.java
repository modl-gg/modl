package gg.modl.backend.infrastructure.logging;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OperatorNotice {
    public static final String LOGGER_NAME = "modl.notice";

    private static final Logger NOTICE_LOG = LoggerFactory.getLogger(LOGGER_NAME);

    public void publish(String message, @Nullable Object... arguments) {
        NOTICE_LOG.info(message, arguments);
    }
}
