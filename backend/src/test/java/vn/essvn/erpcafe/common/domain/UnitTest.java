package vn.essvn.erpcafe.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;

class UnitTest {

    @Test
    void parse_acceptsCanonicalSymbols() {
        assertThat(Unit.parse("g")).isEqualTo(Unit.G);
        assertThat(Unit.parse("kg")).isEqualTo(Unit.KG);
        assertThat(Unit.parse("ml")).isEqualTo(Unit.ML);
        assertThat(Unit.parse("pc")).isEqualTo(Unit.PC);
    }

    @Test
    void parse_isCaseInsensitiveAndTrims() {
        assertThat(Unit.parse("  KG ")).isEqualTo(Unit.KG);
        assertThat(Unit.parse("Ml")).isEqualTo(Unit.ML);
    }

    @Test
    void parse_acceptsCommonSpellings() {
        assertThat(Unit.parse("kilogram")).isEqualTo(Unit.KG);
        assertThat(Unit.parse("grams")).isEqualTo(Unit.G);
        assertThat(Unit.parse("litre")).isEqualTo(Unit.L);
        // Ingredient's javadoc advertised "unit" as the countable base unit, so existing
        // rows are likely to hold it.
        assertThat(Unit.parse("unit")).isEqualTo(Unit.PC);
        assertThat(Unit.parse("pcs")).isEqualTo(Unit.PC);
    }

    @Test
    void parse_rejectsUnknownText() {
        assertThatThrownBy(() -> Unit.parse("sack"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unknown unit 'sack'");
    }

    @Test
    void parse_rejectsBlank() {
        assertThatThrownBy(() -> Unit.parse("  "))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unit is required");
        assertThatThrownBy(() -> Unit.parse(null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void symbol_isTheCanonicalStoredSpelling() {
        assertThat(Unit.KG.symbol()).isEqualTo("kg");
        assertThat(Unit.PC.symbol()).isEqualTo("pc");
    }

    @Test
    void convert_scalesUpWithinDimension() {
        assertThat(Unit.convert(new BigDecimal("6"), Unit.KG, Unit.G)).isEqualByComparingTo("6000");
        assertThat(Unit.convert(new BigDecimal("1.5"), Unit.L, Unit.ML)).isEqualByComparingTo("1500");
    }

    @Test
    void convert_scalesDownWithinDimension() {
        assertThat(Unit.convert(new BigDecimal("500"), Unit.G, Unit.KG)).isEqualByComparingTo("0.5");
        assertThat(Unit.convert(new BigDecimal("250"), Unit.ML, Unit.L)).isEqualByComparingTo("0.25");
    }

    @Test
    void convert_isExactAcrossTheWidestRange() {
        // 1 mg is a millionth of a kg; nothing may round away to zero.
        assertThat(Unit.convert(BigDecimal.ONE, Unit.MG, Unit.KG)).isEqualByComparingTo("0.000001");
        assertThat(Unit.convert(new BigDecimal("0.018"), Unit.KG, Unit.G)).isEqualByComparingTo("18");
    }

    @Test
    void convert_returnsSameQuantityForSameUnit() {
        BigDecimal quantity = new BigDecimal("18.0000");
        assertThat(Unit.convert(quantity, Unit.G, Unit.G)).isSameAs(quantity);
    }

    @Test
    void convert_rejectsCrossDimension() {
        assertThatThrownBy(() -> Unit.convert(BigDecimal.ONE, Unit.KG, Unit.ML))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("MASS")
                .hasMessageContaining("VOLUME");
        assertThatThrownBy(() -> Unit.convert(BigDecimal.ONE, Unit.PC, Unit.G))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void convertRate_scalesInverselyToQuantity() {
        // 210,000d per kg is 210d per g -- the quantity multiplies by 1000, the rate divides.
        assertThat(Unit.convertRate(new BigDecimal("210000"), Unit.KG, Unit.G))
                .isEqualByComparingTo("210");
        assertThat(Unit.convertRate(new BigDecimal("210"), Unit.G, Unit.KG))
                .isEqualByComparingTo("210000");
    }

    @Test
    void convertRate_rejectsCrossDimension() {
        assertThatThrownBy(() -> Unit.convertRate(new BigDecimal("1000"), Unit.L, Unit.G))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void isCompatibleWith_comparesDimension() {
        assertThat(Unit.G.isCompatibleWith(Unit.KG)).isTrue();
        assertThat(Unit.G.isCompatibleWith(Unit.ML)).isFalse();
        assertThat(Unit.PC.isCompatibleWith(null)).isFalse();
    }

    @Test
    void of_listsUnitsOfOneDimensionSmallestFirst() {
        assertThat(Unit.of(Dimension.MASS)).containsExactly(Unit.MG, Unit.G, Unit.KG);
        assertThat(Unit.of(Dimension.VOLUME)).containsExactly(Unit.ML, Unit.CL, Unit.L);
        assertThat(Unit.of(Dimension.COUNT)).containsExactly(Unit.PC);
    }
}
