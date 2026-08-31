package com.slotmonitor.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @Valid
    private Telegram telegram = new Telegram();

    @Valid
    private School school = new School();

    @Valid
    private Monitor monitor = new Monitor();

    @Data
    public static class Telegram {
        @NotBlank(message = "TELEGRAM_BOT_TOKEN not set")
        private String botToken;

        @NotBlank(message = "TELEGRAM_CHAT_ID not set")
        private String chatId;
    }

    @Data
    public static class School {
        private String graphqlUrl = "https://platform.21-school.ru/services/graphql";

        private String organizationId = "b4b36a53-d253-4840-9000-49061d74bf50";

        @NotBlank(message = "TOKEN_ID not set")
        private String tokenId;

        @NotBlank(message = "JSESSIONID not set")
        private String jsessionid;

        private String taskId = "1361446";
    }

    @Data
    public static class Monitor {
        /**
         * Interval in milliseconds. Values below 1000 are treated as seconds
         * so a leftover Python env (CHECK_INTERVAL=30) still works.
         */
        @Min(1)
        private long checkInterval = 30_000;

        @Min(0)
        private int daysBack = 7;

        @Min(1)
        private int daysForward = 30;

        public long checkIntervalMs() {
            return checkInterval < 1_000 ? checkInterval * 1_000 : checkInterval;
        }
    }
}
