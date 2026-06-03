package ro.myfinance.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HarnessSmokeIT extends AbstractPostgresIT {

    @Autowired
    DataSource dataSource;

    @Test
    void contextLoadsAndConnectsAsAppRole() throws Exception {
        try (var conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("select current_user")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("myfinance_app");
        }
    }
}
