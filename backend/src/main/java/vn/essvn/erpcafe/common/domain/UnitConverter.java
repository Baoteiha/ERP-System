package vn.essvn.erpcafe.common.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Class: UnitConverter
 * Purpose: Stores a Unit as its canonical symbol and reads that symbol back as the Unit.
 */
@Converter
public class UnitConverter implements AttributeConverter<Unit, String> {

    @Override
    public String convertToDatabaseColumn(Unit unit) {
        return unit == null ? null : unit.symbol();
    }

    @Override
    public Unit convertToEntityAttribute(String symbol) {
        return symbol == null ? null : Unit.parse(symbol);
    }
}
