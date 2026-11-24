package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.staffmode.custody.CustodyFiles;
import gg.modl.minecraft.features.staffmode.custody.CustodyStore;
import lombok.Value;

import java.nio.file.Path;

@Value
public class StaffModePersistence {
    StartingLocationStore startingLocations;
    CustodyStore custody;

    public static StaffModePersistence in(Path dataFolder, PluginLogger logger) {
        return new StaffModePersistence(new StartingLocationFile(dataFolder, logger), new CustodyFiles(dataFolder, logger));
    }
}
