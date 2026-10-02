package vn.essvn.erpcafe.organization.application;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.exception.BadRequestException;
import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.identity.api.IdentityApi;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.organization.api.BranchCreated;
import vn.essvn.erpcafe.organization.api.BranchDeactivated;
import vn.essvn.erpcafe.organization.api.BranchDeleted;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.web.BranchRequest;
import vn.essvn.erpcafe.sales.api.SalesApi;
import vn.essvn.erpcafe.staff.api.StaffApi;

@Service
@Transactional
public class BranchService {

    private static final String BRANCH_WRITE = "branch:write";

    private final BranchRepository branchRepository;
    private final IdentityApi identityApi;
    private final ApplicationEventPublisher events;
    private final SalesApi salesApi;
    private final StaffApi staffApi;
    private final InventoryApi inventoryApi;

    public BranchService(BranchRepository branchRepository,
            IdentityApi identityApi, ApplicationEventPublisher events, SalesApi salesApi,
            StaffApi staffApi, InventoryApi inventoryApi) {
        this.branchRepository = branchRepository;
        this.identityApi = identityApi;
        this.events = events;
        this.salesApi = salesApi;
        this.staffApi = staffApi;
        this.inventoryApi = inventoryApi;
    }

    @Transactional(readOnly = true)
    public Page<Branch> list(Pageable pageable) {
        return branchRepository.findAll(pageable);
    }

    /** Resolves a branch within the caller's company; another tenant's id is a 404. */
    @Transactional(readOnly = true)
    public Branch get(UUID id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Branch", id));
    }

    public Branch create(BranchRequest request) {
        // a non-owner's grant at the new branch is copied from the branch they act from: without one there is none
        if (!identityApi.isOwner() && BranchContext.current().isEmpty()) {
            throw new BadRequestException("X-Branch-Id is required to create a branch");
        }
        if (branchRepository.existsByCode(request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        Branch branch = new Branch(request.name(), request.code());
        apply(branch, request);
        Branch saved = branchRepository.save(branch);
        events.publishEvent(new BranchCreated(saved.getId(), saved.getCompanyId(),
                identityApi.requireUserId(), saved.isActive()));
        return saved;
    }

    /**
     * Updates a branch in the caller's company. The owning company is deliberately
     * not touched: moving a branch between tenants would hand every branch-scoped
     * query downstream to the wrong company.
     */
    public Branch update(UUID id, BranchRequest request) {
        Branch branch = get(id);
        requireBranchWriteAt(id);
        if (!branch.getCode().equals(request.code()) && branchRepository.existsByCode(request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        boolean wasActive = branch.isActive();
        branch.setCode(request.code());
        apply(branch, request);
        if (wasActive && !branch.isActive()) {
            requireNoLiveWork(branch);
            events.publishEvent(
                    new BranchDeactivated(branch.getId(), branch.getCompanyId(), identityApi.requireUserId()));
        }
        return branch;
    }

    /** A branch with live work (open orders, clocked-in shifts, stock) cannot be deleted or deactivated. */
    public void delete(UUID id) {
        Branch branch = get(id);
        requireBranchWriteAt(id);
        requireNoLiveWork(branch);
        branchRepository.delete(branch);
        events.publishEvent(new BranchDeleted(id, branch.getCompanyId(), identityApi.requireUserId()));
    }

    /**
     * Changing or deleting a branch acts on that branch, not on the one the request names, so the caller's
     * own role there must carry branch:write; an owner passes (ADR-0007).
     */
    private void requireBranchWriteAt(UUID id) {
        if (!identityApi.isOwner() && !identityApi.hasPermissionAt(id, BRANCH_WRITE)) {
            throw new ForbiddenException("You need branch:write at branch " + id);
        }
    }

    private void requireNoLiveWork(Branch branch) {
        UUID id = branch.getId();
        if (salesApi.hasOpenOrders(id)) {
            throw new ConflictException("Branch has open orders; settle or cancel them first");
        }
        if (staffApi.hasOpenShifts(branch.getCompanyId(), id)) {
            throw new ConflictException("Branch has shifts in progress; clock everyone out first");
        }
        if (inventoryApi.hasStockOnHand(id)) {
            throw new ConflictException("Branch still has stock on hand; zero it out first");
        }
    }

    private void apply(Branch branch, BranchRequest request) {
        branch.setName(request.name());
        branch.setAddress(request.address());
        branch.setPhone(request.phone());
        if (request.active() != null) {
            branch.setActive(request.active());
        }
        if (request.taxRate() != null) {
            branch.setTaxRate(request.taxRate());
        }
    }
}
