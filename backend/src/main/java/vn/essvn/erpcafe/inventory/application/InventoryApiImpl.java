package vn.essvn.erpcafe.inventory.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;

/** Implements the published {@link InventoryApi} over the stock ledger. */
@Service
public class InventoryApiImpl implements InventoryApi {

    private final IngredientRepository ingredientRepository;
    private final StockLedgerService ledger;

    public InventoryApiImpl(IngredientRepository ingredientRepository, StockLedgerService ledger) {
        this.ingredientRepository = ingredientRepository;
        this.ledger = ledger;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean ingredientExists(UUID ingredientId) {
        return ingredientRepository.existsById(ingredientId);
    }

    @Override
    @Transactional
    public CogsResult deductForOrder(UUID branchId, List<StockLine> lines, String refType, UUID refId) {
        return ledger.deduct(branchId, lines, refType, refId);
    }

    @Override
    @Transactional
    public void returnForOrder(UUID branchId, List<StockLine> lines, String refType, UUID refId) {
        ledger.returnStock(branchId, lines, refType, refId);
    }
}
