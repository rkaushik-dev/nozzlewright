package io.github.rkaushikdev.nozzlewright.storage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

/**
 * One conversation with the database: a set of rows read and written under one transaction.
 *
 * <p>A transaction is always open. {@link #commit} makes the work so far permanent and starts the
 * next transaction; {@link #rollback} throws it away, forgets every row read, and starts again;
 * {@link #close} throws away anything not yet committed. Rows read here stay readable after it
 * closes. Every repository built on one unit of work shares it, so what one wrote the others see.
 */
public final class UnitOfWork implements AutoCloseable {

    private final EntityManager entities;
    private final EntityTransaction transaction;

    UnitOfWork(EntityManagerFactory factory) {
        this.entities = factory.createEntityManager();
        this.transaction = entities.getTransaction();
        transaction.begin();
    }

    /** The entity manager underneath, for queries. */
    public EntityManager entities() {
        return entities;
    }

    /**
     * Add a new row and write it now, so its id is known.
     *
     * @throws jakarta.persistence.PersistenceException when a constraint refuses it
     */
    public <T> T add(T row) {
        entities.persist(row);
        return row;
    }

    /** Write every pending change now. */
    public void flush() {
        entities.flush();
    }

    /** Delete a row read through this unit of work. */
    public void delete(Object row) {
        entities.remove(row);
    }

    /** Make the work so far permanent and start a fresh transaction. */
    public void commit() {
        transaction.commit();
        transaction.begin();
    }

    /** Throw the work so far away, forget every row read, and start a fresh transaction. */
    public void rollback() {
        if (transaction.isActive()) {
            transaction.rollback();
        }
        entities.clear();
        transaction.begin();
    }

    /** Throw away anything not committed and release the connection. */
    @Override
    public void close() {
        if (transaction.isActive()) {
            transaction.rollback();
        }
        entities.close();
    }
}
