package io.github.guthman.ducklakeshim;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dynamic proxies over pgjdbc's Connection, Statement and ResultSet objects.
 *
 * <p>The proxies implement every public interface of the pgjdbc object, so casts to
 * {@code org.postgresql.PGConnection} and friends keep working.
 */
final class Shim {

    /** What pg_ducklake returns when DataGrip appends {@code ctid} to a table query. */
    static final String DUCKDB_CTID_ERROR = "Referenced column \"ctid\" not found";

    /**
     * Postgres' own wording, which DataGrip's {@code DatabaseTableGridDataHookUp.isRowIdError} already
     * treats as "no row id here, reload without ctid".
     */
    static final String DATAGRIP_ROWID_MARKER = "column \"ctid\" does not exist";

    private Shim() {
    }

    static Connection wrapConnection(Connection connection) {
        return (Connection) proxy(connection, null);
    }

    /**
     * Appends the marker to the pg_ducklake ctid error; returns every other exception unchanged.
     * The original exception becomes the cause, so its ServerErrorMessage stays reachable.
     */
    static SQLException translate(SQLException e) {
        String message = e.getMessage();
        if (message == null
                || e instanceof BatchUpdateException
                || !message.contains(DUCKDB_CTID_ERROR)
                || message.contains(DATAGRIP_ROWID_MARKER)) {
            return e;
        }
        SQLException translated = new SQLException(
                message.trim() + " [" + DATAGRIP_ROWID_MARKER + "]", e.getSQLState(), e.getErrorCode(), e);
        translated.setNextException(e.getNextException());
        return translated;
    }

    private static Object proxy(Object target, Connection connectionProxy) {
        Class<?>[] interfaces = publicInterfaces(target.getClass());
        Handler handler = new Handler(target, connectionProxy);
        Object proxy = Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, handler);
        if (connectionProxy == null) {
            handler.connectionProxy = (Connection) proxy;
        }
        return proxy;
    }

    private static Class<?>[] publicInterfaces(Class<?> type) {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            collect(c.getInterfaces(), result);
        }
        return result.toArray(new Class<?>[0]);
    }

    private static void collect(Class<?>[] interfaces, Set<Class<?>> result) {
        for (Class<?> i : interfaces) {
            if (Modifier.isPublic(i.getModifiers()) && result.add(i)) {
                collect(i.getInterfaces(), result);
            }
        }
    }

    private static final class Handler implements InvocationHandler {
        private final Object target;
        private Connection connectionProxy;

        Handler(Object target, Connection connectionProxy) {
            this.target = target;
            this.connectionProxy = connectionProxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return method.invoke(target, args);
                }
            }

            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause instanceof SQLException ? translate((SQLException) cause) : cause;
            }

            // unwrap() is the caller asking for the raw pgjdbc object; the proxy may not satisfy the cast.
            if (result == null || method.getName().equals("unwrap")) {
                return result;
            }
            if (result == target) {
                return proxy;
            }
            if (result instanceof Connection) {
                return connectionProxy;
            }
            if (result instanceof Statement || result instanceof ResultSet || result instanceof DatabaseMetaData) {
                return Shim.proxy(result, connectionProxy);
            }
            return result;
        }
    }
}
