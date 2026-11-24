package gg.modl.backend.migration.service;

import gg.modl.backend.migration.data.ImportEnding;

public interface ImportFileIntake {
    boolean awaitsFile(String jobId);

    boolean claim(String jobId);

    boolean keepReceiving(String jobId);

    boolean isReceiving(String jobId);

    boolean accept(String jobId);

    boolean reopen(String jobId);

    boolean refuse(String jobId, ImportEnding ending);
}
