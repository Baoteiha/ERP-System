package vn.essvn.erpcafe.organization.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.web.BranchResponse;

@Mapper(componentModel = "spring")
public interface BranchMapper {

    BranchResponse toResponse(Branch branch);

    BranchDto toDto(Branch branch);
}
