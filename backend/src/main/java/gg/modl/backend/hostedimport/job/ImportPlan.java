package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ImportContext;
import gg.modl.backend.hostedimport.mapping.RoleMapper;
import gg.modl.backend.hostedimport.mapping.SettingsImport.SettingsImportPlan;
import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueLog;

public record ImportPlan(ImportContext context, SettingsImportPlan settings, RoleMapper roles, StaffPlan staff) {
    void countConfiguration(IssueLog issues) {
        issues.imported(ImportSubject.ROLES, roles.roles().size());
        issues.imported(ImportSubject.SETTINGS, settings.importedTypes().size());
        issues.skipped(ImportSubject.SETTINGS, settings.unreadableTypes().size());
    }
}
