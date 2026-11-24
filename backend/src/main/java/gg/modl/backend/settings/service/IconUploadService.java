package gg.modl.backend.settings.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageFolders;
import java.util.Arrays;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class IconUploadService {
    private final PublicImageUploader uploader;
    private final GeneralSettingsService generalSettingsService;

    public String uploadIcon(Server server, MultipartFile file, String iconType) {
        IconType type = IconType.of(iconType);
        return uploader.upload(server, file, MediaCategory.SERVER_ICON, type.folder(),
            () -> type.referencedUrl(generalSettingsService.getGeneralSettings(server)));
    }

    private enum IconType {
        HOMEPAGE("homepage", GeneralSettings::getHomepageIconUrl),
        PANEL("panel", GeneralSettings::getPanelIconUrl);

        private final String uploadName;
        private final Function<GeneralSettings, String> referencedUrl;

        IconType(String uploadName, Function<GeneralSettings, String> referencedUrl) {
            this.uploadName = uploadName;
            this.referencedUrl = referencedUrl;
        }

        static IconType of(String uploadName) {
            return Arrays.stream(values())
                .filter(type -> type.uploadName.equals(uploadName))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Invalid icon type. Must be 'homepage' or 'panel'."));
        }

        String folder() {
            return StorageFolders.icon(uploadName);
        }

        String referencedUrl(GeneralSettings settings) {
            return referencedUrl.apply(settings);
        }
    }
}
