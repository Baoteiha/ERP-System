package vn.essvn.erpcafe.inventory.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.domain.Supplier;
import vn.essvn.erpcafe.inventory.persistence.SupplierRepository;

/** Supplier master-data management within the acting user's company. */
@Service
@Transactional
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @Transactional(readOnly = true)
    public List<Supplier> list() {
        return supplierRepository.findAll();
    }

    /** Another company's supplier is not found (404): the tenant seam scopes the lookup. */
    @Transactional(readOnly = true)
    public Supplier get(UUID id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Supplier", id));
    }

    public Supplier create(String name, String contactPhone, String contactEmail, String address) {
        if (supplierRepository.existsByName(name)) {
            throw new ConflictException("Supplier already exists: " + name);
        }
        Supplier supplier = new Supplier(name);
        supplier.setContactPhone(contactPhone);
        supplier.setContactEmail(contactEmail);
        supplier.setAddress(address);
        return supplierRepository.save(supplier);
    }

    public Supplier update(UUID id, String name, String contactPhone, String contactEmail,
            String address, boolean active, Long expectedVersion) {
        Supplier supplier = get(id);
        ConflictException.requireCurrentVersion(supplier.getVersion(), expectedVersion);
        if (!supplier.getName().equals(name) && supplierRepository.existsByName(name)) {
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
