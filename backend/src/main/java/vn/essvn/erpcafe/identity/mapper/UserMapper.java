package vn.essvn.erpcafe.identity.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.web.UserDtos.UserResponse;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);
}
