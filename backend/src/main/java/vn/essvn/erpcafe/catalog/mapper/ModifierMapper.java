package vn.essvn.erpcafe.catalog.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.web.ModifierDtos.ModifierGroupResponse;
import vn.essvn.erpcafe.catalog.web.ModifierDtos.ModifierResponse;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.web.MoneyDto;

@Mapper(componentModel = "spring")
public interface ModifierMapper {

    @Mapping(target = "required", expression = "java(group.isRequired())")
    ModifierGroupResponse toResponse(ModifierGroup group);

    @Mapping(target = "priceDelta", expression = "java(vn.essvn.erpcafe.common.web.MoneyDto.from(modifier.getPriceDelta()))")
    ModifierResponse toResponse(Modifier modifier);

    default MoneyDto money(Money money) {
        return MoneyDto.from(money);
    }
}
