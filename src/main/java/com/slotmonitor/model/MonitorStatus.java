package com.slotmonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MonitorStatus {

    @JsonProperty("task_id")
    String taskId;

    @JsonProperty("check_count")
    int checkCount;

    @JsonProperty("known_slots")
    int knownSlots;

    @JsonProperty("error_count")
    int errorCount;

    @JsonProperty("consecutive_errors")
    int consecutiveErrors;

    @JsonProperty("is_monitoring")
    boolean monitoring;

    @JsonProperty("last_check")
    String lastCheck;
}
