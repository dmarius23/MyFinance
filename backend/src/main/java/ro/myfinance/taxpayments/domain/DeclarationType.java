package ro.myfinance.taxpayments.domain;

/** ANAF declaration kinds we extract amounts from. */
public enum DeclarationType {
    /** Impozit pe profit / venitul microîntreprinderilor. */
    D100,
    /** Contribuții sociale, impozit pe venit, evidența nominală (employer monthly). XML root = declaratieUnica. */
    D112,
    /** Decont de TVA. */
    D300
}
