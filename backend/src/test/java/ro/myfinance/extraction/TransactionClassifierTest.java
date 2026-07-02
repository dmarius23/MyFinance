package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.TransactionClassifier;
import ro.myfinance.extraction.application.TransactionClassifier.Input;
import ro.myfinance.extraction.domain.DocCategory;

class TransactionClassifierTest {

    private final TransactionClassifier c = new TransactionClassifier();

    private Input debit(String partnerIban, String partnerName, String desc) {
        return new Input(false, partnerIban, partnerName, desc, "RO00OWN", "Innovatecode It Srl");
    }

    @Test
    void incomingIsNoDoc() {
        var r = c.classify(new Input(true, "RO11", "Client", "incasare", "RO00OWN", "Demo"));
        assertThat(r.requiresDocument()).isFalse();
        assertThat(r.category()).isEqualTo(DocCategory.INCOME);
    }

    @Test
    void treasuryIsTaxNoDoc() {
        var r = c.classify(debit("RO54TREZ21620A470300", "Trezoreria Cluj", "CAM"));
        assertThat(r.requiresDocument()).isFalse();
        assertThat(r.category()).isEqualTo(DocCategory.TAX);
    }

    @Test
    void ownTransferByIbanIsNoDoc() {
        var r = c.classify(new Input(false, "RO00OWN", "Self", "transfer", "RO00OWN", "Demo"));
        assertThat(r.category()).isEqualTo(DocCategory.OWN_TRANSFER);
        assertThat(r.requiresDocument()).isFalse();
    }

    @Test
    void ownTransferByNameIsNoDoc() {
        var r = c.classify(debit(null, "INNOVATECODE IT SRL", "transfer"));
        assertThat(r.category()).isEqualTo(DocCategory.OWN_TRANSFER);
    }

    @Test
    void salaryIsNoDoc() {
        var r = c.classify(debit("RO72BRDE", "Angajat", "salariu luna Ianuarie"));
        assertThat(r.category()).isEqualTo(DocCategory.SALARY);
        assertThat(r.requiresDocument()).isFalse();
    }

    @Test
    void feeIsNoDoc() {
        assertThat(c.classify(debit("RO45", "NETOPIA PAYMENTS", "comision lunar")).category())
                .isEqualTo(DocCategory.FEE);
    }

    @Test
    void bankCommissionIsNoDoc() {
        assertThat(c.classify(debit(null, null, "Comision operatiune")).requiresDocument()).isFalse();
        assertThat(c.classify(debit(null, "Comision tranzactie", null)).category()).isEqualTo(DocCategory.FEE);
    }

    @Test
    void accountMaintenanceIsNoDoc() {
        // Diacritics-insensitive: "Întreținere cont" → normalized "intretinere cont".
        var r = c.classify(debit(null, null, "Întreținere cont"));
        assertThat(r.requiresDocument()).isFalse();
        assertThat(r.category()).isEqualTo(DocCategory.FEE);
    }

    @Test
    void debitWithoutAnyCounterpartyIsNoDoc() {
        // A bank fee the statement left unlabelled: no partner name, no IBAN, no description.
        var r = c.classify(debit(null, null, null));
        assertThat(r.requiresDocument()).isFalse();
        assertThat(r.category()).isEqualTo(DocCategory.FEE);
    }

    @Test
    void debitWithACounterpartyStillNeedsDoc() {
        // A named/IBAN counterparty is a real payment even with no description → still needs a document.
        assertThat(c.classify(debit("RO21BRDE", null, null)).requiresDocument()).isTrue();
        assertThat(c.classify(debit(null, "KAUFLAND 4700 CLUJ", null)).requiresDocument()).isTrue();
    }

    @Test
    void leasingNeedsDoc() {
        var r = c.classify(debit("RO98BTRL", "BT Leasing", "rata leasing Martie"));
        assertThat(r.requiresDocument()).isTrue();
        assertThat(r.category()).isEqualTo(DocCategory.LEASING);
    }

    @Test
    void supplierNeedsDoc() {
        var r = c.classify(debit("RO21BRDE", "SELGROS", "achizitie marfa"));
        assertThat(r.requiresDocument()).isTrue();
        assertThat(r.category()).isEqualTo(DocCategory.SUPPLIER);
    }
}
