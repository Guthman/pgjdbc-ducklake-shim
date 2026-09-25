package io.github.guthman.ducklakeshim;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirror of DataGrip's table editor behaviour for a Postgres data source (DB-262.10315.132).
 *
 * <ul>
 *   <li>{@code DatabaseGridDataHookUp$DatabaseLoader.needRowId}: a TABLE without a primary key gets
 *       {@code ctid} appended to its select list.</li>
 *   <li>{@code GridUtilCore.getLongMessage}: the grid sees {@code "[" + sqlState + "] "} followed by
 *       the trimmed {@code getMessage()}.</li>
 *   <li>{@code DatabaseTableGridDataHookUp.isRowIdError}: plain substring checks on that text; on a
 *       hit, {@code onProcessed} reloads the page without the row id.</li>
 * </ul>
 */
final class DataGrip {

    private static final List<String> POSTGRES_ROWID_ERRORS = List.of(
            "Column 'ctid' cannot be resolved",
            "column \"ctid\" does not exist",
            "System column \"ctid\" is not supported yet",
            "UPDATE and CTID scans not supported",
            "transparent decompression only supports tableoid system column",
            "System column with id -1 is not supported yet");

    private DataGrip() {
    }

    static String gridMessage(SQLException e) {
        String state = e.getSQLState();
        String prefix = state == null || state.isEmpty() || state.equals("00000") ? "" : "[" + state + "] ";
        String message = e.getMessage();
        return prefix + (message == null || message.isEmpty() ? e.getClass().getName() : message.trim());
    }

    static boolean isRowIdError(SQLException e) {
        String message = gridMessage(e);
        return POSTGRES_ROWID_ERRORS.stream().anyMatch(message::contains);
    }

    /** Opens a primary-key-less table the way the table editor does; returns the first page. */
    static List<List<Object>> openTable(Connection connection, String table) throws SQLException {
        try {
            return firstPage(connection, "SELECT t.*, CTID FROM " + table + " t LIMIT 501", true);
        } catch (SQLException e) {
            if (!isRowIdError(e)) {
                throw e;
            }
            return firstPage(connection, "SELECT t.* FROM " + table + " t LIMIT 501", false);
        }
    }

    private static List<List<Object>> firstPage(Connection connection, String sql, boolean dropLastColumn)
            throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount() - (dropLastColumn ? 1 : 0);
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
