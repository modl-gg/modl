package gg.modl.backend.storage.service;

import gg.modl.backend.server.data.Server;
import java.util.Collection;

public interface StoredObjectDeletionListener {

    void onStoredObjectsDeleted(Server server, Collection<String> keys);
}
