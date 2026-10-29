package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.GenericRunningBalanceParser;
import ro.myfinance.extraction.adapter.external.LibraStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Fixture is the real PDFBox text of a Libra "EXTRAS CONT", with names, IBANs, card and client numbers
 * redacted — layout, dates and every amount are untouched, which is all the parser reads.
 */
class LibraStatementParserTest {

    private final LibraStatementParser parser = new LibraStatementParser();

    /**
     * Mirrors PDFBox's extraction (setSortByPosition) of a real Libra "EXTRAS CONT", with holder,
     * IBANs, card and client numbers replaced by fakes. Layout, dates and every amount are the
     * original ones — that is all the parser reads, and it keeps the statement's own TOTAL row
     * usable as the cross-check.
     */
    private static final String FIXTURE = """
            CodClient: 100000000 Generat la data: 26.05.2026 11:00
            Cont IBAN: RO49BREL0002001234567890 EXEMPLU TEST SRL
            Moneda: RON Adresa:
            Perioada extras: 01/04/2026 - 26/05/2026 Str. EXEMPLU, Nr. 1, Sc. 1,
            Date emitere extras: 26/05/2026 Apt. 1, Ors. ORAS, Jud. XX,
            Romania
            Suma disponibila: 2,065.70
            EXTRAS CONT
            SOLD INITIAL 2,509.28
            Data Descriere operatiune Data Debit Credit Sold
            01/04/2026 Tranzactie INCASARE Taxa admin card xxxx O01p/e0r4a/2re026 4.00 0.00
            0000luna 3 (FT26091JVHR9)
            01/04/2026 Sold nou/New balance 2,505.28
            20/04/2026 Tranzactie Platitor:  STRIPE TECHNOLOGY 20/04/2026 0.00 2,533.13
            EUROPE LIMITED
            RO49BREL0002001234567890 Detalii:
            /ROC/JAPANKNIVES /RFB/T5C8Q8
            (FT26110DSVSV)
            20/04/2026 Comision tranzactie (FT26110DSVSV) 20/04/2026 3.00 0.00
            20/04/2026 Sold nou/New balance 5,035.41
            21/04/2026 Comision tranzactie (FT26111T2K4K) 21/04/2026 6.00 0.00
            21/04/2026 Tranzactie Referinta: 4 Beneficiar:  SC 21/04/2026 300.00 0.00
            CONTABIL TEST SRL Platitor :
            1234567890123 Cont benef:
            RO49BREL0002001234567890 Detalii:
            Factura 3909 (FT26111T2K4K)
            21/04/2026 Sold nou/New balance 4,729.41
            24/04/2026 Tranzactie Cumparaturi 138.33 RON 24/04/2026 138.33 0.00
            24/04/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT261147GBCD)
            24/04/2026 Tranzactie Cumparaturi 85.56 RON 24/04/2026 24/04/2026 85.56 0.00
             ONEMAP BYFJ 0000000000 SG CardId: 0000
             MCC: 7278 (FT26114NTLVK)
            24/04/2026 Tranzactie Cumparaturi 99.79 RON 24/04/2026 24/04/2026 99.79 0.00
             ONEMAP BYFJ 0000000000 SG CardId: 0000
             MCC: 7278 (FT26114BMTLR)
            24/04/2026 Sold nou/New balance 4,405.73
            30/04/2026 Tranzactie Cumparaturi 17.99 RON 30/04/2026 30/04/2026 17.99 0.00
             PayUsameday.ro BUCURESTI RO CardId:
            0000 MCC: 4215 (FT26120H104Y)
            30/04/2026 Sold nou/New balance 4,387.74
            04/05/2026 Tranzactie INCASARE Taxa admin card xxxx 04/05/2026 4.00 0.00
            0000luna 4 (FT26124H5FWR)
            04/05/2026 Tranzactie Cumparaturi 10.96 EUR 02/05/2026 04/05/2026 58.06 0.00
             HETZNER ONLINE GMBH GUNZENHAUSEN
            DE CardId: 0000 MCC: 4816 FX 1EUR:
            5.2978RON (FT26124QFFCB)
            04/05/2026 Sold nou/New balance 4,325.68
            05/05/2026 Tranzactie Cumparaturi 1919.00 RON 05/05/2026 1,919.00 0.00
            05/05/2026ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT26125H5Y1R)
            05/05/2026 Tranzactie Reverse tranzactie: Cumparaturi 05/05/2026 0.00 1,919.00
            1919.00 RON 05/05/2026 ONEMAP BYFJ
            LIBRA INTERNET BANK S.A. | www.librabank.ro
            Phoenix Tower, Calea Vitan 6-6A, Sector 3, Bucuresti, 031296, Romania; Tel.: +40(0)21 208. 8000; Fax: +40(0)21 230.6565;
            Swift: BREL RO BU; Numar de ordine in Registrul Comertului J1996000334403; Cod Unic de Inregistrare 8119644, atribut fiscal RO;
            Aut. BNR seria B nr. 000025/24.01.1997; Nr. Inmatriculare RB-PJR-40-037/18.02.1999; Numar inregistrare ca operator de date cu caracter personal:25819.
            Capital social subscris si varsat: 512.622.000 RON. Pentru orice nemultumire, va incurajam sa ne scrieti la reclamatii@librabank.ro
            Pagina 1 din 4
            0000000000 SG CardId: 0000 MCC: 7278 Cod
            original: 68795941 (FT261259ZF62)
            05/05/2026 Tranzactie Cumparaturi 1817.00 RON 05/05/2026 1,817.00 0.00
            05/05/2026ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT26125MJKSD)
            05/05/2026 Sold nou/New balance 2,508.68
            06/05/2026 Tranzactie Cumparaturi 17.99 RON 06/05/2026 06/05/2026 17.99 0.00
             PayUsameday.ro BUCURESTI RO CardId:
            0000 MCC: 4215 (FT26126Z96L4)
            06/05/2026 Sold nou/New balance 2,490.69
            07/05/2026 Tranzactie Refund 71.00 RON 05/05/2026 07/05/2026 0.00 71.00
            ONEMAPBYFJ CardId: 0000 MCC: 7278
            (FT26127GR30N)
            07/05/2026 Sold nou/New balance 2,561.69
            08/05/2026 Tranzactie Cumparaturi 17.99 RON 08/05/2026 08/05/2026 17.99 0.00
             PayUsameday.ro BUCURESTI RO CardId:
            0000 MCC: 4215 (FT26128RT0ZT)
            08/05/2026 Tranzactie Cumparaturi 102.95 RON 08/05/2026 102.95 0.00
            08/05/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT261280JKDV)
            08/05/2026 Tranzactie Cumparaturi 70.33 RON 08/05/2026 08/05/2026 70.33 0.00
             ONEMAP BYFJ 0000000000 SG CardId: 0000
             MCC: 7278 (FT26128GQGH4)
            08/05/2026 Tranzactie Platitor:  Maria Test 08/05/2026 0.00 738.00
            RO49BREL0002001234567890 Detalii:
            Factura JK0009 (FT26128YVKZP)
            08/05/2026 Comision tranzactie (FT26128YVKZP) 08/05/2026 3.00 0.00
            08/05/2026 Sold nou/New balance 3,105.42
            11/05/2026 Tranzactie Refund 97.00 RON 07/05/2026 11/05/2026 0.00 97.00
            ONEMAPBYFJ CardId: 0000 MCC: 7278
            (FT261313Y7VL)
            11/05/2026 Tranzactie Refund 68.00 RON 07/05/2026 11/05/2026 0.00 68.00
            ONEMAPBYFJ CardId: 0000 MCC: 7278
            (FT26131BLWLW)
            11/05/2026 Tranzactie Refund 74.00 RON 08/05/2026 11/05/2026 0.00 74.00
            ONEMAPBYFJ CardId: 0000 MCC: 7278
            (FT261316R24H)
            11/05/2026 Tranzactie Refund 105.00 RON 07/05/2026 11/05/2026 0.00 105.00
            ONEMAPBYFJ CardId: 0000 MCC: 7278
            (FT26131H1HWT)
            11/05/2026 Tranzactie Cumparaturi 356.10 RON 11/05/2026 356.10 0.00
            11/05/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT26131JJD3P)
            11/05/2026 Sold nou/New balance 3,093.32
            14/05/2026 Tranzactie Cumparaturi 518.33 RON 14/05/2026 518.33 0.00
            14/05/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT26134Z8JLT)
            14/05/2026 Sold nou/New balance 2,574.99
            15/05/2026 Tranzactie Cumparaturi 621.60 RON 15/05/2026 621.60 0.00
            15/05/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT26135R8N64)
            15/05/2026 Tranzactie Cumparaturi 30.87 RON 15/05/2026 15/05/2026 30.87 0.00
             ONEMAP BYFJ 0000000000 SG CardId: 0000
             MCC: 7278 (FT26135WZCSW)
            15/05/2026 Tranzactie Cumparaturi 91.58 RON 15/05/2026 15/05/2026 91.58 0.00
             ONEMAP BYFJ 0000000000 SG CardId: 0000
             MCC: 7278 (FT26135381KW)
            15/05/2026 Sold nou/New balance 1,830.94
            LIBRA INTERNET BANK S.A. | www.librabank.ro
            Phoenix Tower, Calea Vitan 6-6A, Sector 3, Bucuresti, 031296, Romania; Tel.: +40(0)21 208. 8000; Fax: +40(0)21 230.6565;
            Swift: BREL RO BU; Numar de ordine in Registrul Comertului J1996000334403; Cod Unic de Inregistrare 8119644, atribut fiscal RO;
            Aut. BNR seria B nr. 000025/24.01.1997; Nr. Inmatriculare RB-PJR-40-037/18.02.1999; Numar inregistrare ca operator de date cu caracter personal:25819.
            Capital social subscris si varsat: 512.622.000 RON. Pentru orice nemultumire, va incurajam sa ne scrieti la reclamatii@librabank.ro
            Pagina 2 din 4
            18/05/2026 Tranzactie Cumparaturi 106.06 RON 18/05/2026 106.06 0.00
            18/05/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT26138JLTBZ)
            18/05/2026 Tranzactie Cumparaturi 103.18 RON 18/05/2026 103.18 0.00
            18/05/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT26138T1RKP)
            18/05/2026 Tranzactie Cumparaturi 427.00 RON 18/05/2026 427.00 0.00
            18/05/2026 ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT261385SX36)
            18/05/2026 Sold nou/New balance 1,194.70
            25/05/2026 Tranzactie Referinta: 5 Debitor: POPESCU 25/05/2026 0.00 290.00
            ION-TEST
            RO49BREL0002001234567890 Beneficiar:
            EXEMPLU TEST SRL Detalii: Plata Factura JK0012
            (FT26145G6ZKZ)
            25/05/2026 Tranzactie Cumparaturi 2119.00 RON 25/05/2026 2,119.00 0.00
            25/05/2026ONEMAP BYFJ 0000000000 SG
            CardId: 0000 MCC: 7278 (FT261458QHPJ)
            25/05/2026 Tranzactie Referinta: 6 Debitor: POPESCU 25/05/2026 0.00 2,700.00
            ION-TEST
            RO49BREL0002001234567890 Beneficiar:
            EXEMPLU TEST SRL Detalii: Creditare societate
            (FT26145XC1BW)
            25/05/2026 Sold nou/New balance 2,065.70
            RULAJ 9038.71 8595.13
            SOLD FINAL 2065.70
            LIBRA INTERNET BANK S.A. | www.librabank.ro
            Phoenix Tower, Calea Vitan 6-6A, Sector 3, Bucuresti, 031296, Romania; Tel.: +40(0)21 208. 8000; Fax: +40(0)21 230.6565;
            Swift: BREL RO BU; Numar de ordine in Registrul Comertului J1996000334403; Cod Unic de Inregistrare 8119644, atribut fiscal RO;
            Aut. BNR seria B nr. 000025/24.01.1997; Nr. Inmatriculare RB-PJR-40-037/18.02.1999; Numar inregistrare ca operator de date cu caracter personal:25819.
            Capital social subscris si varsat: 512.622.000 RON. Pentru orice nemultumire, va incurajam sa ne scrieti la reclamatii@librabank.ro
            Pagina 3 din 4
            MISCARI PE CONT
            Data Sold Initial Rulaj debit Rulaj credit Sold final
            01/04/2026 2,509.28 4.00 0.00 2,505.28
            20/04/2026 2,505.28 3.00 2,533.13 5,035.41
            21/04/2026 5,035.41 306.00 0.00 4,729.41
            24/04/2026 4,729.41 323.68 0.00 4,405.73
            30/04/2026 4,405.73 17.99 0.00 4,387.74
            04/05/2026 4,387.74 62.06 0.00 4,325.68
            05/05/2026 4,325.68 3,736.00 1,919.00 2,508.68
            06/05/2026 2,508.68 17.99 0.00 2,490.69
            07/05/2026 2,490.69 0.00 71.00 2,561.69
            08/05/2026 2,561.69 194.27 738.00 3,105.42
            11/05/2026 3,105.42 356.10 344.00 3,093.32
            14/05/2026 3,093.32 518.33 0.00 2,574.99
            15/05/2026 2,574.99 744.05 0.00 1,830.94
            18/05/2026 1,830.94 636.24 0.00 1,194.70
            25/05/2026 1,194.70 2,119.00 2,990.00 2,065.70
            TOTAL 2,509.28 9,038.71 8,595.13 2,065.70
            *Acest extras de cont a fost generat automat, fara semnatura ori stampila, prin intermediul serviciului Libra Internet
            Banking sau preluat dintr-o sucursala a Libra Internet Bank SA.
            Depozitele  bancare detinute de dumneavoastra la Libra Internet Bank SA sunt incadrate in categoria depozitelor bancare
            garantate conform legii. Pentru mai multe detalii va rugam sa consultati documentul \"Formular pentru informatiile oferite
            deponentilor\", care contine si Lista depozitelor excluse de la garantare, impreuna cu alte informatii, postat pe site-ul bancii
            in sectiunea \"Garantare depozite FGDB\".
            LIBRA INTERNET BANK S.A. | www.librabank.ro
            Phoenix Tower, Calea Vitan 6-6A, Sector 3, Bucuresti, 031296, Romania; Tel.: +40(0)21 208. 8000; Fax: +40(0)21 230.6565;
            Swift: BREL RO BU; Numar de ordine in Registrul Comertului J1996000334403; Cod Unic de Inregistrare 8119644, atribut fiscal RO;
            Aut. BNR seria B nr. 000025/24.01.1997; Nr. Inmatriculare RB-PJR-40-037/18.02.1999; Numar inregistrare ca operator de date cu caracter personal:25819.
            Capital social subscris si varsat: 512.622.000 RON. Pentru orice nemultumire, va incurajam sa ne scrieti la reclamatii@librabank.ro
            Pagina 4 din 4
            """;

