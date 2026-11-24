package gg.modl.minecraft.features;

import java.nio.file.Path;

public interface FeaturesResources {
    Path getDataFolder();

    void saveDefaultResource(String resourcePath);
}
