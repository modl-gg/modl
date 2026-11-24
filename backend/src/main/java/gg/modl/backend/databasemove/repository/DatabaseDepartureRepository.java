package gg.modl.backend.databasemove.repository;

import gg.modl.backend.database.sql.DatabaseDeparture;

public interface DatabaseDepartureRepository {
    void mark(DatabaseDeparture departure);

    void clear();
}
