package gg.modl.backend.backup;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
public class InterruptedRestoreNotice implements SmartInitializingSingleton {
    private final UploadsRestoreMarker marker;
    private final OperatorNotice operatorNotice;

    public InterruptedRestoreNotice(DataDirectoryProperties dataDirectory, OperatorNotice operatorNotice) {
        this.marker = new UploadsRestoreMarker(dataDirectory.root());
        this.operatorNotice = operatorNotice;
    }

    @Override
    public void afterSingletonsInstantiated() {
        marker.warning().ifPresent(warning -> operatorNotice.publish("{}", warning));
    }
}
