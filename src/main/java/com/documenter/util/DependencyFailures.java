package com.documenter.util;

/** Dependency outages must not be presented as invalid credentials. */
public final class DependencyFailures {
    private DependencyFailures() {}
    public static boolean unavailable(Throwable failure) {
        for (int depth=0; failure!=null && depth<32; depth++, failure=failure.getCause()) {
            if (failure instanceof org.springframework.dao.DataAccessResourceFailureException
                    || failure instanceof org.springframework.dao.QueryTimeoutException
                    || failure instanceof java.sql.SQLException sql && sql.getSQLState()!=null
                    && sql.getSQLState().startsWith("08")) return true;
        }
        return false;
    }
}
