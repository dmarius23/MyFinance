package ro.myfinance.settings.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.GeneralSettings;

public interface GeneralSettingsRepository extends JpaRepository<GeneralSettings, UUID> {
}
