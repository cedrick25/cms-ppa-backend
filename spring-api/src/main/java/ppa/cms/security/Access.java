package ppa.cms.security;

import ppa.cms.persistence.UserAccount;
import ppa.cms.web.ApiException;

public final class Access {

    public static final String DISABLED = "This account is disabled. Contact a CMS-PPA administrator.";

    private static volatile PermissionLookup permissions;

    private Access() {
    }

    public static void use(PermissionLookup lookup) {
        permissions = lookup;
    }

    public static boolean allowed(UserAccount user, String code, boolean fallback) {
        if (user == null || user.disabled()) {
            return false;
        }
        PermissionLookup lookup = permissions;
        if (lookup != null && lookup.roleReady(user)) {
            return lookup.allows(user, code);
        }
        return fallback;
    }

    public static void requirePermission(UserAccount user, String code, boolean fallback) {
        if (!allowed(user, code, fallback)) {
            throw ApiException.message(403, "This action is unauthorized.");
        }
    }

    public static boolean manageUsers(UserAccount user) {
        return allowed(user, "can-view-users", user != null && user.administrator());
    }

    public static boolean manageSystems(UserAccount user) {
        return allowed(user, "can-view-credentials-management", user != null);
    }

    public static boolean viewAudit(UserAccount user) {
        return allowed(user, "can-view-systems-audit-trail", user != null && user.administrator())
                || allowed(user, "can-view-cms-audit-trail", user != null && user.administrator());
    }

    public static void requireManageUsers(UserAccount user) {
        if (!manageUsers(user)) {
            throw ApiException.message(403, "This action is unauthorized.");
        }
    }

    public static void requireManageSystems(UserAccount user) {
        if (!manageSystems(user)) {
            throw ApiException.message(403, "This action is unauthorized.");
        }
    }

    public static void requireViewAudit(UserAccount user) {
        if (!viewAudit(user)) {
            throw ApiException.message(403, "This action is unauthorized.");
        }
    }

    public static void requireSignIns(UserAccount user) {
        requireManageUsers(user);
    }

    public static boolean canResetPassword(UserAccount actor, UserAccount target) {
        return resetPasswordDenial(actor, target) == null;
    }

    public static String resetPasswordDenial(UserAccount actor, UserAccount target) {
        if (!allowed(actor, "can-reset-user-password", actor != null && actor.administrator())) {
            return "Your account isn't allowed to set passwords for other users.";
        }
        if (actor.getId().equals(target.getId())) {
            return "To change your own password, use Change password and enter your current password.";
        }
        if (target.administrator()) {
            return "Administrators manage their own passwords. You can't set a password for another administrator.";
        }
        return null;
    }
}
