package gg.modl.minecraft.core.embedded;

import lombok.Value;

@Value
class CentralLibrary {
    MavenArtifact artifact;
    ServerLibrary file;
}
