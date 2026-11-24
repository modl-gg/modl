package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.ImportApi;

public interface ImportBackend {
    ImportApi api();

    ImportFileUpload newUpload();
}
