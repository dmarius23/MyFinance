package ro.myfinance.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditMaskingTest {

    @Test
    void masksPersonalDataButKeepsBusinessConfig() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("vatStatus", "platitor");        // business config — kept
        in.put("requiresDocument", true);       // boolean — kept
        in.put("email", "alex.pop@firma.ro");   // masked, but domain preserved
        in.put("name", "Alex Pop");             // personal name — redacted
        in.put("iban", "RO49AAAA1B31007593840000");
        in.put("phone", "0712345678");

        Map<String, Object> out = AuditMasking.mask(in);

        assertThat(out).containsEntry("vatStatus", "platitor").containsEntry("requiresDocument", true);
        assertThat(out.get("email")).isEqualTo("a***@firma.ro");
        assertThat(out).containsEntry("name", "***").containsEntry("iban", "***").containsEntry("phone", "***");
    }

    @Test
    void handlesNullMapAndNullValues() {
        assertThat(AuditMasking.mask(null)).isNull();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("decisionSource", null);
        assertThat(AuditMasking.mask(in)).containsEntry("decisionSource", null);
    }
}
