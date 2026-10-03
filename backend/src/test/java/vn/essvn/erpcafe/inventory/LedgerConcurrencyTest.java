package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.application.StockLedgerService;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.MovementType;
import vn.essvn.erpcafe.inventory.domain.StockMovement;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.StockItemRepository;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: LedgerConcurrencyTest
 * Purpose: Proves two simultaneous ledger calls create one stock row, one deduction or reversal per reference, and never sell stock that is not there.
 */
class LedgerConcurrencyTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final int ROUNDS = 5;

    @Autowired private StockLedgerService ledger;
    @Autowired private StockMovementRepository movements;
    @Autowired private StockItemRepository stockItems;
    @Autowired private IngredientRepository ingredients;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private PlatformTransactionManager txManager;

    private record Seed(UUID companyId, UUID branchId, UUID ingredientId) {
    }

    private Seed seed() {
        int n = SEQ.incrementAndGet();
        Company company = TenantContext.runAsSystem(
                () -> companies.save(new Company("Ledger Co %d".formatted(n), "LDG%d".formatted(n))));
        return TenantContext.runAs(company.getId(), () -> {
            Branch branch = branches.save(new Branch("Ledger Branch", "LDB%d".formatted(n)));
            Ingredient ingredient = ingredients.save(new Ingredient("Ledger Beans %d".formatted(n), "g", null));
            return new Seed(company.getId(), branch.getId(), ingredient.getId());
        });
    }

    /** Runs work on the seed's ledger as its company, as a request of that company would. */
    private <T> T asCompany(Seed seed, Supplier<T> work) {
        return TenantContext.runAs(seed.companyId(), work);
    }

    /** Runs both tasks as the seed's company in their own transactions, released together so they overlap. */
    private <T> List<T> runTogether(Seed seed, List<Supplier<T>> tasks) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : settleTogether(seed, tasks)) {
            results.add(future.get());
        }
        return results;
    }

    /** Runs the tasks as {@link #runTogether} does, and returns their futures once all have finished, failed or not. */
    private <T> List<Future<T>> settleTogether(Seed seed, List<Supplier<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Supplier<T> task : tasks) {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                futures.add(pool.submit(() -> asCompany(seed, () -> tx.execute(status -> {
                    ready.countDown();
                    try {
                        ready.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return task.get();
                }))));
            }
            for (Future<T> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException failed) {
                    // the caller reads the failure from the future
                }
            }
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    /** "DEDUCTED" for a sale that went through, else the code of the rule that refused it (or the error itself). */
    private static String outcome(Future<?> sale) throws InterruptedException {
        try {
            sale.get();
            return "DEDUCTED";
        } catch (ExecutionException e) {
            return e.getCause() instanceof BusinessRuleException refusal ? refusal.getCode() : e.getCause().toString();
        }
    }

    private BigDecimal onHand(Seed seed) {
        return asCompany(seed, () -> stockItems.findByIngredientIdAndBranchId(seed.ingredientId(), seed.branchId()))
                .orElseThrow().getQuantityOnHand();
    }

    private List<StockLine> grams(Seed seed, String qty) {
        return List.of(new StockLine(seed.ingredientId(), new BigDecimal(qty), "g"));
    }

    @Test
    void twoDeductionsOfOneReference_recordASingleSetOfMovements() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Seed seed = seed();
            asCompany(seed, () -> ledger.receive(seed.branchId(), seed.ingredientId(), new BigDecimal("1000"), null,
                    vn.essvn.erpcafe.common.domain.Money.of(new BigDecimal("2")), "TEST", null));
            UUID orderId = UUID.randomUUID();

            List<CogsResult> results = runTogether(seed, List.of(
                    () -> ledger.deduct(seed.branchId(), grams(seed, "30"), "ORDER", orderId),
                    () -> ledger.deduct(seed.branchId(), grams(seed, "30"), "ORDER", orderId)));

            assertThat(results).hasSize(2);
            List<StockMovement> depletions = asCompany(seed, () -> movements.findByRefTypeAndRefId("ORDER", orderId));
            assertThat(depletions).hasSize(1);
            assertThat(depletions.get(0).getType()).isEqualTo(MovementType.SALE_DEPLETION);
            assertThat(onHand(seed)).isEqualByComparingTo("970");
        }
    }

    @Test
    void twoSalesOfTheLastUnitAtOnce_oneIsRefusedAndStockEndsAtZero() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Seed seed = seed();
            asCompany(seed, () -> ledger.receive(seed.branchId(), seed.ingredientId(), new BigDecimal("18"), null,
                    vn.essvn.erpcafe.common.domain.Money.of(new BigDecimal("2")), "TEST", null));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            List<Future<CogsResult>> sales = settleTogether(seed, List.of(
                    () -> ledger.deduct(seed.branchId(), grams(seed, "18"), "ORDER", first),
                    () -> ledger.deduct(seed.branchId(), grams(seed, "18"), "ORDER", second)));

            assertThat(List.of(outcome(sales.get(0)), outcome(sales.get(1))))
                    .containsExactlyInAnyOrder("DEDUCTED", "INSUFFICIENT_STOCK");
            long depletions = asCompany(seed, () -> movements.findAll()).stream()
                    .filter(m -> m.getType() == MovementType.SALE_DEPLETION
                            && (first.equals(m.getRefId()) || second.equals(m.getRefId())))
                    .count();
            assertThat(depletions).isEqualTo(1);
            assertThat(onHand(seed)).isEqualByComparingTo("0");
        }
    }

    @Test
    void twoFirstSalesOfANeverStockedIngredient_areBothRefusedAndLeaveNoRow() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Seed seed = seed();

            List<Future<CogsResult>> sales = settleTogether(seed, List.of(
                    () -> ledger.deduct(seed.branchId(), grams(seed, "30"), "ORDER", UUID.randomUUID()),
                    () -> ledger.deduct(seed.branchId(), grams(seed, "50"), "ORDER", UUID.randomUUID())));

            // both refused by the rule: neither trips the stock row's unique key
            assertThat(List.of(outcome(sales.get(0)), outcome(sales.get(1))))
                    .containsExactly("INSUFFICIENT_STOCK", "INSUFFICIENT_STOCK");
            assertThat(asCompany(seed, () -> stockItems.findByBranchId(seed.branchId()))).isEmpty();
        }
    }

    @Test
    void twoFirstWastesOfANewIngredient_shareOneStockRow() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Seed seed = seed();

            // Waste can take stock below zero, so both writes land in the one row they race to create.
            runTogether(seed, List.of(
                    () -> ledger.waste(seed.branchId(), seed.ingredientId(), new BigDecimal("30"), "g", null),
                    () -> ledger.waste(seed.branchId(), seed.ingredientId(), new BigDecimal("50"), "g", null)));

            List<vn.essvn.erpcafe.inventory.domain.StockItem> rows =
                    asCompany(seed, () -> stockItems.findByBranchId(seed.branchId()));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getQuantityOnHand()).isEqualByComparingTo("-80");
        }
    }

    @Test
    void voidAndRefundOfOneOrderAtOnce_restoreStockOnlyOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Seed seed = seed();
            asCompany(seed, () -> ledger.receive(seed.branchId(), seed.ingredientId(), new BigDecimal("1000"), null,
                    vn.essvn.erpcafe.common.domain.Money.of(new BigDecimal("2")), "TEST", null));
            UUID orderId = UUID.randomUUID();
            asCompany(seed, () -> ledger.deduct(seed.branchId(), grams(seed, "30"), "ORDER", orderId));

            runTogether(seed, List.of(
                    () -> ledger.reverseDeduction(seed.branchId(), "ORDER", orderId, "ORDER_VOID"),
                    () -> ledger.reverseDeduction(seed.branchId(), "ORDER", orderId, "ORDER_REFUND")));

            long reversals = asCompany(seed, () -> movements.findAll()).stream()
                    .filter(m -> orderId.equals(m.getRefId()) && m.getType() == MovementType.SALE_REVERSAL).count();
            assertThat(reversals).isEqualTo(1);
            assertThat(onHand(seed)).isEqualByComparingTo("1000");
        }
    }
}
