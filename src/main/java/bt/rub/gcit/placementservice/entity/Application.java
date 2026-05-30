package bt.rub.gcit.placementservice.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "applications")
public class Application {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SHORTLISTED = "shortlisted";
    public static final String STATUS_PASSED_CTT = "passed_ctt";
    public static final String STATUS_PASSED_INTERVIEW = "passed_interview";
    public static final String STATUS_PLACED = "placed";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CLOSED_PLACED_ELSEWHERE = "closed_candidate_placed_elsewhere";

    @Id
    private String id;

    private User student;
    private Course course;

    private String applicationType; // "higher-education", "self-financed"
    private Double totalMeritScore;
    private Map<String, Double> meritRankingBreakdown;

    // Kept for compatibility with existing frontend and existing records.
    private String status = STATUS_PENDING;
    private String currentStatus = STATUS_PENDING;
    private List<StatusHistory> statusHistory = new ArrayList<>();

    private boolean isPlaced = false;
    private LocalDateTime placementDate;

    @CreatedDate
    private LocalDateTime submittedAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StatusHistory {
        private String fromStatus;
        private String toStatus;
        private LocalDateTime changedAt;
        private String changedBy;
        private String changedByRole;
        private String note;
    }
}
