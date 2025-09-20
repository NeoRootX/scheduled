package com.example.scheduler.service;

import com.example.scheduler.domain.BatchRun;
import com.example.scheduler.domain.BatchTask;
import com.example.scheduler.repo.TaskPicker;
import com.example.scheduler.repo.TaskRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEngine {

    private final TaskRepo taskRepo;
    private final TaskPicker picker;

    @PersistenceContext
    private EntityManager em; // 不要 final，便于容器注入

    private final ObjectMapper mapper; // 由 Spring 注入，便于统一配置

    private final List<TaskRunner> autoRunners;
    private final Map<String, TaskRunner> runners = new ConcurrentHashMap<>();

    /**
     * 供外部（Registrar）或测试注册 Runner
     */
    public void register(TaskRunner r) {
        if (r == null) return;
        String key = r.type();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("TaskRunner.type() must not be empty");
        }
        runners.put(key, r);
        log.info("Runner registered: {}", key);
    }

    @PostConstruct
    public void autoRegister() {
        for (TaskRunner r : autoRunners) {
            // 复用校验逻辑
            register(r);
        }
        log.info("All runners: {}", runners.keySet());
    }

    /**
     * A. 原子领取（短事务 / 新事务）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected Optional<BatchTask> claimOne() {
        Optional<Long> idOpt = picker.lockOnePendingId();
        if (!idOpt.isPresent()) return Optional.empty();
        Long id = idOpt.get();
        int ok = picker.markRunning(id, owner());
        if (ok == 0) {
            // 被其他实例抢走
            return Optional.empty();
        }
        BatchTask task = em.find(BatchTask.class, id);
        return Optional.ofNullable(task);
    }

    /**
     * B. 对外入口：领取 → 执行业务（无事务）→ 完成回写（新事务）
     */
    public void pollAndRunOnce() {
        Optional<BatchTask> opt = claimOne();
        if (!opt.isPresent()) return;

        BatchTask task = opt.get();

        // 新建 run 记录（短事务写入 started_at 与 RUNNING）
        BatchRun run = createRun(task.getId());

        // —— 执行业务（不在事务里）——
        boolean succeed = false;
        String errMsg = null;
        Timestamp finish = tsNow();

        try {
            TaskRunner r = runners.get(task.getType());
            if (r == null) throw new IllegalStateException("No runner for type=" + task.getType());

            String payload = safePayload(task.getPayload());
            r.run(mapper.readTree(payload));
            succeed = true;
        } catch (Exception e) {
            log.error("Task failed id={}", task.getId(), e);
            errMsg = e.getMessage();
            if (errMsg != null && errMsg.length() > 1900) {
                errMsg = errMsg.substring(0, 1900);
            }
        }

        // —— 回写（短事务 / 新事务）——
        complete(task.getId(), run.getId(), succeed, errMsg, finish);
    }

    /**
     * 新建 run 记录（短事务）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected BatchRun createRun(Long taskId) {
        BatchRun run = new BatchRun();
        run.setTaskId(taskId);
        run.setStatus("RUNNING");
        run.setStartedAt(tsNow());
        em.persist(run);
        return run;
    }

    /**
     * 完成回写（短事务）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void complete(Long taskId, Long runId, boolean succeed, String message, Timestamp finishAt) {
        BatchTask task = em.find(BatchTask.class, taskId);
        if (task == null) {
            log.warn("Task not found when completing, id={}", taskId);
            return;
        }
        BatchRun run = em.find(BatchRun.class, runId);
        if (run == null) {
            run = new BatchRun();
            run.setTaskId(taskId);
            run.setStartedAt(tsNow());
        }

        task.setStatus(succeed ? "SUCCEED" : "FAILED");
        task.setMessage(message);
        task.setFinishAt(finishAt);
        Timestamp now = tsNow();
        if (task.getCreatedAt() == null) task.setCreatedAt(now);
        task.setUpdatedAt(now);

        run.setStatus(succeed ? "SUCCEED" : "FAILED");
        run.setEndedAt(finishAt);
        run.setMessage(message);

        em.merge(run);
        taskRepo.save(task);
    }

    private static String owner() {
        long pid = ProcessHandle.current().pid();
        return "local#" + pid;
    }

    private static Timestamp tsNow() {
        return Timestamp.from(Instant.now());
    }

    private static String safePayload(String payload) {
        return (payload == null || payload.trim().isEmpty()) ? "{}" : payload;
    }
}