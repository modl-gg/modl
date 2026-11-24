package gg.modl.backend.appeal.service;

import gg.modl.backend.infrastructure.exception.ValidationException;

public class PunishmentNotStartedException extends ValidationException {
    public static final String MACHINE_CODE = "PUNISHMENT_NOT_STARTED";

    private static final String MESSAGE = "This punishment has not been started yet and cannot be appealed at this time.";

    public PunishmentNotStartedException() {
        super(MESSAGE, MACHINE_CODE);
    }
}
