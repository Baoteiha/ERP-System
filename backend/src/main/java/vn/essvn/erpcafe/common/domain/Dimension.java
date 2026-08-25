package vn.essvn.erpcafe.common.domain;

/**
 * The physical quantity a {@link Unit} measures. Conversion is only ever defined
 * within one dimension: grams convert to kilograms, never to millilitres.
 */
public enum Dimension {
    MASS,
    VOLUME,
    COUNT
}
