package com.example.scheduler.repo;

import com.example.scheduler.domain.BatchTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public interface TaskRepo extends JpaRepository<BatchTask, Long> {
    Optional<BatchTask> findTopByStatusAndNotBeforeLessThanEqualOrderByPriorityDescIdAsc(String status, Timestamp notBefore);

    @Modifying
    @Query(value = "INSERT INTO batch_task(ticket_no, type, payload, priority, status, attempts, max_attempts, not_before) " + "SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8 WHERE NOT EXISTS (SELECT 1 FROM batch_task WHERE ticket_no=?1)", nativeQuery = true)
    int insertIfNotExists(String ticketNo, String type, String payload, int priority, String status, int attempts, int maxAttempts, Timestamp notBefore);
}