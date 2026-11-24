package gg.modl.minecraft.core.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class CredentialCommandRedactor {
    public static final String REDACTED = "[REDACTED]";

    private static final Pattern LEADING_SLASHES = Pattern.compile("^[/\\s\\p{Z}]+");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\s\\p{Z}]+");
    private static final List<String> CREDENTIAL_COMMANDS = Collections.unmodifiableList(Arrays.asList(
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
            "modl link", "tclaim", "claimticket"));

    private final Set<String> commandPaths;
    private final int longestPathWords;

    public CredentialCommandRedactor(Map<String, String> commandAliases, Collection<String> configuredCommands) {
        List<String> commands = new ArrayList<>(CREDENTIAL_COMMANDS);
        commands.addAll(configuredCommands);
        for (String alias : aliasesOf(commandAliases, "modl")) commands.add(alias + " link");
        commands.addAll(aliasesOf(commandAliases, "tclaim"));

        Set<String> paths = new HashSet<>();
        int longest = 0;
        for (String command : commands) {
            List<String> words = wordsOf(command);
            if (words.isEmpty()) continue;
            paths.add(pathOf(words));
            longest = Math.max(longest, words.size());
        }
        this.commandPaths = Collections.unmodifiableSet(paths);
        this.longestPathWords = longest;
    }

    public static CredentialCommandRedactor withBuiltInCommands() {
        return new CredentialCommandRedactor(Collections.emptyMap(), Collections.emptyList());
    }

    public String redact(String commandLine) {
        List<String> words = wordsOf(commandLine);
        for (int length = 1; length <= longestPathWords && length < words.size(); length++) {
            List<String> invocation = words.subList(0, length);
            if (commandPaths.contains(pathOf(invocation))) {
                return "/" + String.join(" ", invocation) + " " + REDACTED;
            }
        }
        return commandLine;
    }

    private static List<String> wordsOf(String commandLine) {
        String body = LEADING_SLASHES.matcher(commandLine).replaceFirst("");
        return body.isEmpty() ? Collections.emptyList() : Arrays.asList(WORD_SEPARATOR.split(body));
    }

    private static String pathOf(List<String> words) {
        String path = String.join(" ", words);
        String root = words.get(0);
        return path.substring(root.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    private static List<String> aliasesOf(Map<String, String> commandAliases, String command) {
        List<String> aliases = new ArrayList<>();
        String configured = commandAliases.get(command);
        if (configured == null) return aliases;
        for (String alias : configured.split("\\|")) {
            if (!alias.trim().isEmpty()) aliases.add(alias.trim());
        }
        return aliases;
    }
}
