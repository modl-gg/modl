package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    public static final int MAX_LENGTH = 64;

    private static final String NUMBER = "(0|[1-9][0-9]{0,8})";
    private static final Pattern SHAPE = Pattern.compile(NUMBER + "\\." + NUMBER + "\\." + NUMBER + "(?:-([0-9A-Za-z.]{1,32}))?");
    private static final Pattern NUMERIC_IDENTIFIER = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;

    private ReleaseVersion(int major, int minor, int patch, String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    public static ReleaseVersion parse(String text) {
        ReleaseVersion version = tryParse(text);
        if (version == null) {
            throw new IllegalArgumentException("Not a modl release version: " + describe(text));
        }
        return version;
    }

    public static @Nullable ReleaseVersion tryParse(@Nullable String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            return null;
        }
        Matcher matcher = SHAPE.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String prerelease = matcher.group(4);
        if (prerelease != null && !validPrerelease(prerelease)) {
            return null;
        }
        return new ReleaseVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)), prerelease);
    }

    private static boolean validPrerelease(String prerelease) {
        for (String identifier : prerelease.split("\\.", -1)) {
            if (identifier.isEmpty()) {
                return false;
            }
            if (DIGITS.matcher(identifier).matches() && !NUMERIC_IDENTIFIER.matcher(identifier).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(@Nullable String text) {
        if (text == null) {
            return "none";
        }
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "..." : text;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public boolean isPrerelease() {
        return prerelease != null;
    }

    public String line() {
        return major + "." + minor;
    }

    public boolean sameLine(ReleaseVersion other) {
        return major == other.major && minor == other.minor;
    }

    public boolean isNewerThan(ReleaseVersion other) {
        return compareTo(other) > 0;
    }

    public boolean isOlderThan(ReleaseVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        int byNumbers = compareNumbers(other);
        if (byNumbers != 0) {
            return byNumbers;
        }
        if (prerelease == null || other.prerelease == null) {
            return Boolean.compare(prerelease == null, other.prerelease == null);
        }
        return comparePrerelease(prerelease, other.prerelease);
    }

    private int compareNumbers(ReleaseVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    private static int comparePrerelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int shared = Math.min(leftParts.length, rightParts.length);
        for (int i = 0; i < shared; i++) {
            int compared = compareIdentifier(leftParts[i], rightParts[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = DIGITS.matcher(left).matches();
        boolean rightNumeric = DIGITS.matcher(right).matches();
        if (leftNumeric && rightNumeric) {
            return left.length() != right.length() ? Integer.compare(left.length(), right.length()) : left.compareTo(right);
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseVersion)) {
            return false;
        }
        ReleaseVersion version = (ReleaseVersion) other;
        return major == version.major && minor == version.minor && patch == version.patch
            && Objects.equals(prerelease, version.prerelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }

    @Override
    public String toString() {
        String numbers = major + "." + minor + "." + patch;
        return prerelease == null ? numbers : numbers + "-" + prerelease;
    }
}
