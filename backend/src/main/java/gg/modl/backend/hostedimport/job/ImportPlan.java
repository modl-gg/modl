package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ImportContext;
import gg.modl.backend.hostedimport.mapping.RoleMapper;
import gg.modl.backend.hostedimport.mapping.SettingsImport.SettingsImportPlan;
import gg.modl.backend.hostedimport.mapping.StaffPlan;

public record ImportPlan(ImportContext context, SettingsImportPlan settings, RoleMapper roles, StaffPlan staff) {
}
