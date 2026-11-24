package gg.modl.backend.theme.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PublicImageUploader;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageFolders;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ThemeBackgroundUploadService {
    private final PublicImageUploader uploader;
    private final PublicThemeService themes;

    public String upload(Server server, MultipartFile file) {
        return uploader.upload(server, file, MediaCategory.SERVER_ICON, StorageFolders.THEME_BACKGROUND, () -> themes.current().backgroundImageUrl());
    }
}
