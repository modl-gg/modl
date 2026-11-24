package gg.modl.backend.retention.service;

import gg.modl.backend.server.data.Server;
import java.time.Instant;

public interface RetentionTask {

    String subject();

    long purge(Server server, Instant now);
}
