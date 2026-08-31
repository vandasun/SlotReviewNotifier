package com.slotmonitor.service;

import com.slotmonitor.config.AppProperties;
import com.slotmonitor.model.TimeSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramService {

    private static final DateTimeFormatter LOCAL_SLOT_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd.MM");
    private static final long ERROR_COOLDOWN_MS = 300_000L;

    private final WebClient webClient;
    private final AppProperties properties;
    private final AtomicLong lastErrorSent = new AtomicLong(0);

    public boolean sendMessage(String text) {
        String token = properties.getTelegram().getBotToken();
        String chatId = properties.getTelegram().getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.error("Telegram config missing!");
            return false;
        }

        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        try {
            String body = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "chat_id", chatId,
                            "text", text,
                            "parse_mode", "Markdown"
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            log.info("Message sent to Telegram");
            return body != null;
        } catch (Exception e) {
            log.error("Telegram error: {}", e.getMessage());
            return false;
        }
    }

    public boolean sendStartupMessage() {
        long intervalSec = properties.getMonitor().checkIntervalMs() / 1000;
        String text = "🔄 *Мониторинг запущен на Render*\n\n"
                + "🆔 Проект: `" + properties.getSchool().getTaskId() + "`\n"
                + "⏱ Проверка каждые " + intervalSec + " сек\n"
                + "🌐 Render Worker активен";
        return sendMessage(text);
    }

    public boolean sendSlotNotification(String taskId, List<TimeSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return false;
        }

        List<TimeSlot> sorted = slots.stream()
                .sorted(Comparator.comparing(TimeSlot::getStart, Comparator.nullsLast(String::compareTo)))
                .toList();

        StringBuilder text = new StringBuilder();
        text.append("🔔 *НОВЫЙ СЛОТ ДОСТУПЕН!*\n\n");
        text.append("🆔 Проект: `").append(taskId).append("`\n\n");
        text.append("🕐 *Доступные слоты:*\n");

        int limit = Math.min(10, sorted.size());
        for (int i = 0; i < limit; i++) {
            TimeSlot slot = sorted.get(i);
            text.append("• `").append(slot.getStart()).append("` → ")
                    .append(formatLocal(slot.getStart())).append("\n");
        }

        if (sorted.size() > 10) {
            text.append("\n... и еще ").append(sorted.size() - 10).append(" слотов");
        }

        text.append("\n\n📝 Запишись скорее!");
        return sendMessage(text.toString());
    }

    public boolean sendErrorNotification(String errorMessage) {
        long now = System.currentTimeMillis();
        long previous = lastErrorSent.get();
        if (now - previous < ERROR_COOLDOWN_MS) {
            return false;
        }
        if (!lastErrorSent.compareAndSet(previous, now)) {
            return false;
        }

        String truncated = errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
        return sendMessage("⚠️ *Ошибка мониторинга*\n\n" + truncated);
    }

    public boolean sendShutdownMessage() {
        return sendMessage("🛑 *Мониторинг остановлен*");
    }

    private String formatLocal(String start) {
        try {
            OffsetDateTime dt = OffsetDateTime.parse(start);
            return dt.atZoneSameInstant(ZoneId.systemDefault()).format(LOCAL_SLOT_FORMAT);
        } catch (Exception e) {
            return start;
        }
    }
}
