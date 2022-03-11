package org.xapps.services.repositories;

import lombok.extern.slf4j.Slf4j;
import org.xapps.services.entities.UserRole;
import org.xapps.services.repositories.utils.Repository;

@Slf4j
public class UserRoleRepository extends Repository<UserRole, UserRole.UserRoleId> {
    public UserRoleRepository() {
        super(UserRole.class);
    }
}
