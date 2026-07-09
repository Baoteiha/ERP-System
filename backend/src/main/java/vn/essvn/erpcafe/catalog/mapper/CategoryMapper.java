package vn.essvn.erpcafe.catalog.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.catalog.domain.Category;
import vn.essvn.erpcafe.catalog.web.CategoryDtos.CategoryResponse;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryResponse toResponse(Category category);
}
