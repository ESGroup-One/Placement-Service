package bt.rub.gcit.placementservice.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "applications")
public class Application {

    @Id
    private String id;
    private User student;
    private Course course;

    private String applicationType; // "higher-education", "self-financed"
    private Double totalMeritScore;
    private Map<String, Double> meritRankingBreakdown;
    private String status = "pending"; // "pending", "shortlisted", "rejected"

    @CreatedDate
    private LocalDateTime submittedAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}