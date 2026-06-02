package ro.myfinance.common.security;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps the application {@link javax.sql.DataSource} so every borrowed connection carries the
 * current tenant identity as PostgreSQL session settings ({@code app.tenant_id}, {@code app.role}).
 * RLS policies read these via {@code current_setting('app.tenant_id', true)}.
 *
 * <p>When no tenant is bound (background threads, unauthenticated paths), the settings are blank and
 * RLS returns zero rows — fail closed. The settings are reset before the connection returns to the
 * pool so identity never leaks between requests.
 *
 * <p>Note: this only constrains a role that is itself subject to RLS — i.e. a non-superuser,
 * non-{@code BYPASSRLS} login. The app connects as {@code myfinance_app}; Flyway connects as the
 * admin role for DDL.
 */
public class RlsDataSource extends DelegatingDataSource {

    public RlsDataSource(javax.sql.DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) throws SQLException {
        applySettings(connection);
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                new ResettingHandler(connection));
    }

    private static void applySettings(Connection connection) throws SQLException {
        var identity = TenantContext.current().orElse(null);
        String tenantId = identity != null && identity.tenantId() != null ? identity.tenantId().toString() : "";
        String role = identity != null && identity.role() != null ? identity.role().name() : "";
        // set_config(..., false) = session scope; survives until reset on connection return.
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', " + quote(tenantId) + ", false), "
                    + "set_config('app.role', " + quote(role) + ", false)");
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** Resets the GUCs on close() before the real connection returns to the pool. */
    private record ResettingHandler(Connection delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                if (!delegate.isClosed()) {
                    try (Statement st = delegate.createStatement()) {
                        st.execute("SELECT set_config('app.tenant_id', '', false), "
                                + "set_config('app.role', '', false)");
                    } catch (SQLException ignored) {
                        // best effort; closing anyway
                    }
                }
                delegate.close();
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }
}
