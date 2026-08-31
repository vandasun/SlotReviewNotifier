package com.slotmonitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Telegram telegram = new Telegram();
    private School school = new School();
    private Monitor monitor = new Monitor();

    public boolean hasTelegram() {
        return StringUtils.hasText(telegram.getBotToken()) && StringUtils.hasText(telegram.getChatId());
    }

    public boolean hasSchoolAuth() {
        return StringUtils.hasText(school.getTokenId()) && StringUtils.hasText(school.getJsessionid());
    }

    @Data
    public static class Telegram {
        private String botToken = "";
        private String chatId = "";
    }

    @Data
    public static class School {
        private String graphqlUrl = "https://platform.21-school.ru/services/graphql";
        private String organizationId = "b4b36a53-d253-4840-9000-49061d74bf50";
        private String tokenId = "";
        private String jsessionid = "";
        private String taskId = "1361446";
    }

    @Data
    public static class Monitor {
        /**
         * Interval in milliseconds. Values below 1000 are treated as seconds
         * so a leftover Python env (CHECK_INTERVAL=30) still works.
         */
        private long checkInterval = 30_000;
        private int daysBack = 7;
        private int daysForward = 30;

        public long checkIntervalMs() {
            return checkInterval < 1_000 ? checkInterval * 1000 : checkInterval;
        }
    }
}
