package vn.essvn.erpcafe.identity.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.web.RoleDtos.RoleResponse;

@Mapper(componentModel = "spring")
public interface RoleMapper {

    RoleResponse toResponse(Role role);

    /** Used by MapStruct to convert the permission set into a set of names. */
    default String map(Permission permission) {
        return permission.getName();
    }
}
