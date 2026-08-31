package com.slotmonitor.controller;

import com.slotmonitor.config.AppProperties;
import com.slotmonitor.model.MonitorStatus;
import com.slotmonitor.service.SlotMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final SlotMonitorService slotMonitorService;
    private final AppProperties properties;

    @GetMapping("/")
    public Map<String, Object> health() {
        return Map.of(
                "status", "running",
                "service", "Slot Monitor Bot",
                "task_id", properties.getSchool().getTaskId(),
                "slots_known", slotMonitorService.knownSlotCount()
        );
    }

    @GetMapping("/status")
    public MonitorStatus status() {
        return slotMonitorService.getStatus();
    }

    @GetMapping(value = "/ping", produces = MediaType.TEXT_PLAIN_VALUE)
    public String ping() {
        return "pong";
    }
}
