package gg.modl.minecraft.spigot;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
class LoginDenial {
    String message;
    boolean refusedByCheck;

    static LoginDenial refused(String message) {
        return new LoginDenial(message, true);
    }

    static LoginDenial unchecked(String message) {
        return new LoginDenial(message, false);
    }
}
