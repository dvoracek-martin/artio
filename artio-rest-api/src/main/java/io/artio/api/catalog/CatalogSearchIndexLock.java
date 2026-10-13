package io.artio.api.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Coordinates catalog writes with a full index replay across every API instance. */
@Component
public class CatalogSearchIndexLock {
    static final int LOCK_NAMESPACE = 0x41525449;
    static final int LOCK_ID = 1;
    private final JdbcTemplate jdbc;

    public CatalogSearchIndexLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Call inside a write transaction before mutating product or translation rows. */
    public void lockCatalogWrite() {
        acquire("pg_advisory_xact_lock_shared");
    }

    /** Call inside the replay transaction before reading the catalog snapshot. */
    public void lockFullRebuild() {
        acquire("pg_advisory_xact_lock");
    }

    private void acquire(String function) {
        jdbc.query("SELECT " + function + "(?, ?)", rs -> {
            rs.next();
            return null;
        }, LOCK_NAMESPACE, LOCK_ID);
    }
}
