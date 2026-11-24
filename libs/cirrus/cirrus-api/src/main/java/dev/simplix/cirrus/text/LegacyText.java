package dev.simplix.cirrus.text;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;

@UtilityClass
public class LegacyText {

    private static final AdventureSerializer SECTION_CODES = AdventureSerializer.serializer(ClientVersion.getLatest());

    public Component deserialize(@NonNull String sectionCodedText) {
        return SECTION_CODES.fromLegacy(sectionCodedText);
    }
}
