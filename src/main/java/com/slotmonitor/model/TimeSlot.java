package com.slotmonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TimeSlot {

    private String start = "";
    private String end = "";

    @JsonProperty("validStartTimes")
    private List<String> validStartTimes = new ArrayList<>();

    @JsonProperty("staffSlot")
    private boolean staffSlot;

    public boolean isStudentSlot() {
        return !staffSlot;
    }
}
