package ro.myfinance.intake.adapter.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.intake.domain.TenantStorageConfig;

public interface TenantStorageConfigRepository extends JpaRepository<TenantStorageConfig, UUID> {

    Optional<TenantStorageConfig> findByTenantId(UUID tenantId);
}