    private String fixture() {
        return FIXTURE;
    }

    @Test
    void extractsEveryTransactionAndAgreesWithTheStatementsOwnTotals() {
        ParsedStatement s = parser.parse(fixture());

        assertThat(s.bankCode()).isEqualTo("LIBRA");
        assertThat(s.openingBalance()).isEqualByComparingTo("2509.28");
        assertThat(s.closingBalance()).isEqualByComparingTo("2065.70");
        assertThat(s.transactions()).hasSize(36);

        BigDecimal debits = s.transactions().stream().map(ParsedTransaction::amount)
                .filter(a -> a.signum() < 0).map(BigDecimal::negate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = s.transactions().stream().map(ParsedTransaction::amount)
                .filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        // The statement prints these itself: TOTAL 2,509.28 9,038.71 8,595.13 2,065.70
        assertThat(debits).isEqualByComparingTo("9038.71");
        assertThat(credits).isEqualByComparingTo("8595.13");
        assertThat(s.openingBalance().subtract(debits).add(credits))
                .isEqualByComparingTo(s.closingBalance());
    }

    /**
     * PDFBox ({@code setSortByPosition(true)}) interleaves the overlapping header word "Operare" into
     * the first data row, so its operare date arrives as {@code O01p/e0r4a/2re026}. Matching on the
     * operare column would silently drop this row — and a dropped row still leaves a plausible-looking
     * statement, so it is pinned explicitly.
     */
    @Test
    void readsTheFirstRowDespiteTheHeaderBleedingIntoIt() {
        List<ParsedTransaction> t = parser.parse(fixture()).transactions();

        assertThat(t.getFirst().date()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(t.getFirst().amount()).isEqualByComparingTo("-4.00");
        assertThat(t.getFirst().description()).contains("Taxa admin card");
    }

    /**
     * The per-day summary after "MISCARI PE CONT" has the same date-then-amounts shape as a
     * transaction row, so parsing past it would double-count the entire statement.
     */
    @Test
    void stopsBeforeTheDailySummaryTable() {
        ParsedStatement s = parser.parse(fixture());

        assertThat(s.transactions()).hasSize(36);
        assertThat(s.transactions()).noneMatch(t -> t.description().contains("Rulaj"));
        // 2,509.28 appears in the summary as an opening column; it must not become a transaction.
        assertThat(s.transactions()).noneMatch(t -> t.amount().abs().compareTo(
                new BigDecimal("2509.28")) == 0);
    }

    @Test
    void signsDebitsNegativeAndCreditsPositiveAndKeepsWrappedDescriptions() {
        List<ParsedTransaction> t = parser.parse(fixture()).transactions();

        ParsedTransaction incoming = t.stream()
                .filter(x -> x.date().equals(LocalDate.of(2026, 4, 20)) && x.amount().signum() > 0)
                .findFirst().orElseThrow();
        assertThat(incoming.amount()).isEqualByComparingTo("2533.13");
        // "EUROPE LIMITED" is on a wrapped continuation line, so this proves wrapping is captured.
        assertThat(incoming.description()).contains("STRIPE TECHNOLOGY").contains("EUROPE LIMITED");
        assertThat(incoming.ref()).isEqualTo("FT26110DSVSV");
        assertThat(incoming.partnerIban()).startsWith("RO");

        assertThat(t).filteredOn(x -> x.description().startsWith("Comision tranzactie"))
                .allMatch(x -> x.amount().signum() < 0);
    }

    /** Daily "Sold nou/New balance" rows are closing balances, not transactions. */
    @Test
    void ignoresDailyClosingBalanceRows() {
        assertThat(parser.parse(fixture()).transactions())
                .noneMatch(t -> t.description().contains("Sold nou"));
    }

    @Test
    void recognisesLibraAndDeclinesOtherStatements() {
        assertThat(parser.supports(fixture())).isTrue();
        assertThat(parser.supports("ING Bank N.V. INGBROBU extras de cont")).isFalse();
        assertThat(parser.supports("")).isFalse();
        assertThat(parser.supports(null)).isFalse();
    }

    /**
     * Libra previously fell through to the universal fallback, which expects a running balance on the
     * row and cannot read the separate Debit/Credit columns. This is the regression that motivated the
     * parser, so it is pinned: the fallback must not reproduce the statement's totals.
     */
    @Test
    void genericFallbackCannotParseThisLayout() {
        List<ParsedTransaction> generic = new GenericRunningBalanceParser()
                .parse(fixture()).transactions();

        BigDecimal credits = generic.stream().map(ParsedTransaction::amount)
                .filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(credits).isNotEqualByComparingTo("8595.13");
    }
}
