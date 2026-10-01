package vn.essvn.erpcafe.catalog.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Category;
import vn.essvn.erpcafe.catalog.persistence.CategoryRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;

/** Menu category management within the acting user's company. */
@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CurrentUser currentUser;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository,
            CurrentUser currentUser) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Category> list() {
        return categoryRepository.findByCompanyIdOrderByDisplayOrderAsc(currentUser.require().companyId());
    }

    @Transactional(readOnly = true)
    public Category get(UUID id) {
        return categoryRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Category", id));
    }

    public Category create(String name, int displayOrder) {
        UUID companyId = currentUser.require().companyId();
        if (categoryRepository.existsByCompanyIdAndName(companyId, name)) {
            throw new ConflictException("Category already exists: " + name);
        }
        return categoryRepository.save(new Category(companyId, name, displayOrder));
    }

    public Category update(UUID id, String name, int displayOrder, boolean active) {
        Category category = get(id);
        if (!category.getName().equals(name)
                && categoryRepository.existsByCompanyIdAndName(category.getCompanyId(), name)) {
            throw new ConflictException("Category already exists: " + name);
        }
        category.setName(name);
        category.setDisplayOrder(displayOrder);
        category.setActive(active);
        return category;
    }

    public void delete(UUID id) {
        Category category = get(id);
        if (productRepository.existsByCompanyIdAndCategoryId(category.getCompanyId(), id)) {
            throw new ConflictException("Category still has products; move or delete them first");
        }
        categoryRepository.delete(category);
    }
}
