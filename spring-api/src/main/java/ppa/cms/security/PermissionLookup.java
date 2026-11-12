package ppa.cms.security;

import ppa.cms.persistence.UserAccount;

public interface PermissionLookup {

    boolean roleReady(UserAccount user);

    boolean allows(UserAccount user, String code);
}
