package com.example.scheduler.config;

import com.example.scheduler.service.TaskEngine;
import com.example.scheduler.service.TaskRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class RunnerRegistrar {
    private final TaskEngine engine;
    private final Map<String, TaskRunner> runners;

    @PostConstruct
    public void init() {
        for (TaskRunner r : runners.values()) {
            engine.register(r);
        }
    }
}
