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
