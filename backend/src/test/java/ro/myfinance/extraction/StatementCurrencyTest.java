package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.Camt053StatementParser;
import ro.myfinance.extraction.adapter.external.Mt940StatementParser;
import ro.myfinance.extraction.application.ParsedStatement;

/**
 * In Spec A every stored transaction is RON, because only RON statement sections are ingested. These
 * assert the currency is nonetheless carried through from the parsers that genuinely know it, so Spec B
 * has a working channel rather than needing a second migration.
 */
class StatementCurrencyTest {

    @Test
    void mt940CarriesTheCurrencyFromItsBalanceTag() {
        // :60F: content is C/D + YYMMDD + CCY + amount; the parser already captures CCY as group 3.
        String mt940 = ":20:REF\n:25:RO98INGB0000999905473924\n:60F:C260101RON1000,00\n"
                + ":61:2601010101D50,00NTRFNONREF\n:86:Plata\n:62F:C260101RON950,00\n";
        ParsedStatement s = new Mt940StatementParser().parse(mt940);
        assertThat(s.currency()).isEqualTo("RON");
    }

    @Test
    void camtReadsTheCcyAttributeNotJustTheAmountText() {
        // The currency in CAMT.053 is an ATTRIBUTE on <Amt>, which the parser did not read at all.
        String camt = "<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\">"
                + "<BkToCstmrStmt><Stmt><Id>1</Id>"
                + "<Acct><Id><IBAN>RO98INGB0000999905473924</IBAN></Id></Acct>"
                + "<Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"EUR\">100.00</Amt>"
                + "<CdtDbtInd>CRDT</CdtDbtInd></Bal>"
                + "<Ntry><Amt Ccy=\"EUR\">25.00</Amt><CdtDbtInd>DBIT</CdtDbtInd>"
                + "<BookgDt><Dt>2026-01-05</Dt></BookgDt></Ntry>"
                + "</Stmt></BkToCstmrStmt></Document>";
        ParsedStatement s = new Camt053StatementParser().parse(camt);
        assertThat(s.currency()).isEqualTo("EUR");
    }

    @Test
    void aParserThatCannotKnowTheCurrencyReportsRon() {
        // Text-PDF bank parsers name no currency reliably, and RON is correct for every statement they
        // handle. Asserted on the record itself so the contract "never null" is pinned.
        ParsedStatement s = new ParsedStatement("BRD", "RO49BRDE", null, null, java.util.List.of(), null);
        assertThat(s.currency()).isEqualTo("RON");
    }
}
