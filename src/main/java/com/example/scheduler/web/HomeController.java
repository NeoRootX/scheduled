package com.example.scheduler.web;

import com.example.scheduler.domain.BatchSchedule;
import com.example.scheduler.domain.BatchTask;
import com.example.scheduler.repo.ScheduleRepo;
import com.example.scheduler.repo.TaskRepo;
import com.example.scheduler.service.TaskRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {
    private final ScheduleRepo scheduleRepo;
    private final TaskRepo taskRepo;
    private final List<TaskRunner> runners;
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/")
    public String home(Model model,
                       @RequestParam(value = "ok", required = false) Boolean ok,
                       @RequestParam(value = "type", required = false) String lastType,
                       @RequestParam(value = "payload", required = false) String lastPayload,
                       @RequestParam(value = "cost", required = false) Long costMs,
                       @RequestParam(value = "error", required = false) String error) {
        model.addAttribute("schedules", scheduleRepo.findAll());
        model.addAttribute("tasks", taskRepo.findAll());
        model.addAttribute("runners", runners);
        if (ok != null) {
            model.addAttribute("ok", ok);
            model.addAttribute("lastType", lastType);
            model.addAttribute("lastPayload", lastPayload);
            model.addAttribute("costMs", costMs);
            model.addAttribute("error", error);
        }
        return "home";
    }

    @PostMapping("/manual/run")
    public String manualRun(@RequestParam String type,
                            @RequestParam(required = false) String payload) {
        TaskRunner runner = runners.stream()
                .filter(r -> r.type().equals(type))
                .findFirst()
                .orElse(null);

        String json = (payload == null || payload.trim().isEmpty()) ? "{}" : payload.trim();

        long start = System.currentTimeMillis();
        String error = null;

        if (runner == null) {
            error = "IllegalArgumentException: No runner for type=" + type;
        } else {
            com.fasterxml.jackson.databind.JsonNode node = null;
            try {
                node = mapper.readTree(json);
            } catch (Exception e) {
                error = "BadPayload: " + safeMsg(e.getMessage());
            }

            if (error == null) {
                try {
                    runner.run(node);
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + safeMsg(e.getMessage());
                }
            }
        }

        long cost = System.currentTimeMillis() - start;
        String redirect = "/?ok=" + (error == null)
                + "&type=" + urlEncode(type)
                + "&payload=" + urlEncode(json)
                + "&cost=" + cost
                + (error == null ? "" : "&error=" + urlEncode(error));
        return "redirect:" + redirect;
    }

    @PostMapping("/schedules")
    public String create(@ModelAttribute @Validated BatchSchedule s) {
        String json = (s.getPayload() == null || s.getPayload().trim().isEmpty()) ? "{}" : s.getPayload().trim();
        try {
            mapper.readTree(json);
        } catch (Exception e) {
            return "redirect:/?ok=false&error=" + urlEncode("BadPayload in schedule: " + safeMsg(e.getMessage()));
        }
        s.setPayload(json);
        scheduleRepo.save(s);
        return "redirect:/?ok=true";
    }

    @PostMapping("/tasks/enqueue")
    public String enqueue(@RequestParam String type,
                          @RequestParam(required = false) String payload) {
        String json = (payload == null || payload.trim().isEmpty()) ? "{}" : payload.trim();

        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            String redirect = "/?ok=false"
                    + "&type=" + urlEncode(type)
                    + "&payload=" + urlEncode(json)
                    + "&error=" + urlEncode("BadPayload: " + safeMsg(e.getMessage()));
            return "redirect:" + redirect;
        }

        BatchTask t = new BatchTask();
        t.setType(type);
        t.setPayload(json);
        t.setStatus("PENDING");
        taskRepo.save(t);
        return "redirect:/?ok=true&type=" + urlEncode(type) + "&payload=" + urlEncode(json);
    }

    private static String safeMsg(String msg) {
        if (msg == null) return "";
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}