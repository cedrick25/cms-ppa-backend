package ppa.cms.domain;

import java.util.List;

public final class DataPermissionCatalog {

    public record Item(String module, String moduleLabel, String code, String label, String description, boolean userDefault) {
    }

    private static final List<Item> ITEMS = List.of(
            shared("dashboard", "Dashboard", "can-view-dashboard", "View dashboard", "Open the dashboard."),
            shared("dashboard", "Dashboard", "can-view-production-systems", "View production systems", "See the production systems list and their addresses on the dashboard."),
            shared("dashboard", "Dashboard", "can-retry-production-systems", "Retry production systems", "Retry loading the production systems list."),
            admin("dashboard", "Dashboard", "can-view-sign-in-history", "View sign-in history", "See recent sign-ins on the dashboard."),
            admin("dashboard", "Dashboard", "can-retry-sign-in-history", "Retry sign-in history", "Retry loading recent sign-ins."),
            admin("dashboard", "Dashboard", "can-view-remote-desk-activity", "View remote desk activity", "See opens, views, copies, and updates of server remote desks."),
            admin("dashboard", "Dashboard", "can-filter-remote-desk-activity", "Filter remote desk activity", "Switch the remote desks chart between this week, last week, and the last 30 days."),
            admin("dashboard", "Dashboard", "can-switch-remote-desk-chart", "Switch remote desk chart", "Switch the remote desks chart between bar and line."),
            admin("dashboard", "Dashboard", "can-retry-remote-desk-activity", "Retry remote desk activity", "Retry loading the remote desks chart."),
            admin("dashboard", "Dashboard", "can-view-credential-change-activity", "View information system activity", "See information system name updates."),
            admin("dashboard", "Dashboard", "can-filter-credential-change-activity", "Filter information system activity", "Switch the information systems chart period."),
            admin("dashboard", "Dashboard", "can-retry-credential-change-activity", "Retry information system activity", "Retry loading the information systems chart."),
            admin("dashboard", "Dashboard", "can-view-account-change-activity", "View MySQL credential activity", "See MySQL credential views, copies, updates, and removals."),
            admin("dashboard", "Dashboard", "can-filter-account-change-activity", "Filter MySQL credential activity", "Switch the MySQL credentials chart period."),
            admin("dashboard", "Dashboard", "can-retry-account-change-activity", "Retry MySQL credential activity", "Retry loading the MySQL credentials chart."),
            shared("dashboard", "Dashboard", "can-view-notifications", "View notifications", "Open the notifications panel and see website status alerts."),
            shared("dashboard", "Dashboard", "can-open-notification", "Open a notification", "Open a notification and follow its link."),
            shared("dashboard", "Dashboard", "can-mark-notification-read", "Mark a notification read", "Mark one notification as read."),
            shared("dashboard", "Dashboard", "can-mark-all-notifications-read", "Mark all notifications read", "Mark every notification as read."),
            shared("dashboard", "Dashboard", "can-retry-notifications", "Retry notifications", "Retry loading notifications."),
            shared("dashboard", "Dashboard", "can-search", "Search the CMS", "Use the search box to jump to a page or action."),

            shared("account", "Account", "can-update-own-account", "Update own account", "Open Update account and save your own name, email, username, or password."),
            shared("account", "Account", "can-toggle-theme", "Toggle theme", "Switch between light mode and dark mode."),
            shared("account", "Account", "can-sign-out", "Sign out", "Sign out of the CMS."),

            shared("credentials", "Credentials", "can-view-credentials-management", "View credentials management", "Open the credentials list."),
            shared("credentials", "Credentials", "can-search-systems", "Search systems", "Search registered systems."),
            shared("credentials", "Credentials", "can-filter-systems-by-environment", "Filter systems by environment", "Filter the list by Production, Test, or Legacy."),
            shared("credentials", "Credentials", "can-clear-system-filters", "Clear system filters", "Clear the system search and environment filter."),
            shared("credentials", "Credentials", "can-page-systems", "Page through systems", "Move between pages of the system list."),
            shared("credentials", "Credentials", "can-register-system", "Register a system", "Register a new system and its credentials."),
            shared("credentials", "Credentials", "can-view-system-details", "View system details", "Open the system summary from View / Update."),
            shared("credentials", "Credentials", "can-view-system-addresses", "View system addresses", "Click a row to show that system's addresses."),
            shared("credentials", "Credentials", "can-update-system", "Update a system", "Save changes to a registered system."),
            shared("credentials", "Credentials", "can-delete-system", "Delete a system", "Remove a registered system."),
            shared("credentials", "Credentials", "can-view-remote-access", "View remote access", "Open remote access and see usernames, passwords, and connect actions."),
            shared("credentials", "Credentials", "can-show-remote-password", "Show a remote password", "Reveal a password in the system form or remote access dialog."),
            shared("credentials", "Credentials", "can-copy-remote-value", "Copy a remote value", "Copy an address, id, host, or connection command."),
            shared("credentials", "Credentials", "can-connect-anydesk", "Connect with AnyDesk", "Start an AnyDesk connection."),
            shared("credentials", "Credentials", "can-connect-rustdesk", "Connect with RustDesk", "Start a RustDesk connection."),
            shared("credentials", "Credentials", "can-connect-rdp", "Connect with RDP", "Open or copy a Remote Desktop connection."),
            shared("credentials", "Credentials", "can-connect-ssh", "Connect with SSH", "Open or copy an SSH connection."),
            shared("credentials", "Credentials", "can-setup-one-click-rdp", "Set up one-click RDP", "Change how RDP entries open."),
            shared("credentials", "Credentials", "can-setup-one-click-ssh", "Set up one-click SSH", "Change how SSH entries open."),

            admin("systems-audit", "Systems audit trail", "can-view-systems-audit-trail", "View systems audit trail", "Open the systems audit trail."),
            admin("systems-audit", "Systems audit trail", "can-search-systems-audit", "Search systems audit", "Search system audit records."),
            admin("systems-audit", "Systems audit trail", "can-filter-systems-audit-by-source", "Filter systems audit by source", "Filter records by UAMS, CMIS, or PPIS."),
            admin("systems-audit", "Systems audit trail", "can-filter-systems-audit-by-date", "Filter systems audit by date", "Limit records with the From and To dates."),
            admin("systems-audit", "Systems audit trail", "can-refresh-systems-audit", "Refresh systems audit", "Refresh records from the source systems."),
            admin("systems-audit", "Systems audit trail", "can-clear-systems-audit-filters", "Clear systems audit filters", "Clear the systems audit search, source, and dates."),
            admin("systems-audit", "Systems audit trail", "can-page-systems-audit", "Page through systems audit", "Move between pages of system audit records."),

            admin("cms-audit", "CMS audit trail", "can-view-cms-audit-trail", "View CMS audit trail", "Open the CMS audit trail."),
            admin("cms-audit", "CMS audit trail", "can-search-cms-audit", "Search CMS audit", "Search CMS audit records."),
            admin("cms-audit", "CMS audit trail", "can-filter-cms-audit-by-module", "Filter CMS audit by module", "Filter records by Sign-in, Credentials, Users, or User roles."),
            admin("cms-audit", "CMS audit trail", "can-filter-cms-audit-by-date", "Filter CMS audit by date", "Limit CMS audit records with the From and To dates."),
            admin("cms-audit", "CMS audit trail", "can-refresh-cms-audit", "Refresh CMS audit", "Reload the CMS audit trail."),
            admin("cms-audit", "CMS audit trail", "can-clear-cms-audit-filters", "Clear CMS audit filters", "Clear the CMS audit search, module, and dates."),
            admin("cms-audit", "CMS audit trail", "can-page-cms-audit", "Page through CMS audit", "Move between pages of CMS audit records."),

            admin("users", "Users", "can-view-users", "View users", "Open the registered users list."),
            admin("users", "Users", "can-search-users", "Search users", "Search accounts by name, username, or email."),
            admin("users", "Users", "can-filter-users-by-status", "Filter users by status", "Filter accounts by Active or Disabled."),
            admin("users", "Users", "can-clear-user-filters", "Clear user filters", "Clear the user search and status filter."),
            admin("users", "Users", "can-page-users", "Page through users", "Move between pages of accounts."),
            admin("users", "Users", "can-register-user", "Register a user", "Create an account and choose its role."),
            admin("users", "Users", "can-view-user-details", "View user details", "Open an account and see its details."),
            admin("users", "Users", "can-update-user", "Update a user", "Save changes to an account."),
            admin("users", "Users", "can-reset-user-password", "Reset a user password", "Set a new password for another account."),
            admin("users", "Users", "can-disable-user", "Disable a user", "Disable an account so it cannot sign in."),
            admin("users", "Users", "can-enable-user", "Enable a user", "Enable a disabled account."),
            admin("users", "Users", "can-delete-user", "Delete a user", "Permanently remove an account."),

            admin("user-roles", "User roles", "can-view-user-roles", "View user roles", "Open the user roles list."),
            admin("user-roles", "User roles", "can-search-user-roles", "Search user roles", "Search roles by name or description."),
            admin("user-roles", "User roles", "can-page-user-roles", "Page through user roles", "Move between pages of roles."),
            admin("user-roles", "User roles", "can-create-user-role", "Create a user role", "Create a new user role."),
            admin("user-roles", "User roles", "can-update-user-role", "Update a user role", "Update an existing user role."),
            admin("user-roles", "User roles", "can-view-user-role-details", "View user role details", "View a role together with the permissions assigned to it."),
            admin("user-roles", "User roles", "can-delete-user-role", "Delete a user role", "Delete a user role record."),
            admin("user-roles", "User roles", "can-assign-role-permissions", "Assign permissions", "Give data permissions to a user role.")
    );

    private DataPermissionCatalog() {
    }

    public static List<Item> all() {
        return ITEMS;
    }

    public static boolean userDefault(String code) {
        for (Item item : ITEMS) {
            if (item.code().equals(code)) {
                return item.userDefault();
            }
        }
        return false;
    }

    private static Item shared(String module, String moduleLabel, String code, String label, String description) {
        return new Item(module, moduleLabel, code, label, description, true);
    }

    private static Item admin(String module, String moduleLabel, String code, String label, String description) {
        return new Item(module, moduleLabel, code, label, description, false);
    }
}
