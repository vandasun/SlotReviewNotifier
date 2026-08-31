package com.slotmonitor.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copies Render/OS env vars onto app.* properties before {@code @ConfigurationProperties} bind.
 * Runs even if YAML placeholders resolve to empty strings.
 */
public class SchoolEnvPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> overlay = new LinkedHashMap<>();
        copy(environment, overlay, "app.telegram.bot-token", "TELEGRAM_BOT_TOKEN");
        copy(environment, overlay, "app.telegram.chat-id", "TELEGRAM_CHAT_ID");
        copy(environment, overlay, "app.school.token-id", "TOKEN_ID", "SCHOOL_TOKEN_ID");
        copy(environment, overlay, "app.school.jsessionid", "JSESSIONID", "SCHOOL_JSESSIONID");
        copy(environment, overlay, "app.school.organization-id", "ORGANIZATION_ID");
        copy(environment, overlay, "app.school.task-id", "TASK_ID");
        if (!overlay.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("render-env-overlay", overlay));
        }
    }

    private void copy(ConfigurableEnvironment environment, Map<String, Object> overlay, String target, String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (!StringUtils.hasText(value)) {
                value = System.getenv(key);
            }
            if (StringUtils.hasText(value)) {
                overlay.put(target, value.trim());
                return;
            }
        }
    }
}
