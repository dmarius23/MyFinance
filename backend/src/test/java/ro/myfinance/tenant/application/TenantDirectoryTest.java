package ro.myfinance.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantDirectoryTest {

    @Test
    void coercesNumericAndStringLimits() {
        Map<String, Object> limits = new HashMap<>();
        limits.put("maxDocumentsPerCompanyMonth", 50);
        assertThat(TenantDirectory.coerce(limits, "maxDocumentsPerCompanyMonth")).isEqualTo(50L);

        limits.put("maxDocumentsPerCompanyMonth", "25");
        assertThat(TenantDirectory.coerce(limits, "maxDocumentsPerCompanyMonth")).isEqualTo(25L);
    }

    @Test
    void missingBlankZeroNegativeOrGarbageMeansNoLimit() {
        Map<String, Object> limits = new HashMap<>();
        assertThat(TenantDirectory.coerce(limits, "maxDocumentsPerCompanyMonth")).isEqualTo(-1L);
        assertThat(TenantDirectory.coerce(null, "maxDocumentsPerCompanyMonth")).isEqualTo(-1L);

        limits.put("maxDocumentsPerCompanyMonth", 0);
        assertThat(TenantDirectory.coerce(limits, "maxDocumentsPerCompanyMonth")).isEqualTo(-1L);
        limits.put("maxDocumentsPerCompanyMonth", -3);
        assertThat(TenantDirectory.coerce(limits, "maxDocumentsPerCompanyMonth")).isEqualTo(-1L);
        limits.put("maxDocumentsPerCompanyMonth", "  ");
        assertThat(TenantDirectory.coerce(limits, "maxDocumentsPerCompanyMonth")).isEqualTo(-1L);
        limits.put("maxDocumentsPerCompanyMonth", "abc");
        assertThat(TenantDirectory.coerce(limits, "maxDocumentsPerCompanyMonth")).isEqualTo(-1L);
    }
}
