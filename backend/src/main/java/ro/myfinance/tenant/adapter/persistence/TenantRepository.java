package ro.myfinance.tenant.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.tenant.domain.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
