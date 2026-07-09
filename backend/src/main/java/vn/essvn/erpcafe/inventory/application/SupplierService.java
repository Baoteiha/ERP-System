package vn.essvn.erpcafe.inventory.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.inventory.domain.Supplier;
import vn.essvn.erpcafe.inventory.persistence.SupplierRepository;

/** Supplier master-data management within the acting user's company. */
@Service
@Transactional
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final CurrentUser currentUser;

    public SupplierService(SupplierRepository supplierRepository, CurrentUser currentUser) {
        this.supplierRepository = supplierRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Supplier> list() {
        return supplierRepository.findByCompanyId(currentUser.require().companyId());
    }

    @Transactional(readOnly = true)
    public Supplier get(UUID id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Supplier", id));
    }

    public Supplier create(String name, String contactPhone, String contactEmail, String address) {
        UUID companyId = currentUser.require().companyId();
        if (supplierRepository.existsByCompanyIdAndName(companyId, name)) {
            throw new ConflictException("Supplier already exists: " + name);
        }
        Supplier supplier = new Supplier(companyId, name);
        supplier.setContactPhone(contactPhone);
        supplier.setContactEmail(contactEmail);
        supplier.setAddress(address);
        return supplierRepository.save(supplier);
    }

    public Supplier update(UUID id, String name, String contactPhone, String contactEmail,
            String address, boolean active) {
        Supplier supplier = get(id);
        if (!supplier.getName().equals(name)
                && supplierRepository.existsByCompanyIdAndName(supplier.getCompanyId(), name)) {
            throw new ConflictException("Supplier already exists: " + name);
        }
        supplier.setName(name);
        supplier.setContactPhone(contactPhone);
        supplier.setContactEmail(contactEmail);
        supplier.setAddress(address);
        supplier.setActive(active);
        return supplier;
    }

    public void delete(UUID id) {
        supplierRepository.delete(get(id));
    }
}
