package gg.modl.backend.log.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CredentialCommandRedactor {
    private static final String REDACTED = " [REDACTED]";
    private static final Pattern WORD = Pattern.compile("[^\\s\\p{Z}]++", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SECRET_ASSIGNMENT =
        Pattern.compile("(?:--)?(?:password|pass|token|secret)=", Pattern.CASE_INSENSITIVE);
    private static final String SECRET_FLAG = "--password";
    private static final int WRAPPED_COMMAND_SEARCH_WORDS = 6;

    private static final Set<String> COMMAND_PATHS = Set.of(
        "l", "log", "login", "logar", "autologin", "forcelogin", "startsession",
        "reg", "register", "registrar", "forceregister", "forcereg",
        "unreg", "unregister", "forceunregister", "rmpass",
        "cp", "cpw", "changepassword", "changepass", "changepw", "changepwd", "passch", "passwd", "password",
        "setpassword", "setpass", "createpassword", "forcecreatepassword",
        "forcechangepassword", "forcechangepass", "fcp", "trocarsenha", "mudarsenha",
        "resetpassword", "recovery", "requestpasswordrecovery", "confirmpasswordrecovery", "confirmpasswordreset",
        "email", "setemail", "addemail", "bindemail", "verification", "verifyemail",
        "2fa", "totp", "2fauth", "2fauthcode", "2faconfirm", "activatesecondfactor", "deactivatesecondfactor",
        "auth", "authme", "nlogin", "openlogin", "librelogin", "catseedlogin", "cslogin", "xauth", "lac",
        "premium", "license",
        "account changepassword", "account unregister",
        "modl link", "tclaim", "claimticket",
        "authenticate", "pass", "setpw", "resetpw", "resetpass", "recover", "forgotpassword", "addpassword",
        "pin", "otp", "mfa", "2pa", "senha", "contrasena", "wachtwoord", "motdepasse");
    private static final Set<String> WRAPPABLE_COMMANDS = Set.of(
        "login", "register", "changepassword", "setpassword", "password", "passwd", "2fa", "totp");
    private static final Set<String> CREDENTIAL_NAME_PARTS =
        Set.of("password", "passwd", "login", "register", "totp", "2fa");

    private CredentialCommandRedactor() {
    }

    public static String redact(String commandLine) {
        Matcher words = WORD.matcher(commandLine);
        String root = "";
        int position = 0;
        while (words.find()) {
            String word = words.group();
            String name = position < WRAPPED_COMMAND_SEARCH_WORDS ? commandName(word) : "";
            if (position == 0) {
                if (name.isEmpty()) {
                    continue;
                }
                root = name;
            }
            if (takesCredentials(position, root, name) || SECRET_FLAG.equalsIgnoreCase(word)) {
                return redactedAfter(commandLine, words.end());
            }
            Matcher assignment = SECRET_ASSIGNMENT.matcher(word);
            if (assignment.lookingAt()) {
                return redactedAfter(commandLine, words.start() + assignment.end());
            }
            position++;
        }
        return commandLine;
    }

    private static boolean takesCredentials(int position, String root, String name) {
        if (position >= WRAPPED_COMMAND_SEARCH_WORDS) {
            return false;
        }
        if (position == 0 && (COMMAND_PATHS.contains(name) || CREDENTIAL_NAME_PARTS.stream().anyMatch(name::contains))) {
            return true;
        }
        if (position == 1 && COMMAND_PATHS.contains(String.join(" ", root, name))) {
            return true;
        }
        return WRAPPABLE_COMMANDS.contains(name);
    }

    private static String redactedAfter(String commandLine, int secretStart) {
        boolean hasSecret = WORD.matcher(commandLine).find(secretStart);
        return hasSecret ? commandLine.substring(0, secretStart) + REDACTED : commandLine;
    }

    private static String commandName(String word) {
        int start = 0;
        while (start < word.length() && word.charAt(start) == '/') {
            start++;
        }
        String invoked = word.substring(start);
        return invoked.substring(invoked.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }
}
