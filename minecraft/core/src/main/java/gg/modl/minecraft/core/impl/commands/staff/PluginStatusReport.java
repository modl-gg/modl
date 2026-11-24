package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.api.http.response.StatusReportResponse;
import gg.modl.minecraft.core.boot.CoreState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PluginStatusReport {
    static final String TITLE = "modl plugin status";
    private static final int MAX_VALUE_LENGTH = 200;

    private final List<String> lines = new ArrayList<>();

    PluginStatusReport(String pluginVersion, String platform, String serverVersion, String javaVersion, CoreState state,
                       String panelUrl) {
        lines.add(TITLE);
        line("Plugin", pluginVersion);
        line("Platform", platform + " " + serverVersion);
        line("Java", javaVersion);
        line("State", state.name().toLowerCase(Locale.ROOT));
        line("Panel URL", panelUrl);
    }

    List<String> withBackend(StatusReportResponse backend) {
        List<String> report = new ArrayList<>(lines);
        report.add("");
        for (String backendLine : backend.getReport().split("\n", -1)) {
            report.add(sanitized(backendLine));
        }
        while (!report.isEmpty() && report.get(report.size() - 1).isEmpty()) report.remove(report.size() - 1);
        return report;
    }

    List<String> alone() {
        return new ArrayList<>(lines);
    }

    private void line(String label, String value) {
        lines.add(label + ": " + printable(value));
    }

    private static String printable(String value) {
        if (value == null || value.trim().isEmpty()) return "-";
        return sanitized(value);
    }

    private static String sanitized(String value) {
        String singleLine = value.replaceAll("\\p{Cntrl}", " ").trim();
        return singleLine.length() <= MAX_VALUE_LENGTH ? singleLine : singleLine.substring(0, MAX_VALUE_LENGTH);
    }
}
