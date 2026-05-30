package bt.rub.gcit.placementservice.entity;

import java.time.LocalDateTime;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@Document(collection = "users")
public class User {

    @JsonIgnore
    private String password;

    @JsonIgnore
    private String otp;

    @Id
    private String id;
    private String indexNumber;
    private String cid;
    private String fullName;
    private String email;
    private Object academicMarks;
    private boolean isVerified = false;
    private Role role;

    private String profileImageUrl = "";
    private boolean isPlaced = false;
    private LocalDateTime placementDate;

    // for admin
    private String collegeName;
    private String websiteUrl;
    private String contactInfo;

    private String passwordToken;

    public enum Role {
        student,
        admin,
        superadmin
    }
}
