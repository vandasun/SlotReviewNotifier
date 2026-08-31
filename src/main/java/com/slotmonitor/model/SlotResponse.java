package com.slotmonitor.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SlotResponse {

    private int checkDuration;

    @Builder.Default
    private List<TimeSlot> timeSlots = new ArrayList<>();

    @Builder.Default
    private int reviewByStudentCount = 0;

    @Builder.Default
    private int relevantReviewByStudentsCount = 0;

    @Builder.Default
    private int reviewByInspectionStaffCount = 0;

    @Builder.Default
    private int relevantReviewByInspectionStaffCount = 0;

    @Builder.Default
    private String p2pRequirementStatus = "";

    public List<TimeSlot> getStudentSlots() {
        return timeSlots.stream()
                .filter(TimeSlot::isStudentSlot)
                .toList();
    }

    public boolean hasAvailableSlots() {
        return !getStudentSlots().isEmpty();
    }
}
