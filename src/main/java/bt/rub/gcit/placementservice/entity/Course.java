package bt.rub.gcit.placementservice.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "courses")
public class Course {

    @Id
    private String id;

    private String title;
    private String description;
    private Map<String, Object> eligibility_criteria;
    private Map<String, Object> merit_ranking;
    private LocalDateTime application_dateline;
    private Integer gov_seats;
    private Integer self_finance_seats;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private CollegeInfo college;
    @Data
    public static class CollegeInfo {
        private String id;
        private String collegeName;
        private String websiteUrl;
        private String contactInfo;
        private String fullName;
        private String email;
        private String profileImageUrl;
    }
}
