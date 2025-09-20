package com.example.scheduler.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PollScheduler {
    private final TaskEngine engine;

    @Scheduled(fixedDelay = 2000L, initialDelay = 3000L)
    public void tick() {
        engine.pollAndRunOnce();
    }
}