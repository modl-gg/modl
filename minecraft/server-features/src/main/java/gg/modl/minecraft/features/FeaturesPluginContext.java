package gg.modl.minecraft.features;

public interface FeaturesPluginContext extends FeaturesResources {

    FeatureScheduler getScheduler();

    FeaturePlayerProvider getPlayerProvider();

    String getMinecraftVersion();
}
