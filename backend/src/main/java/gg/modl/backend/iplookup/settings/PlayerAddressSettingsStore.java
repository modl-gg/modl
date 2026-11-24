package gg.modl.backend.iplookup.settings;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class PlayerAddressSettingsStore {

    private final CachedSettingsDocument<PlayerAddressSettings> settings;
    private final CachedSettingsDocument<AddressForwardingNotice> notice;

    public PlayerAddressSettingsStore(SettingsDocumentService documents, UnitOfWork unitOfWork) {
        this.settings = new CachedSettingsDocument<>(SettingsType.PLAYER_ADDRESSES,
            SettingsCodec.of(PlayerAddressSettings.class, () -> PlayerAddressSettings.EMPTY), documents, unitOfWork);
        this.notice = new CachedSettingsDocument<>(SettingsType.ADDRESS_FORWARDING,
            SettingsCodec.of(AddressForwardingNotice.class, () -> AddressForwardingNotice.NONE), documents, unitOfWork);
    }

    public PlayerAddressSettings current() {
        return settings.current();
    }

    public PlayerAddressSettings update(UnaryOperator<PlayerAddressSettings> change) {
        return settings.update(change);
    }

    public AddressForwardingNotice notice() {
        return notice.current();
    }

    public AddressForwardingNotice updateNotice(UnaryOperator<AddressForwardingNotice> change) {
        return notice.update(change);
    }
}
