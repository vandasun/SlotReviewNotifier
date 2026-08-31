package com.slotmonitor.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnvSecretsBinder {

    private final AppProperties properties;
    private final Environment environment;

    @PostConstruct
    public void overlayFromEnvironment() {
        AppProperties.Telegram telegram = properties.getTelegram();
        telegram.setBotToken(firstNonBlank(telegram.getBotToken(),
                "TELEGRAM_BOT_TOKEN", "telegram.bot-token", "app.telegram.bot-token"));
        telegram.setChatId(firstNonBlank(telegram.getChatId(),
                "TELEGRAM_CHAT_ID", "telegram.chat-id", "app.telegram.chat-id"));

        AppProperties.School school = properties.getSchool();
        school.setTokenId(firstNonBlank(school.getTokenId(),
                "TOKEN_ID", "SCHOOL_TOKEN_ID", "token.id", "app.school.token-id"));
        school.setJsessionid(firstNonBlank(school.getJsessionid(),
                "JSESSIONID", "SCHOOL_JSESSIONID", "jsessionid", "app.school.jsessionid"));

        log.info("Env keys present: {}", relevantEnvKeys());
        log.info("Config: TELEGRAM_BOT_TOKEN {}", describe(telegram.getBotToken()));
        log.info("Config: TELEGRAM_CHAT_ID {}", describe(telegram.getChatId()));
        log.info("Config: TOKEN_ID {}", describe(school.getTokenId()));
        log.info("Config: JSESSIONID {}", describe(school.getJsessionid()));

        if (!properties.hasSchoolAuth()) {
            log.error("School cookies are missing. Set TOKEN_ID and JSESSIONID (or SCHOOL_TOKEN_ID / SCHOOL_JSESSIONID) on the Render service. Health endpoints stay up.");
        }
    }

    private String firstNonBlank(String current, String... keys) {
        if (StringUtils.hasText(current)) {
            return current.trim();
        }
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (!StringUtils.hasText(value)) {
                value = System.getenv(key);
            }
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return current == null ? "" : current;
    }

    private String describe(String value) {
        if (!StringUtils.hasText(value)) {
            return "MISSING";
        }
        return "set (" + value.length() + " chars)";
    }

    private String relevantEnvKeys() {
        String keys = System.getenv().keySet().stream()
                .filter(key -> {
                    String upper = key.toUpperCase(Locale.ROOT);
                    return upper.contains("TOKEN")
                            || upper.contains("SESSION")
                            || upper.contains("TELEGRAM")
                            || upper.contains("SCHOOL")
                            || upper.contains("TASK")
                            || upper.equals("PORT")
                            || upper.equals("JAVA_OPTS");
                })
                .sorted()
                .collect(Collectors.joining(", "));
        return keys.isEmpty() ? "(none matched)" : keys;
    }
}
