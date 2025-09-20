package com.example.scheduler.repo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("db2")
public class Db2TaskPicker implements TaskPicker {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Optional<Long> lockOnePendingId() {
        // DB2: FETCH FIRST 1 ROWS ONLY + FOR UPDATE WITH RS SKIP LOCKED DATA
        String sql =
                "SELECT id " +
                        "FROM batch_task " +
                        "WHERE status='PENDING' AND not_before <= CURRENT TIMESTAMP " +
                        "ORDER BY priority DESC, id ASC " +
                        "FETCH FIRST 1 ROWS ONLY " +
                        "FOR UPDATE WITH RS SKIP LOCKED DATA";
        List<Number> ids = em.createNativeQuery(sql).getResultList();
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0).longValue());
    }

    @Override
    @Transactional
    public int markRunning(Long id, String owner) {
        String sql =
                "UPDATE batch_task " +
                        "SET status='RUNNING', owner=:owner, " +
                        "    heartbeat_at=CURRENT TIMESTAMP, updated_at=CURRENT TIMESTAMP " +
                        "WHERE id=:id";
        return em.createNativeQuery(sql)
                .setParameter("owner", owner)
                .setParameter("id", id)
                .executeUpdate();
    }
}