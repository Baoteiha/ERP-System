package vn.essvn.erpcafe.organization.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
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
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.mapper.BranchMapper;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.web.BranchRequest;
import vn.essvn.erpcafe.sales.api.SalesApi;
import vn.essvn.erpcafe.staff.api.StaffApi;

@Service
@Transactional
public class BranchService implements OrganizationApi {

    private static final String BRANCH_WRITE = "branch:write";

    private final BranchRepository branchRepository;
    private final BranchMapper branchMapper;
    private final IdentityApi identityApi;
    private final ApplicationEventPublisher events;
    private final SalesApi salesApi;
    private final StaffApi staffApi;
    // Lazy: inventory's ledger depends on OrganizationApi, which this service implements.
    private final ObjectProvider<InventoryApi> inventoryApi;

    public BranchService(BranchRepository branchRepository, BranchMapper branchMapper,
            IdentityApi identityApi, ApplicationEventPublisher events, SalesApi salesApi,
            StaffApi staffApi, ObjectProvider<InventoryApi> inventoryApi) {
        this.branchRepository = branchRepository;
        this.branchMapper = branchMapper;
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
        if (inventoryApi.getObject().hasStockOnHand(id)) {
            throw new ConflictException("Branch still has stock on hand; zero it out first");
        }
    }

    // --- OrganizationApi (published to other modules) ---
    //
    // These take an explicit companyId and are deliberately not scoped through
    // the caller's identity: callers are other modules that already pass the caller's own
    // company, plus login, which reads as the system across every company. Inside a
    // tenant the seam has already narrowed the rows to that company; the companyId
    // still decides, so the answer is the same in either scope.

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchDto> findBranch(UUID companyId, UUID branchId) {
        return branchRepository.findById(branchId)
                .filter(b -> b.getCompanyId().equals(companyId))
                .map(branchMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> companyIdOf(UUID branchId) {
        return branchRepository.findById(branchId).map(Branch::getCompanyId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean branchExistsInCompany(UUID branchId, UUID companyId) {
        return branchRepository.findById(branchId)
                .filter(b -> b.isActive() && b.getCompanyId().equals(companyId))
                .isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchDto> listActiveBranches(UUID companyId) {
        return branchRepository.findByCompanyIdAndActiveTrue(companyId).stream()
                .map(branchMapper::toDto)
                .toList();
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
