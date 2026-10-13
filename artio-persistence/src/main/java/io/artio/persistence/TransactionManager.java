package io.artio.persistence;

/** Port for explicit transaction boundaries when a unit of work is not sufficient. */
public interface TransactionManager {
    Transaction begin();

    interface Transaction extends AutoCloseable {
        void commit();

        void rollback();

        @Override
        void close();
    }
}