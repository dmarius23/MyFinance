package ro.myfinance.mod01_tenant.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.mod01_tenant.domain.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
