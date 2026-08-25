package vn.essvn.erpcafe.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * A unit of measure, with a closed vocabulary and conversion within a
 * {@link Dimension}. Every quantity crossing a module boundary — a recipe line, a
 * purchase line, a stock movement — is a number plus one of these.
 *
 * <p>The set is deliberately fixed rather than user-configurable. Packaging
 * ("sack", "case", "box of 20 bags") is <em>not</em> a unit: it is a purchasing
 * convenience that belongs on the item being bought, expressed as a quantity of a
 * real unit. Keeping the two apart means there is exactly one mechanism for
 * converting a number, and it needs no database lookup.
 *
 * <p>Factors are relative to a canonical unit per dimension (g, ml, pc). They are
 * all powers of ten, which is what makes {@link #convert} exact.
 */
public enum Unit {

    MG(Dimension.MASS, "0.001"),
    G(Dimension.MASS, "1"),
    KG(Dimension.MASS, "1000"),

    ML(Dimension.VOLUME, "1"),
    CL(Dimension.VOLUME, "10"),
    L(Dimension.VOLUME, "1000"),

    PC(Dimension.COUNT, "1");

    /**
     * Scale floor for conversion results. Matches {@code StockLedgerService.CALC_SCALE}
     * so a quantity keeps the same precision wherever it is computed.
     */
    public static final int CALC_SCALE = 10;

    /**
     * Spellings accepted by {@link #parse}, beyond each unit's own symbol. Free-text
     * units predate this type ({@code Ingredient.baseUnit} was a plain string, and its
     * javadoc advertised "unit" as a count), so parsing has to absorb what real data
     * already contains rather than reject it.
     */
    private static final Map<String, Unit> ALIASES = aliases();

    private final Dimension dimension;
    private final BigDecimal factorToCanonical;

    Unit(Dimension dimension, String factorToCanonical) {
        this.dimension = dimension;
        this.factorToCanonical = new BigDecimal(factorToCanonical);
    }

    public Dimension dimension() {
        return dimension;
    }

    /** The canonical spelling, and the form persisted in every unit column. */
    public String symbol() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isCompatibleWith(Unit other) {
        return other != null && dimension == other.dimension;
    }

    /** Every unit measuring the same thing, in ascending order of size. */
    public static List<Unit> of(Dimension dimension) {
        return Arrays.stream(values()).filter(u -> u.dimension == dimension).toList();
    }

    /**
     * Resolves a unit from user input or stored text: case-insensitive, trimmed, and
     * tolerant of the common spellings. Unknown text is rejected rather than guessed —
     * silently defaulting an unrecognised unit is how a recipe ends up deducting
     * thousandths of what it should.
     */
    public static Unit parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleException("Unit is required (one of: " + supported() + ")");
        }
        Unit unit = ALIASES.get(raw.trim().toLowerCase(Locale.ROOT));
        if (unit == null) {
            throw new BusinessRuleException("Unknown unit '" + raw.trim() + "' (expected one of: " + supported() + ")");
        }
        return unit;
    }

    /**
     * Converts a quantity between two units of the same dimension.
     *
     * <p>Rejects a cross-dimension conversion instead of returning anything: there is no
     * defensible number of millilitres in a kilogram, and a caller that asks for one has
     * a bug that must not be papered over with a zero.
     */
    public static BigDecimal convert(BigDecimal quantity, Unit from, Unit to) {
        if (quantity == null) {
            throw new BusinessRuleException("Quantity is required");
        }
        if (!from.isCompatibleWith(to)) {
            throw new BusinessRuleException(
                    "Cannot convert %s to %s: %s and %s measure different things"
                            .formatted(from.symbol(), to.symbol(), from.dimension, to.dimension));
        }
        if (from == to) {
            return quantity;
        }
        BigDecimal canonical = quantity.multiply(from.factorToCanonical);
        // Every factor is a power of ten and the largest divisor is 1000, so three digits
        // beyond the canonical value's own scale make the division exact — nothing rounds.
        int scale = Math.max(canonical.scale() + 3, CALC_SCALE);
        return canonical.divide(to.factorToCanonical, scale, RoundingMode.HALF_EVEN);
    }

    /**
     * Converts a <em>per-unit</em> rate — a cost, typically — which scales inversely to
     * the quantity it prices. 210,000₫/kg is 210₫/g: the quantity multiplies by 1000
     * while the rate divides by it. Getting this backwards misprices stock by a factor
     * of a million, so it is a named operation rather than an open-coded division.
     */
    public static BigDecimal convertRate(BigDecimal ratePerUnit, Unit from, Unit to) {
        if (ratePerUnit == null) {
            throw new BusinessRuleException("Rate is required");
        }
        // One `from` is this many `to`; the rate divides by exactly that.
        BigDecimal perFrom = convert(BigDecimal.ONE, from, to);
        if (perFrom.signum() == 0) {
            throw new BusinessRuleException("Cannot convert a rate from " + from.symbol() + " to " + to.symbol());
        }
        return ratePerUnit.divide(perFrom, Math.max(ratePerUnit.scale(), CALC_SCALE), RoundingMode.HALF_EVEN);
    }

    /** Canonical symbols, for error messages and validation constraints. */
    public static String supported() {
        return Arrays.stream(values()).map(Unit::symbol).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static Map<String, Unit> aliases() {
        Map<String, Unit> map = new LinkedHashMap<>();
        for (Unit unit : values()) {
            map.put(unit.symbol(), unit);
        }
        register(map, MG, "milligram", "milligrams");
        register(map, G, "gr", "gram", "grams", "gam");
        register(map, KG, "kgs", "kilo", "kilos", "kilogram", "kilograms");
        register(map, ML, "mls", "milliliter", "millilitre", "milliliters", "millilitres");
        register(map, CL, "centiliter", "centilitre");
        register(map, L, "lit", "ltr", "liter", "litre", "liters", "litres");
        // "unit" is the spelling Ingredient's own javadoc used for a countable item, so it
        // is the value most likely to already exist in a base_unit column.
        register(map, PC, "pcs", "piece", "pieces", "unit", "units", "ea", "each");
        return Map.copyOf(map);
    }

    private static void register(Map<String, Unit> map, Unit unit, String... spellings) {
        for (String spelling : spellings) {
            map.put(spelling, unit);
        }
    }
}
