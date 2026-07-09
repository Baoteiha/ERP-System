package vn.essvn.erpcafe.organization.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.web.CompanyResponse;

@Mapper(componentModel = "spring")
public interface CompanyMapper {

    CompanyResponse toResponse(Company company);
}
