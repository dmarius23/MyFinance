package ro.myfinance.ingestion.adapter.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.ModuleSyncStatus;

public interface ModuleSyncStatusRepository extends JpaRepository<ModuleSyncStatus, UUID> {

    Optional<ModuleSyncStatus> findByModuleAndPeriodMonth(String module, LocalDate periodMonth);
}
