package ro.myfinance.extraction.adapter.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import org.junit.jupiter.api.Test;

/**
 * Banca Transilvania "EXTRAS CONT" — parsed from position-sorted text. Operations carry a date, a type
 * and a single amount; direction comes from the type (Plată → debit, Încasare → credit). The RULAJ ZI /
 * SOLD lines are daily summaries, not operations, and a same-day follow-up operation omits its date.
 */
class BancaTransilvaniaStatementParserTest {

    private final BancaTransilvaniaStatementParser parser = new BancaTransilvaniaStatementParser();

    // Mirrors the sorted-text shape of a real BT statement, incl. a two-operation day (28/10) whose
    // second operation has no date, and the RULAJ/SOLD summary lines that must be skipped.
    private static final String BT = String.join("\n",
            "Banca Transilvania - CENTRALA",
            "EXTRAS CONT Numarul: 5 din 01/10/2022 - 31/10/2022",
            "Extras numarul 5 RON Cod IBAN: RO68BTRLRONCRT0CH3184101",
            "Data Descriere Debit Credit",
            "SOLD ANTERIOR 104,819.66",
            "03/10/2022 Plata OP intra - canal electronic 300.00",
            "SERVICII CONTABILE 09.2022;5ANCOR EXPERT CONSULT;RO42BTRLRONCRT0314443801;BTRLRO22",
            "REF. 833EINT222760373",
            "03/10/2022 RULAJ ZI 300.00 0.00",
            "SOLD FINAL ZI 104,519.66",
            "14/10/2022 Incasare OP - canal electronic 59,190.04",
            "C.I.F.:30814167;CV FACTURA 5/ 30.09.2022;794SHE INFORMATION TECHNOLOGY SRL;113RONCRT00T0508201;BTRLRO22",
            "REF. 113EIIN222870103",
            "14/10/2022 RULAJ ZI 0.00 59,190.04",
            "SOLD FINAL ZI 163,709.70",
            "28/10/2022 Comision plata OP - canal electronic 0.51",
            "REF. 833ETRZ223010204",
            "Plata OP inter - canal electronic 6,419.00",
            "Impozit micro;46306459;46306459;CHECK12Trezorerie operativa Municipiul Sib;RO96TREZ5765503XXXXXXXXX;TREZROBU",
            "REF. 833ETRZ223010204",
            "28/10/2022 RULAJ ZI 6,419.51 0.00",
            "SOLD FINAL ZI 157,290.19",
            "31/10/2022 RULAJ TOTAL CONT 6,719.51 59,190.04",
            "SOLD FINAL CONT 157,290.19");

    @Test
    void recognisesBancaTransilvania() {
        assertThat(parser.supports(BT)).isTrue();
    }

    @Test
    void parsesOperationsAndSkipsRulajAndSoldSummaries() {
        ParsedStatement s = parser.parse(BT);

        assertThat(s.bankCode()).isEqualTo("BTRL");
        assertThat(s.accountIban()).isEqualTo("RO68BTRLRONCRT0CH3184101");
        assertThat(s.openingBalance()).isEqualByComparingTo("104819.66");
        // Four real operations; the RULAJ ZI / RULAJ TOTAL CONT / SOLD lines are not transactions.
        assertThat(s.transactions()).hasSize(4);
    }

    @Test
    void directionComesFromTheOperationType() {
        ParsedStatement s = parser.parse(BT);
        assertThat(amountOn(s, LocalDate.of(2022, 10, 3))).isEqualByComparingTo("-300.00");   // Plata → debit
        assertThat(amountOn(s, LocalDate.of(2022, 10, 14))).isEqualByComparingTo("59190.04"); // Incasare → credit
    }

    @Test
    void capturesTheDatelessSecondOperationOfTheSameDay() {
        ParsedStatement s = parser.parse(BT);
        // 28/10 has two debits: the 0.51 fee and the dateless 6,419.00 tax payment.
        long oct28 = s.transactions().stream().filter(t -> t.date().equals(LocalDate.of(2022, 10, 28))).count();
        assertThat(oct28).isEqualTo(2);
        assertThat(amountOn(s, LocalDate.of(2022, 10, 28), "-6419.00")).isTrue();
    }

    @Test
    void extractsCounterpartyNameStrippingOrdinalAndCheckMarkers() {
        ParsedStatement s = parser.parse(BT);
        assertThat(partnerOn(s, LocalDate.of(2022, 10, 3))).isEqualTo("ANCOR EXPERT CONSULT");
        assertThat(partnerOn(s, LocalDate.of(2022, 10, 14))).isEqualTo("SHE INFORMATION TECHNOLOGY SRL");
    }

    private static BigDecimal amountOn(ParsedStatement s, LocalDate date) {
        return s.transactions().stream().filter(t -> t.date().equals(date)).findFirst()
                .map(ParsedTransaction::amount).orElseThrow();
    }

    private static boolean amountOn(ParsedStatement s, LocalDate date, String amount) {
        return s.transactions().stream()
                .anyMatch(t -> t.date().equals(date) && t.amount().compareTo(new BigDecimal(amount)) == 0);
    }

    private static String partnerOn(ParsedStatement s, LocalDate date) {
        return s.transactions().stream().filter(t -> t.date().equals(date)).findFirst()
                .map(ParsedTransaction::partnerName).orElseThrow();
    }
}
