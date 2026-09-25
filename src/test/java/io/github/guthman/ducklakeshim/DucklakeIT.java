package io.github.guthman.ducklakeshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;
import org.postgresql.util.PSQLException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Runs against a throwaway pg_ducklake container; image set by the {@code ducklake.image} property. */
class DucklakeIT {

    private static final String DUCKLAKE_TABLE = "lake.events";
    private static final String DUCKLAKE_VIEW = "lake.v_events";
    private static final String HEAP_TABLE = "lake.heap_events";

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void start() throws SQLException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        String image = System.getProperty("ducklake.image", "pgducklake/pgducklake:18-v1.0.2");
        postgres = new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                // The image's 0000-preload.sh restarts the init server once more than stock postgres.
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 3)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        postgres.start();

        try (Connection c = pgjdbc(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA lake");
            s.execute("CREATE TABLE " + DUCKLAKE_TABLE + " (id bigint, name text, score int) USING ducklake");
            s.execute("INSERT INTO " + DUCKLAKE_TABLE + " VALUES (1, 'alpha', 10), (2, 'beta', 20), (3, 'gamma', 30)");
            s.execute("CREATE VIEW " + DUCKLAKE_VIEW + " AS SELECT * FROM " + DUCKLAKE_TABLE);
            s.execute("CREATE TABLE " + HEAP_TABLE + " AS SELECT * FROM " + DUCKLAKE_TABLE);
        }
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Connection connect(Driver driver) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());
        return driver.connect(postgres.getJdbcUrl(), props);
    }

    private static Connection pgjdbc() throws SQLException {
        return connect(new org.postgresql.Driver());
    }

    private static Connection shim() throws SQLException {
        return connect(new DucklakeShimDriver());
    }

    // --- the bug and the fix, end to end ------------------------------------------------------

    @Test
    void withPlainPgjdbcDataGripCannotOpenTheTable() throws SQLException {
        try (Connection c = pgjdbc()) {
            SQLException e = assertThrows(SQLException.class, () -> DataGrip.openTable(c, DUCKLAKE_TABLE));
            assertTrue(e.getMessage().contains(Shim.DUCKDB_CTID_ERROR), e.getMessage());
        }
    }

    @Test
    void withShimDataGripOpensTheTable() throws SQLException {
        try (Connection c = shim()) {
            List<List<Object>> rows = DataGrip.openTable(c, DUCKLAKE_TABLE);
            assertEquals(List.of(
                    List.of(1L, "alpha", 10),
                    List.of(2L, "beta", 20),
                    List.of(3L, "gamma", 30)), rows);
        }
    }

    // --- the translated error ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ctid", "CTID", "t.ctid", "\"ctid\""})
    void everySpellingOfCtidIsTranslated(String ctid) throws SQLException {
        try (Connection c = shim(); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> s.executeQuery("SELECT t.*, " + ctid + " FROM " + DUCKLAKE_TABLE + " t"));
            assertTrue(DataGrip.isRowIdError(e), DataGrip.gridMessage(e));
            assertEquals("XX000", e.getSQLState());
            PSQLException original = assertInstanceOf(PSQLException.class, e.getCause());
            assertTrue(e.getMessage().startsWith(original.getMessage().trim()), "original text is kept");
        }
    }

    @Test
    void preparedStatementWithCursorFetchIsTranslated() throws SQLException {
        try (Connection c = shim();
                PreparedStatement ps = c.prepareStatement("SELECT t.*, ctid FROM " + DUCKLAKE_TABLE + " t")) {
            c.setAutoCommit(false);
            ps.setFetchSize(1);
            SQLException e = assertThrows(SQLException.class, ps::executeQuery);
            assertTrue(DataGrip.isRowIdError(e), DataGrip.gridMessage(e));
            c.rollback();
        }
    }

    // --- things the shim must leave alone ----------------------------------------------------

    @Test
    void otherDuckdbBinderErrorsAreNotDisguised() throws SQLException {
        try (Connection c = shim(); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> s.executeQuery("SELECT no_such_column FROM " + DUCKLAKE_TABLE));
            assertFalse(DataGrip.isRowIdError(e), DataGrip.gridMessage(e));
            assertInstanceOf(PSQLException.class, e, "untranslated errors keep their pgjdbc type");
        }
    }

    @Test
    void heapTablesStillGetTheirCtid() throws SQLException {
        try (Connection c = shim(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT ctid FROM " + HEAP_TABLE + " ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("(0,1)", rs.getString(1));
        }
    }

    @Test
    void postgresOwnViewErrorIsPassedThroughAsIs() throws SQLException {
        try (Connection raw = pgjdbc(); Connection c = shim();
                Statement rawStatement = raw.createStatement(); Statement s = c.createStatement()) {
            String sql = "SELECT ctid FROM " + DUCKLAKE_VIEW;
            SQLException expected = assertThrows(SQLException.class, () -> rawStatement.executeQuery(sql));
            SQLException actual = assertThrows(SQLException.class, () -> s.executeQuery(sql));
            assertEquals(expected.getMessage(), actual.getMessage());
            assertEquals("42703", actual.getSQLState());
        }
    }

    // --- the wrapper stays transparent ------------------------------------------------------

    @Test
    void pgjdbcApiIsStillReachable() throws SQLException {
        try (Connection c = shim(); Statement s = c.createStatement()) {
            assertTrue(((PGConnection) c).getBackendPID() > 0);
            assertTrue(c.unwrap(PGConnection.class).getBackendPID() > 0);
            assertSame(c, s.getConnection());
            assertEquals("PostgreSQL", c.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void connectionRecoversAfterTheErrorInAutoCommit() throws SQLException {
        try (Connection c = shim(); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT ctid FROM " + DUCKLAKE_TABLE));
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + DUCKLAKE_TABLE)) {
                assertTrue(rs.next());
                assertEquals(3, rs.getLong(1));
                assertSame(c, rs.getStatement().getConnection());
            }
        }
    }
}
