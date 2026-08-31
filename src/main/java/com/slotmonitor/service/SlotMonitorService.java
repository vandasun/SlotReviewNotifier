package com.slotmonitor.service;

import com.slotmonitor.config.AppProperties;
import com.slotmonitor.model.MonitorStatus;
import com.slotmonitor.model.SlotResponse;
import com.slotmonitor.model.TimeSlot;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotMonitorService implements SchedulingConfigurer {

    private static final DateTimeFormatter LAST_CHECK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private final SchoolApiService schoolApiService;
    private final TelegramService telegramService;
    private final AppProperties properties;

    private final Set<String> lastSlots = new HashSet<>();
    private boolean firstCheck = true;
    private int errorCount;
    private int consecutiveErrors;
    private int checkCount;
    private volatile String lastCheck = "never";
    private volatile boolean monitoring = true;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        Duration interval = Duration.ofMillis(properties.getMonitor().checkIntervalMs());
        taskRegistrar.addFixedDelayTask(new IntervalTask(this::checkAndNotify, interval, Duration.ofSeconds(3)));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("============================================================");
        log.info("SLOT MONITOR BOT - RENDER WEB SERVICE");
        log.info("============================================================");
        log.info("Project ID: {}", properties.getSchool().getTaskId());
        log.info("Check interval: {} sec", properties.getMonitor().checkIntervalMs() / 1000);
        log.info("Looking {} days back, {} days forward",
                properties.getMonitor().getDaysBack(),
                properties.getMonitor().getDaysForward());

        telegramService.sendStartupMessage();

        if (!properties.hasSchoolAuth()) {
            log.error("TOKEN_ID or JSESSIONID is empty — monitoring will keep retrying, /ping stays available");
            telegramService.sendErrorNotification(
                    "Java bot started without school cookies.\nSet TOKEN_ID and JSESSIONID on this Render service (copy them from the Python service).");
        } else {
            log.info("Testing API connection...");
            Optional<SlotResponse> test = schoolApiService.getSlots();
            if (test.isEmpty()) {
                log.error("API test failed! Check TOKEN_ID and JSESSIONID");
                telegramService.sendErrorNotification(
                        "API test failed on startup!\nCheck TOKEN_ID and JSESSIONID in Environment Variables");
            } else {
                log.info("API test successful! Found {} slots", test.get().getStudentSlots().size());
            }
        }

        monitoring = true;
        log.info("Starting monitoring loop...");
    }

    public void checkAndNotify() {
        int currentCheck;
        synchronized (this) {
            checkCount++;
            currentCheck = checkCount;
            lastCheck = OffsetDateTime.now(ZoneOffset.UTC).format(LAST_CHECK_FORMAT);
        }
        log.info("Check #{}", currentCheck);

        try {
            Optional<SlotResponse> response = schoolApiService.getSlots();
            Runnable notification = applyCheckResult(response, currentCheck);
            if (notification != null) {
                notification.run();
            }
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            synchronized (this) {
                consecutiveErrors++;
            }
        }
    }

    private synchronized Runnable applyCheckResult(Optional<SlotResponse> response, int currentCheck) {
        if (response.isEmpty()) {
            consecutiveErrors++;
            errorCount++;
            log.warn("Failed to get slots (error #{}, consecutive: {})", errorCount, consecutiveErrors);

            if (consecutiveErrors >= 3) {
                consecutiveErrors = 0;
                int errors = errorCount;
                String taskId = properties.getSchool().getTaskId();
                return () -> telegramService.sendErrorNotification(
                        "Не удалось получить слоты для " + taskId + "\n"
                                + "Ошибка #" + errors + "\n"
                                + "Проверь токены в Environment Variables");
            }
            return null;
        }

        consecutiveErrors = 0;
        List<TimeSlot> studentSlots = response.get().getStudentSlots();
        Set<String> currentSlots = studentSlots.stream()
                .map(TimeSlot::getStart)
                .collect(Collectors.toSet());

        if (firstCheck) {
            lastSlots.clear();
            lastSlots.addAll(currentSlots);
            firstCheck = false;
            log.info("Initial state: {} slots found", currentSlots.size());
            if (!currentSlots.isEmpty()) {
                log.info("Found {} available slots on start", currentSlots.size());
                String taskId = properties.getSchool().getTaskId();
                return () -> telegramService.sendSlotNotification(taskId, studentSlots);
            }
            return null;
        }

        Set<String> newSlots = new HashSet<>(currentSlots);
        newSlots.removeAll(lastSlots);

        if (!newSlots.isEmpty()) {
            List<TimeSlot> newObjects = studentSlots.stream()
                    .filter(slot -> newSlots.contains(slot.getStart()))
                    .toList();
            log.info("NEW SLOTS FOUND: {}", newSlots.size());
            newObjects.stream()
                    .sorted((a, b) -> String.valueOf(a.getStart()).compareTo(String.valueOf(b.getStart())))
                    .limit(3)
                    .forEach(slot -> log.info("  {}", slot.getStart()));

            lastSlots.clear();
            lastSlots.addAll(currentSlots);
            String taskId = properties.getSchool().getTaskId();
            return () -> telegramService.sendSlotNotification(taskId, newObjects);
        }

        Set<String> removed = new HashSet<>(lastSlots);
        removed.removeAll(currentSlots);
        if (!removed.isEmpty()) {
            log.info("Slots removed: {}", removed.size());
            lastSlots.clear();
            lastSlots.addAll(currentSlots);
        }

        if (currentCheck % 10 == 0) {
            log.info("Status: {} known slots, {} errors", lastSlots.size(), errorCount);
        }
        return null;
    }

    public synchronized MonitorStatus getStatus() {
        return MonitorStatus.builder()
                .taskId(properties.getSchool().getTaskId())
                .checkCount(checkCount)
                .knownSlots(lastSlots.size())
                .errorCount(errorCount)
                .consecutiveErrors(consecutiveErrors)
                .monitoring(monitoring)
                .lastCheck(lastCheck)
                .build();
    }

    public synchronized int knownSlotCount() {
        return lastSlots.size();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down...");
        monitoring = false;
        telegramService.sendShutdownMessage();
    }
}
