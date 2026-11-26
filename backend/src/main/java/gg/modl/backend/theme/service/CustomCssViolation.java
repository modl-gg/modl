package gg.modl.backend.theme.service;

public enum CustomCssViolation {
    TOO_LARGE("CUSTOM_CSS_TOO_LARGE", "Custom CSS can be at most 1 MiB"),
    INVALID_CHARACTER("CUSTOM_CSS_INVALID_CHARACTER", "Custom CSS cannot contain NUL characters"),
    IMPORT("CUSTOM_CSS_IMPORT", "Custom CSS cannot use @import"),
    MARKUP("CUSTOM_CSS_MARKUP", "Custom CSS cannot contain </style"),
    SCRIPT("CUSTOM_CSS_SCRIPT", "Custom CSS cannot use javascript: or vbscript: URLs, expression(), behavior, or -moz-binding");

    private final String code;
    private final String message;

    CustomCssViolation(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
