package io.github.guthman.ducklakeshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Runs without a database: pgjdbc objects are replaced by proxies that throw the pg_ducklake error. */
class ShimTest {

    private static final String DUCKLAKE_MESSAGE = "ERROR: (PGDuckDB/CreatePlan) Prepared query returned an error: "
            + "Binder Error: Referenced column \"ctid\" not found in FROM clause!";

    private static SQLException ducklakeError() {
        return new SQLException(DUCKLAKE_MESSAGE, "XX000");
    }

    @Test
    void rawErrorIsNotRecognisedByDataGrip() {
        assertFalse(DataGrip.isRowIdError(ducklakeError()));
    }

    @Test
    void translatedErrorIsRecognisedAndKeepsOriginal() {
        SQLException original = ducklakeError();
        SQLException translated = Shim.translate(original);

        assertTrue(DataGrip.isRowIdError(translated));
        assertTrue(translated.getMessage().startsWith(DUCKLAKE_MESSAGE));
        assertEquals("XX000", translated.getSQLState());
        assertSame(original, translated.getCause());
    }

    @Test
    void unrelatedErrorsPassThroughUntouched() {
        SQLException other = new SQLException("ERROR: relation \"nope\" does not exist", "42P01");
        assertSame(other, Shim.translate(other));

        SQLException alreadyPostgres = new SQLException("ERROR: column \"ctid\" does not exist", "42703");
        assertSame(alreadyPostgres, Shim.translate(alreadyPostgres));
    }

    @Test
    void translatesOnEveryExecutionPath() throws SQLException {
        Connection connection = Shim.wrapConnection(fakeConnection());

        Statement statement = connection.createStatement();
        assertTrue(DataGrip.isRowIdError(assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT t.*, ctid FROM lake.t t"))));
        assertTrue(DataGrip.isRowIdError(assertThrows(SQLException.class,
                () -> statement.execute("SELECT t.*, ctid FROM lake.t t"))));

        PreparedStatement prepared = connection.prepareStatement("SELECT t.*, ctid FROM lake.t t");
        assertTrue(DataGrip.isRowIdError(assertThrows(SQLException.class, prepared::executeQuery)));

        // Cursor-based fetches can surface the error on next() rather than on execute.
        ResultSet rs = connection.prepareStatement("SELECT 1").getResultSet();
        assertTrue(DataGrip.isRowIdError(assertThrows(SQLException.class, rs::next)));
    }

    @Test
    void childObjectsPointBackAtTheProxy() throws SQLException {
        Connection connection = Shim.wrapConnection(fakeConnection());
        Statement statement = connection.createStatement();

        assertSame(connection, statement.getConnection());
        assertSame(connection, connection.prepareStatement("SELECT 1").getConnection());
        assertTrue(connection.equals(connection));
    }

    // --- fakes ------------------------------------------------------------------------------------

    private static Connection fakeConnection() {
        Connection[] self = new Connection[1];
        self[0] = fake(Connection.class, (name, args) -> {
            switch (name) {
                case "createStatement":
                    return fakeStatement(Statement.class, self[0]);
                case "prepareStatement":
                    return fakeStatement(PreparedStatement.class, self[0]);
                default:
                    return null;
            }
        });
        return self[0];
    }

    private static <T extends Statement> T fakeStatement(Class<T> type, Connection owner) {
        return fake(type, (name, args) -> {
            switch (name) {
                case "getConnection":
                    return owner;
                case "getResultSet":
                    return fake(ResultSet.class, (n, a) -> {
                        if (n.equals("next")) {
                            throw ducklakeError();
                        }
                        return null;
                    });
                case "execute":
                case "executeQuery":
                    throw ducklakeError();
                default:
                    return null;
            }
        });
    }

    private interface Behaviour {
        Object call(String method, Object[] args) throws SQLException;
    }

    private static <T> T fake(Class<T> type, Behaviour behaviour) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    return behaviour.call(method.getName(), args);
                }));
    }
}
