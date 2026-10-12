package ro.myfinance.company.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.myfinance.company.application.CompanyRegistryLookup;

/**
 * ANAF's public taxpayer service (<i>PlatitorTvaRest</i>): POST a list of {@code {cui, data}} and get the
 * official record back. No authentication, no registration — but ANAF documents a limit of <b>100 CUIs per
 * request and 1 request per second</b>, which is why the UI calls this one CUI at a time, on demand.
 *
 * <p>Never throws: ANAF being down or slow must not stop an accountant adding a company, so every failure
 * degrades to {@link Optional#empty()} and the form stays manually editable.
 */
@Component
public class AnafCompanyLookup implements CompanyRegistryLookup {

    private static final Logger log = LoggerFactory.getLogger(AnafCompanyLookup.class);

    private final RestClient client;

    public AnafCompanyLookup(RestClient.Builder builder,
                             @Value("${myfinance.anaf.lookup-url:https://webservicesp.anaf.ro/api/PlatitorTvaRest/v9/tva}")
                             String url) {
        this.client = builder.baseUrl(url).build();
    }

    @Override
    public Optional<RegistryCompany> byCui(String cui) {
        String digits = digitsOf(cui);
        if (digits.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode body = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    // The service wants the CUI as a NUMBER (no "RO" prefix) and the date to report on.
                    .body(List.of(Map.of("cui", Long.parseLong(digits), "data", LocalDate.now().toString())))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode found = body == null ? null : body.path("found");
            if (found == null || !found.isArray() || found.isEmpty()) {
                return Optional.empty(); // ANAF answers with an empty "found" for an unknown CUI
            }
            return Optional.of(map(found.get(0), digits));
        } catch (RuntimeException e) {
            log.warn("ANAF lookup failed for CUI {}", digits, e);
            return Optional.empty();
        }
    }

    /** Map ANAF's grouped response. Every field is optional — older records carry less. */
    private RegistryCompany map(JsonNode r, String cui) {
        JsonNode general = r.path("date_generale");
        JsonNode vat = r.path("inregistrare_scop_Tva");
        JsonNode inactive = r.path("stare_inactiv");
        JsonNode fiscal = r.path("adresa_domiciliu_fiscal");

        // The fiscal domicile is the residence the treasury-account matching needs; fall back to the
        // registered office only when ANAF has no fiscal address on file.
        String locality = firstNonBlank(text(fiscal, "ddenumire_Localitate"), text(fiscal, "dlocalitate"),
                text(r.path("adresa_sediu_social"), "sdenumire_Localitate"),
                text(r.path("adresa_sediu_social"), "slocalitate"));
        String county = firstNonBlank(text(fiscal, "ddenumire_Judet"), text(fiscal, "djudet"),
                text(r.path("adresa_sediu_social"), "sdenumire_Judet"),
                text(r.path("adresa_sediu_social"), "sjudet"));

        return new RegistryCompany(
                cui,
                text(general, "denumire"),
                text(general, "nrRegCom"),
                text(general, "cod_CAEN"),
                text(general, "adresa"),
                locality,
                county,
                vat.hasNonNull("scpTVA") ? vat.path("scpTVA").asBoolean() : null,
                inactive.path("statusInactivi").asBoolean(false)
                        || !blank(text(inactive, "dataRadiere")),
                general.path("statusRO_e_Factura").asBoolean(false));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!blank(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** CUI digits only — users paste "RO20464846", ANAF wants 20464846. */
    private static String digitsOf(String cui) {
        return cui == null ? "" : cui.replaceAll("\\D", "");
    }
}
