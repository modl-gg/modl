package gg.modl.backend.hostedimport.mapping;

import static java.util.Map.entry;

import java.util.Map;

final class HostedOnlyPermissionNames {
    static final Map<String, String> BY_ID = Map.ofEntries(
        entry("admin.settings.view.domain", "View Domain"),
        entry("admin.settings.view.billing", "View Billing"),
        entry("admin.settings.view.migration", "View Migration"),
        entry("admin.settings.modify.domain", "Modify Domain"),
        entry("admin.settings.modify.billing", "Modify Billing"),
        entry("admin.settings.modify.migration", "Modify Migration"),
        entry("staff.maintenance", "Maintenance Mode"),
        entry("ticket.close.all.lock", "Lock Tickets"),
        entry("ticket.manage.subscribe", "Manage Subscriptions"),
        entry("ticket.delete.all", "Delete Tickets"));

    private HostedOnlyPermissionNames() {
    }
}
