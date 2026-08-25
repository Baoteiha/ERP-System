package vn.essvn.erpcafe.inventory.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.inventory.domain.Item;
import vn.essvn.erpcafe.inventory.web.ItemDtos.ItemResponse;

@Mapper(componentModel = "spring")
public interface ItemMapper {

    ItemResponse toResponse(Item item);
}
