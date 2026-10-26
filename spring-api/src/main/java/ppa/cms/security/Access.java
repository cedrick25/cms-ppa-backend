package ppa.cms.security;

import ppa.cms.persistence.UserAccount;
import ppa.cms.web.ApiException;

public final class Access {

    public static final String DISABLED = "This account is disabled. Contact a CMS-PPA administrator.";

    private Access() {
    }

    public static boolean manageUsers(UserAccount user) {
        return user != null && !user.disabled() && user.administrator();
    }

    public static boolean manageSystems(UserAccount user) {
        return user != null && !user.disabled();
    }

    public static boolean viewAudit(UserAccount user) {
        return manageUsers(user);
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
        if (!manageUsers(actor)) {
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
