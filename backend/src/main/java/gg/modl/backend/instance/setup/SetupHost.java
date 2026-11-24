package gg.modl.backend.instance.setup;

import gg.modl.backend.instance.InstanceProperties;

public enum SetupHost {
    PLUGIN("set embedded.admin-email in connection.yml"),
    STANDALONE("set MODL_ADMIN_EMAIL");

    private final String adminEmailInstruction;

    SetupHost(String adminEmailInstruction) {
        this.adminEmailInstruction = adminEmailInstruction;
    }

    public static SetupHost of(InstanceProperties instanceProperties) {
        return instanceProperties.isEmbedded() ? PLUGIN : STANDALONE;
    }

    String adminEmailInstruction() {
        return adminEmailInstruction;
    }
}
