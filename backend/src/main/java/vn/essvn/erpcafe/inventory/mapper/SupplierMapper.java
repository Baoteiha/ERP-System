package vn.essvn.erpcafe.inventory.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.inventory.domain.Supplier;
import vn.essvn.erpcafe.inventory.web.SupplierDtos.SupplierResponse;

@Mapper(componentModel = "spring")
public interface SupplierMapper {

    SupplierResponse toResponse(Supplier supplier);
}
