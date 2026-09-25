package io.github.guthman.ducklakeshim;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Delegates to pgjdbc and rewrites one DuckDB error so DataGrip's "retry without ctid" fallback fires.
 *
 * <p>Accepts ordinary {@code jdbc:postgresql:} URLs. It deliberately does not register itself with
 * {@link java.sql.DriverManager}: tools such as DataGrip instantiate the configured driver class
 * directly, and self-registration would let this driver claim plain Postgres URLs in any app that
 * happens to have the jar on its classpath.
 */
public final class DucklakeShimDriver implements Driver {

    private final Driver delegate = new org.postgresql.Driver();

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        Connection connection = delegate.connect(url, info);
        return connection == null ? null : Shim.wrapConnection(connection);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
}
