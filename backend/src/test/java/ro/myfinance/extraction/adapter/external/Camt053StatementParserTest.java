package ro.myfinance.extraction.adapter.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

class Camt053StatementParserTest {

    private final Camt053StatementParser parser = new Camt053StatementParser();

    // A minimal but realistic CAMT.053: opening/closing balances, one debit + one credit, with
    // counterparty name/IBAN and remittance. Namespaced (camt.053.001.02) to exercise local-name lookup.
    private static final String CAMT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
              <BkToCstmrStmt>
                <Stmt>
                  <Id>STMT-1</Id>
                  <Acct><Id><IBAN>RO98INGB0000999905473924</IBAN></Id></Acct>
                  <Bal>
                    <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="RON">1000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>
                  <Bal>
                    <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="RON">1150.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>
                  <Ntry>
                    <Amt Ccy="RON">200.00</Amt>
                    <CdtDbtInd>DBIT</CdtDbtInd>
                    <Sts>BOOK</Sts>
                    <BookgDt><Dt>2026-03-05</Dt></BookgDt>
                    <NtryDtls><TxDtls>
                      <Refs><EndToEndId>INV-778</EndToEndId></Refs>
                      <RltdPties>
                        <Cdtr><Nm>ACME FURNIZOR SRL</Nm></Cdtr>
                        <CdtrAcct><Id><IBAN>RO49AAAA1234567890123456</IBAN></Id></CdtrAcct>
                      </RltdPties>
                      <RmtInf><Ustrd>Plata factura INV-778</Ustrd></RmtInf>
                    </TxDtls></NtryDtls>
                  </Ntry>
                  <Ntry>
                    <Amt Ccy="RON">350.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                    <Sts>BOOK</Sts>
                    <BookgDt><Dt>2026-03-12</Dt></BookgDt>
                    <NtryDtls><TxDtls>
                      <RltdPties>
                        <Dbtr><Nm>CLIENT BETA SRL</Nm></Dbtr>
                        <DbtrAcct><Id><IBAN>RO12BBBB0000000000000001</IBAN></Id></DbtrAcct>
                      </RltdPties>
                      <RmtInf><Ustrd>Incasare</Ustrd></RmtInf>
                    </TxDtls></NtryDtls>
                  </Ntry>
                  <Ntry>
                    <Amt Ccy="RON">99.00</Amt>
                    <CdtDbtInd>DBIT</CdtDbtInd>
                    <Sts>PDNG</Sts>
                    <BookgDt><Dt>2026-03-13</Dt></BookgDt>
                  </Ntry>
                </Stmt>
              </BkToCstmrStmt>
            </Document>
            """;

    @Test
    void supportsCamtXml() {
        assertThat(parser.supports(CAMT)).isTrue();
        assertThat(parser.supports("just some pdf text")).isFalse();
    }

    @Test
    void parsesBalancesAndBookedEntriesWithDirection() {
        ParsedStatement s = parser.parse(CAMT);

        assertThat(s.accountIban()).isEqualTo("RO98INGB0000999905473924");
        assertThat(s.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1150.00");
        assertThat(s.transactions()).hasSize(2); // the PDNG (pending) entry is excluded

        ParsedTransaction debit = s.transactions().get(0);
        assertThat(debit.date()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(debit.amount()).isEqualByComparingTo("-200.00"); // DBIT → negative
        assertThat(debit.partnerName()).isEqualTo("ACME FURNIZOR SRL"); // creditor (we paid)
        assertThat(debit.partnerIban()).isEqualTo("RO49AAAA1234567890123456");
        assertThat(debit.ref()).isEqualTo("INV-778");

        ParsedTransaction credit = s.transactions().get(1);
        assertThat(credit.amount()).isEqualByComparingTo("350.00"); // CRDT → positive
        assertThat(credit.partnerName()).isEqualTo("CLIENT BETA SRL"); // debtor (we received)

        // Cross-check: opening + Σ == closing.
        BigDecimal sum = s.transactions().stream().map(ParsedTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo("1150.00");
    }
}
