package com.slotmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmonitor.config.AppProperties;
import com.slotmonitor.model.SlotResponse;
import com.slotmonitor.model.TimeSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolApiService {

    private static final DateTimeFormatter FROM_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000'Z'");
    private static final DateTimeFormatter TO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.999'Z'");

    private static final String GRAPHQL_QUERY = """
            query calendarGetNameLessStudentTimeslotsForReview($from: DateTime!, $taskId: ID!, $to: DateTime!) {
              student {
                getNameLessStudentTimeslotsForReview(from: $from, taskId: $taskId, to: $to) {
                  checkDuration
                  projectReviewsInfo {
                    ...ProjectReviewsInfo
                    __typename
                  }
                  timeSlots {
                    ...CalendarNameLessTimeslot
                    __typename
                  }
                  __typename
                }
                __typename
              }
            }

            fragment ProjectReviewsInfo on ProjectReviewsInfo {
              reviewByStudentCount
              relevantReviewByStudentsCount
              reviewByInspectionStaffCount
              relevantReviewByInspectionStaffCount
              p2pRequirementStatus
              __typename
            }

            fragment CalendarNameLessTimeslot on CalendarNamelessTimeSlot {
              start
              end
              validStartTimes
              staffSlot
              __typename
            }
            """;

    private final WebClient webClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public Optional<SlotResponse> getSlots() {
        return getSlots(properties.getSchool().getTaskId());
    }

    public Optional<SlotResponse> getSlots(String taskId) {
        AppProperties.School school = properties.getSchool();
        AppProperties.Monitor monitor = properties.getMonitor();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String from = now.minusDays(monitor.getDaysBack()).format(FROM_FORMAT);
        String to = now.plusDays(monitor.getDaysForward()).format(TO_FORMAT);

        Map<String, Object> payload = Map.of(
                "operationName", "calendarGetNameLessStudentTimeslotsForReview",
                "query", GRAPHQL_QUERY,
                "variables", Map.of(
                        "taskId", taskId,
                        "from", from,
                        "to", to
                )
        );

        String cookie = "tokenId=" + school.getTokenId() + "; JSESSIONID=" + school.getJsessionid();

        try {
            JsonNode data = webClient.post()
                    .uri(school.getGraphqlUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("schoolid", school.getOrganizationId())
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .bodyValue(payload)
                    .exchangeToMono(response -> {
                        if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(JsonNode.class);
                        }
                        List<String> badRequest = response.headers().header("x-bad-request");
                        return response.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> {
                            log.error("HTTP {}", response.statusCode().value());
                            if (!body.isBlank()) {
                                log.error("Response: {}", body.length() > 500 ? body.substring(0, 500) : body);
                            }
                            if (!badRequest.isEmpty()) {
                                log.error("x-bad-request: {}", badRequest.get(0));
                            }
                            return Mono.empty();
                        });
                    })
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (data == null) {
                return Optional.empty();
            }

            if (data.has("errors")) {
                log.error("GraphQL errors: {}", data.get("errors").toPrettyString());
                return Optional.empty();
            }

            return Optional.of(parseResponse(data));
        } catch (Exception e) {
            log.error("Request error: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private SlotResponse parseResponse(JsonNode root) {
        JsonNode slotsData = root.path("data")
                .path("student")
                .path("getNameLessStudentTimeslotsForReview");

        List<TimeSlot> timeSlots = new ArrayList<>();
        JsonNode slotsNode = slotsData.path("timeSlots");
        if (slotsNode.isArray()) {
            for (JsonNode slotNode : slotsNode) {
                timeSlots.add(objectMapper.convertValue(slotNode, TimeSlot.class));
            }
        }

        JsonNode projectInfo = slotsData.path("projectReviewsInfo");

        return SlotResponse.builder()
                .checkDuration(slotsData.path("checkDuration").asInt(30))
                .timeSlots(timeSlots)
                .reviewByStudentCount(projectInfo.path("reviewByStudentCount").asInt(0))
                .relevantReviewByStudentsCount(projectInfo.path("relevantReviewByStudentsCount").asInt(0))
                .reviewByInspectionStaffCount(projectInfo.path("reviewByInspectionStaffCount").asInt(0))
                .relevantReviewByInspectionStaffCount(projectInfo.path("relevantReviewByInspectionStaffCount").asInt(0))
                .p2pRequirementStatus(projectInfo.path("p2pRequirementStatus").asText(""))
                .build();
    }
}
