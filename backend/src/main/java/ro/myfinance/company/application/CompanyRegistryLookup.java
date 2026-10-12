package ro.myfinance.company.application;

import java.util.Optional;

/**
 * Official company data looked up by fiscal code, so the accountant doesn't retype what the state
 * already knows. Implemented against ANAF's public taxpayer service.
 *
 * <p>Always best-effort: a lookup failure must never block adding a company — the form stays manually
 * editable and the registry only ever <em>suggests</em> values.
 */
public interface CompanyRegistryLookup {

    /** Registry data for {@code cui}, or empty when unknown / the registry is unreachable. */
    Optional<RegistryCompany> byCui(String cui);

    /**
     * What the registry knows. {@code fiscalLocality}/{@code fiscalCounty} come from the fiscal
     * domicile (domiciliu fiscal) — that is the residence the treasury-account matching needs, not the
     * registered office.
     *
     * @param vatPayer            true/false per the VAT register; null when the registry didn't say
     * @param inactive            struck off or declared inactive — worth warning about before adding
     * @param eFacturaRegistered  enrolled in the RO e-Factura register (drives future invoice polling)
     */
    record RegistryCompany(String cui, String legalName, String regNo, String caen, String address,
                           String fiscalLocality, String fiscalCounty, Boolean vatPayer,
                           boolean inactive, boolean eFacturaRegistered) {
    }
}
