package vn.essvn.erpcafe.catalog.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.web.ProductDtos.ProductResponse;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.web.MoneyDto;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "basePrice", expression = "java(vn.essvn.erpcafe.common.web.MoneyDto.from(product.getBasePrice()))")
    @Mapping(target = "modifierGroupIds", source = "modifierGroups")
    ProductResponse toResponse(Product product);

    default UUID groupId(ModifierGroup group) {
        return group.getId();
    }

    default MoneyDto money(Money money) {
        return MoneyDto.from(money);
    }
}
