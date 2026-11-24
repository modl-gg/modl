package gg.modl.backend.database.sql;

import java.time.Instant;

public record DatabaseDeparture(String moveToken, String movedTo, Instant movedAt) {
    public boolean belongsTo(String token) {
        return moveToken.equals(token);
    }
}
