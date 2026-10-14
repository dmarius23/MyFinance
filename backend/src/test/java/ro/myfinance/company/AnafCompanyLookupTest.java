package ro.myfinance.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.myfinance.company.adapter.external.AnafCompanyLookup;
import ro.myfinance.company.application.CompanyRegistryLookup.RegistryCompany;

/** Maps ANAF's grouped taxpayer response, and never lets a registry problem break company creation. */
class AnafCompanyLookupTest {

    private static final String URL = "https://anaf.test/api/PlatitorTvaRest/v9/tva";

    /** A lookup wired to a mocked ANAF, with the canned body it should return. */
    private AnafCompanyLookup lookupReturning(String json) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                // ANAF wants the CUI as a NUMBER, without the "RO" prefix.
                .andExpect(jsonPath("$[0].cui").isNumber())
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return new AnafCompanyLookup(builder, URL);
    }

    @Test
    void mapsTheGroupedResponseAndPrefersTheFiscalDomicile() {
        RegistryCompany c = lookupReturning("""
            {"found":[{
              "date_generale":{"cui":20464846,"denumire":"MERIC SRL","nrRegCom":"J12/131/2007",
                               "adresa":"STR. FLORILOR 192","cod_CAEN":"6920","statusRO_e_Factura":true},
              "inregistrare_scop_Tva":{"scpTVA":true},
              "stare_inactiv":{"statusInactivi":false},
              "adresa_domiciliu_fiscal":{"ddenumire_Localitate":"FLORESTI","ddenumire_Judet":"CLUJ"},
              "adresa_sediu_social":{"sdenumire_Localitate":"CLUJ-NAPOCA","sdenumire_Judet":"CLUJ"}
            }],"notFound":[]}""")
                .byCui("RO20464846").orElseThrow();

        assertThat(c.cui()).isEqualTo("20464846");          // "RO" prefix stripped
        assertThat(c.legalName()).isEqualTo("MERIC SRL");
        assertThat(c.regNo()).isEqualTo("J12/131/2007");
        assertThat(c.caen()).isEqualTo("6920");
        assertThat(c.vatPayer()).isTrue();
        assertThat(c.inactive()).isFalse();
        assertThat(c.eFacturaRegistered()).isTrue();
        // The FISCAL domicile wins over the registered office — that is the residence the treasury-account
        // matching needs, and getting it from ANAF is the point of this lookup.
        assertThat(c.fiscalLocality()).isEqualTo("FLORESTI");
        assertThat(c.fiscalCounty()).isEqualTo("CLUJ");
    }

    @Test
    void fallsBackToTheRegisteredOfficeWhenThereIsNoFiscalAddress() {
        RegistryCompany c = lookupReturning("""
            {"found":[{"date_generale":{"denumire":"X SRL"},
                       "adresa_sediu_social":{"sdenumire_Localitate":"BRASOV"}}]}""")
                .byCui("123").orElseThrow();

        assertThat(c.fiscalLocality()).isEqualTo("BRASOV");
    }

    @Test
    void reportsAStruckOffCompanyAsInactive() {
        RegistryCompany c = lookupReturning("""
            {"found":[{"date_generale":{"denumire":"VECHI SRL"},
                       "stare_inactiv":{"statusInactivi":false,"dataRadiere":"2024-03-01"}}]}""")
                .byCui("999").orElseThrow();

        assertThat(c.inactive()).isTrue(); // struck off counts as inactive even when the flag is false
    }

    @Test
    void missingVatSectionLeavesTheStatusUnknownRatherThanGuessing() {
        RegistryCompany c = lookupReturning("{\"found\":[{\"date_generale\":{\"denumire\":\"Y SRL\"}}]}")
                .byCui("321").orElseThrow();

        assertThat(c.vatPayer()).isNull(); // null ≠ "not a VAT payer"
    }

    @Test
    void unknownCuiIsEmptyRatherThanAnError() {
        assertThat(lookupReturning("{\"found\":[],\"notFound\":[\"123\"]}").byCui("123")).isEmpty();
    }

    @Test
    void registryFailureNeverPropagates() {
        // ANAF down or rate-limiting must not block adding a company — the form stays manually editable.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(new AnafCompanyLookup(builder, URL).byCui("123")).isEmpty();
    }

    @Test
    void aCuiWithNoDigitsIsNotEvenSentToAnaf() {
        // No request is expected on this server — asking for junk must not hit the rate-limited service.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();
        AnafCompanyLookup lookup = new AnafCompanyLookup(builder, URL);

        assertThat(lookup.byCui("not-a-cui")).isEmpty();
        assertThat(lookup.byCui(null)).isEmpty();
    }
}
