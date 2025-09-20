package com.example.scheduler.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface TaskRunner {
    String type();

    void run(JsonNode payload) throws Exception;
}