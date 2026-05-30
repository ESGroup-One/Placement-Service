package bt.rub.gcit.placementservice.service;

import bt.rub.gcit.placementservice.dao.ApplicationRepository;
import bt.rub.gcit.placementservice.dao.CourseRepository;
import bt.rub.gcit.placementservice.dao.UserRepository;
import bt.rub.gcit.placementservice.entity.Application;
import bt.rub.gcit.placementservice.entity.Course;
import bt.rub.gcit.placementservice.entity.User;
import bt.rub.gcit.placementservice.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ApplicationService {

    private static final List<String> ORDERED_WORKFLOW = List.of(
            Application.STATUS_PENDING,
            Application.STATUS_SHORTLISTED,
            Application.STATUS_PASSED_CTT,
            Application.STATUS_PASSED_INTERVIEW,
            Application.STATUS_PLACED);

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            Application.STATUS_PLACED,
            Application.STATUS_REJECTED,
            Application.STATUS_CLOSED_PLACED_ELSEWHERE);

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StatusEmailService statusEmailService;

    private Double getStudentMark(Map<String, Object> marks, String subject) {
        String lower = subject.toLowerCase();
        if (marks.containsKey(lower)) {
            return Double.parseDouble(marks.get(lower).toString());
        }

        if (lower.equals("mathematics") && marks.containsKey("math")) {
            return Double.parseDouble(marks.get("math").toString());
        }
        if (lower.equals("math") && marks.containsKey("mathematics")) {
            return Double.parseDouble(marks.get("mathematics").toString());
        }

        return null;
    }

    private void markAsUsed(Set<String> usedSet, String subject) {
        String lower = subject.toLowerCase();
        usedSet.add(lower);
        if (lower.equals("math")) {
            usedSet.add("mathematics");
        }
        if (lower.equals("mathematics")) {
            usedSet.add("math");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> checkEligibility(String token, String courseId) {
        User student = getAuthenticatedUser(token);
        if (student.getRole() != User.Role.student) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students can check eligibility.");
        }

        if (isCandidatePlaced(student)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You have already been placed and cannot apply for additional courses.");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found."));

        if (course.getApplication_dateline() != null && LocalDateTime.now().isAfter(course.getApplication_dateline())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The application deadline for this course has passed.");
        }

        if (applicationRepository.findByStudentIdAndCourseId(student.getId(), courseId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You have already applied for this course.");
        }

        if (!(student.getAcademicMarks() instanceof Map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Academic marks are missing or invalid.");
        }

        Map<String, Object> studentMarks = (Map<String, Object>) student.getAcademicMarks();
        Map<String, Object> criteria = course.getEligibility_criteria() != null
                ? course.getEligibility_criteria()
                : Collections.emptyMap();

        boolean isEligible = true;
        Map<String, Object> details = new HashMap<>();
        List<String> failureMessages = new ArrayList<>();

        double totalMarks = 0;
        int count = 0;
        List<Map.Entry<String, Double>> allSubjects = new ArrayList<>();

        for (Map.Entry<String, Object> entry : studentMarks.entrySet()) {
            try {
                if (entry.getValue() instanceof Number) {
                    double mark = Double.parseDouble(entry.getValue().toString());
                    totalMarks += mark;
                    count++;
                    allSubjects.add(new AbstractMap.SimpleEntry<>(entry.getKey(), mark));
                }
            } catch (Exception ignored) {
            }
        }
        double aggregate = count > 0 ? totalMarks / count : 0;

        Set<String> usedForEligibility = new HashSet<>();
        for (String subject : criteria.keySet()) {
            String lowerSub = subject.toLowerCase();
            if (lowerSub.equals("overall_aggregate") || lowerSub.contains("best subject")) {
                continue;
            }

            double reqMark = Double.parseDouble(criteria.get(subject).toString());
            Double studentMark = getStudentMark(studentMarks, subject);

            boolean met = (studentMark != null && studentMark >= reqMark);
            if (!met) {
                isEligible = false;
                double actualMark = (studentMark != null) ? studentMark : 0;
                failureMessages.add(String.format(
                        "You have not passed the minimum marks required for %s, which is %.0f (Your mark is %.0f).",
                        subject, reqMark, actualMark));
            }

            Map<String, Object> subjectStat = new HashMap<>();
            subjectStat.put("required", reqMark);
            subjectStat.put("studentMark", studentMark != null ? studentMark : 0);
            subjectStat.put("met", met);
            details.put(subject, subjectStat);

            if (met) {
                markAsUsed(usedForEligibility, subject);
            }
        }

        List<Map.Entry<String, Double>> remaining = allSubjects.stream()
                .filter(e -> !usedForEligibility.contains(e.getKey().toLowerCase()))
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toList());

        int bestSubCounter = 1;
        while (criteria.containsKey("Best Subject " + bestSubCounter)) {
            String key = "Best Subject " + bestSubCounter;
            double reqMark = Double.parseDouble(criteria.get(key).toString());

            if (remaining.size() < bestSubCounter) {
                isEligible = false;
                failureMessages.add("You do not have enough subjects to fulfill the '" + key + "' requirement.");
            } else {
                Map.Entry<String, Double> best = remaining.get(bestSubCounter - 1);
                boolean met = best.getValue() >= reqMark;
                if (!met) {
                    isEligible = false;
                    failureMessages.add(String.format("Your %s (%s) of %.0f is below the required %.0f.",
                            key, best.getKey(), best.getValue(), reqMark));
                }

                Map<String, Object> stat = new HashMap<>();
                stat.put("required", reqMark);
                stat.put("studentMark", best.getValue());
                stat.put("met", met);
                stat.put("subjectName", best.getKey());
                details.put(key, stat);
            }
            bestSubCounter++;
        }

        if (criteria.containsKey("Overall_Aggregate")) {
            double reqAgg = Double.parseDouble(criteria.get("Overall_Aggregate").toString());
            double roundedAgg = Math.round(aggregate * 100.0) / 100.0;
            boolean met = roundedAgg >= reqAgg;
            if (!met) {
                isEligible = false;
                failureMessages.add(String.format("Your overall aggregate of %.2f is below the required %.2f.",
                        roundedAgg, reqAgg));
            }

            Map<String, Object> aggStat = new HashMap<>();
            aggStat.put("required", reqAgg);
            aggStat.put("studentMark", roundedAgg);
            aggStat.put("met", met);
            details.put("Overall_Aggregate", aggStat);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("isEligible", isEligible);
        response.put("eligibilityDetails", details);

        if (isEligible) {
            response.put("message", "You meet all eligibility criteria.");
            Map<String, Object> merit = calculateMerit(studentMarks, course.getMerit_ranking());
            Map<String, Object> appData = new HashMap<>();
            appData.put("totalMeritScore", merit.get("totalScore"));
            appData.put("meritRankingBreakdown", merit.get("breakdown"));
            response.put("applicationData", appData);
        } else {
            response.put("message", "Eligibility criteria not met.");
            response.put("reasons", failureMessages);
        }

        return response;
    }

    private Map<String, Object> calculateMerit(Map<String, Object> marks, Map<String, Object> ranking) {
        if (ranking == null || ranking.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("totalScore", calculateAggregate(marks));
            result.put("breakdown", Collections.emptyMap());
            return result;
        }

        double total = 0;
        Map<String, Double> breakdown = new HashMap<>();
        Set<String> usedInMerit = new HashSet<>();

        for (String key : ranking.keySet()) {
            if (key.toLowerCase().contains("best subject")) {
                continue;
            }

            Double studentMark = getStudentMark(marks, key);
            if (studentMark != null) {
                double weight = Double.parseDouble(ranking.get(key).toString());
                double score = studentMark * weight;
                total += score;
                breakdown.put(key, score);
                markAsUsed(usedInMerit, key);
            }
        }

        List<Map.Entry<String, Double>> sortedForMerit = marks.entrySet().stream()
                .filter(e -> !usedInMerit.contains(e.getKey().toLowerCase()) && e.getValue() instanceof Number)
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), Double.parseDouble(e.getValue().toString())))
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toList());

        int count = 1;
        while (ranking.containsKey("Best Subject " + count) && sortedForMerit.size() >= count) {
            String key = "Best Subject " + count;
            double weight = Double.parseDouble(ranking.get(key).toString());
            Map.Entry<String, Double> best = sortedForMerit.get(count - 1);
            double score = best.getValue() * weight;
            total += score;
            breakdown.put(key + " (" + best.getKey() + ")", score);
            count++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalScore", total);
        result.put("breakdown", breakdown);
        return result;
    }

    private double calculateAggregate(Map<String, Object> marks) {
        double total = 0;
        int count = 0;
        for (Object value : marks.values()) {
            try {
                total += Double.parseDouble(value.toString());
                count++;
            } catch (Exception ignored) {
            }
        }
        return count > 0 ? total / count : 0;
    }

    public Application submitApplication(String token, String courseId, String type) {
        Map<String, Object> eligibility = checkEligibility(token, courseId);

        if (!(boolean) eligibility.get("isEligible")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Eligibility criteria not met.");
        }

        User student = getAuthenticatedUser(token);
        if (isCandidatePlaced(student)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You have already been placed and cannot apply for additional courses.");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found."));

        @SuppressWarnings("unchecked")
        Map<String, Object> appData = (Map<String, Object>) eligibility.get("applicationData");

        Application application = new Application();
        application.setStudent(student);
        application.setCourse(course);
        application.setApplicationType(type);
        application.setTotalMeritScore(((Number) appData.get("totalMeritScore")).doubleValue());

        @SuppressWarnings("unchecked")
        Map<String, Double> meritBreakdown = (Map<String, Double>) appData.get("meritRankingBreakdown");
        application.setMeritRankingBreakdown(meritBreakdown);
        application.setStatus(Application.STATUS_PENDING);
        application.setCurrentStatus(Application.STATUS_PENDING);
        application.setSubmittedAt(LocalDateTime.now());
        application.getStatusHistory().add(new Application.StatusHistory(
                null,
                Application.STATUS_PENDING,
                LocalDateTime.now(),
                actorName(student),
                student.getRole().name(),
                "Application submitted"));

        return applicationRepository.save(application);
    }

    public Map<String, Object> getApplicationsForCourse(String token, String courseId) {
        User actor = getAuthenticatedUser(token);
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found."));

        assertCanManageCourse(actor, course);

        List<Application> applications = applicationRepository.findByCourseIdOrderByTotalMeritScoreDesc(courseId);
        applications.forEach(this::ensureStatusFields);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", applications);
        response.put("courseTitle", course.getTitle());
        response.put("college", course.getCollege() != null ? course.getCollege().getCollegeName() : null);

        Map<String, Object> courseCreator = new HashMap<>();
        courseCreator.put("id", course.getCollege() != null ? course.getCollege().getId() : null);
        courseCreator.put("name", course.getCollege() != null ? course.getCollege().getFullName() : null);
        courseCreator.put("image", course.getCollege() != null ? course.getCollege().getProfileImageUrl() : null);
        response.put("courseCreator", courseCreator);

        return response;
    }

    public List<Application> getMyApplications(String token) {
        User student = getAuthenticatedUser(token);
        if (student.getRole() != User.Role.student) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students can view their applications here.");
        }

        List<Application> applications = applicationRepository.findByStudentIdOrderBySubmittedAtDesc(student.getId());
        applications.forEach(this::ensureStatusFields);
        return applications;
    }

    public Map<String, Object> getPlacementStatus(String token) {
        User student = getAuthenticatedUser(token);
        if (student.getRole() != User.Role.student) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students can view placement status here.");
        }

        List<Application> applications = applicationRepository.findByStudentIdOrderBySubmittedAtDesc(student.getId());
        Application placedApplication = applications.stream()
                .peek(this::ensureStatusFields)
                .filter(app -> Application.STATUS_PLACED.equals(app.getCurrentStatus()))
                .findFirst()
                .orElse(null);

        boolean isPlaced = student.isPlaced() || placedApplication != null;
        LocalDateTime placementDate = student.getPlacementDate();
        if (placementDate == null && placedApplication != null) {
            placementDate = placedApplication.getPlacementDate();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("isPlaced", isPlaced);
        response.put("placementDate", placementDate);
        response.put("message", isPlaced
                ? "You have already been placed. You cannot apply for additional courses."
                : "You are eligible to apply for courses.");

        if (placedApplication != null) {
            response.put("placedApplicationId", placedApplication.getId());
            response.put("courseTitle", placedApplication.getCourse() != null ? placedApplication.getCourse().getTitle() : null);
            response.put("collegeName", placedApplication.getCourse() != null && placedApplication.getCourse().getCollege() != null
                    ? placedApplication.getCourse().getCollege().getCollegeName()
                    : null);
        }

        return response;
    }

    public List<Application.StatusHistory> getStatusHistory(String token, String applicationId) {
        User actor = getAuthenticatedUser(token);
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));

        if (actor.getRole() == User.Role.student) {
            if (application.getStudent() == null || !actor.getId().equals(application.getStudent().getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own application history.");
            }
        } else {
            assertCanManageApplication(actor, application);
        }

        ensureStatusFields(application);
        if (application.getStatusHistory().isEmpty()) {
            application.getStatusHistory().add(new Application.StatusHistory(
                    null,
                    application.getCurrentStatus(),
                    application.getSubmittedAt(),
                    "system",
                    "system",
                    "Application status initialized from existing record"));
        }
        return application.getStatusHistory();
    }

    public Application updateApplicationStatus(String token, String applicationId, String requestedStatus, String note) {
        User actor = getAuthenticatedUser(token);
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));

        assertCanManageApplication(actor, application);
        ensureStatusFields(application);

        String newStatus = normalizeRequestedStatus(requestedStatus);
        if (Application.STATUS_CLOSED_PLACED_ELSEWHERE.equals(newStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This status is system-managed and cannot be selected manually.");
        }

        String currentStatus = application.getCurrentStatus();
        validateTransition(currentStatus, newStatus);

        applyStatusChange(application, currentStatus, newStatus, actorName(actor), actor.getRole().name(), note);

        if (Application.STATUS_PLACED.equals(newStatus)) {
            LocalDateTime now = LocalDateTime.now();
            application.setPlaced(true);
            application.setPlacementDate(now);
            markCandidatePlaced(application, now);
        }

        Application saved = applicationRepository.save(application);
        statusEmailService.sendStatusChangeEmail(saved, displayStatus(newStatus));

        if (Application.STATUS_PLACED.equals(newStatus)) {
            closeOtherApplications(saved, actor);
        }

        return saved;
    }

    private void closeOtherApplications(Application placedApplication, User actor) {
        if (placedApplication.getStudent() == null) {
            return;
        }

        List<Application> otherApplications = applicationRepository.findByStudentIdAndIdNot(
                placedApplication.getStudent().getId(),
                placedApplication.getId());

        for (Application other : otherApplications) {
            ensureStatusFields(other);
            if (TERMINAL_STATUSES.contains(other.getCurrentStatus())) {
                continue;
            }

            String fromStatus = other.getCurrentStatus();
            applyStatusChange(
                    other,
                    fromStatus,
                    Application.STATUS_CLOSED_PLACED_ELSEWHERE,
                    "system",
                    "system",
                    "Candidate placed through another application.");
            applicationRepository.save(other);
            statusEmailService.sendStatusChangeEmail(other, displayStatus(Application.STATUS_CLOSED_PLACED_ELSEWHERE));
        }
    }

    private void markCandidatePlaced(Application application, LocalDateTime placementDate) {
        User student = application.getStudent();
        if (student == null || student.getId() == null) {
            return;
        }

        User persistedStudent = userRepository.findById(student.getId()).orElse(student);
        persistedStudent.setPlaced(true);
        persistedStudent.setPlacementDate(placementDate);
        userRepository.save(persistedStudent);

        application.setStudent(persistedStudent);
    }

    private boolean isCandidatePlaced(User student) {
        if (student.isPlaced()) {
            return true;
        }

        return applicationRepository.findByStudentIdOrderBySubmittedAtDesc(student.getId()).stream()
                .peek(this::ensureStatusFields)
                .anyMatch(application -> Application.STATUS_PLACED.equals(application.getCurrentStatus()));
    }

    private User getAuthenticatedUser(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authorization token.");
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        String identifier = jwtUtil.extractIdentifier(token);
        return userRepository.findByEmail(identifier)
                .or(() -> userRepository.findByIndexNumber(identifier))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
    }

    private void assertCanManageApplication(User actor, Application application) {
        if (actor.getRole() == User.Role.superadmin) {
            return;
        }

        if (actor.getRole() != User.Role.admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators can manage application status.");
        }

        if (application.getCourse() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Application has no course attached.");
        }

        assertCanManageCourse(actor, application.getCourse());
    }

    private void assertCanManageCourse(User actor, Course course) {
        if (actor.getRole() == User.Role.superadmin) {
            return;
        }

        if (actor.getRole() != User.Role.admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators can manage applications.");
        }

        String collegeAdminId = course.getCollege() != null ? course.getCollege().getId() : null;
        if (collegeAdminId == null || !collegeAdminId.equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only manage applications for your own college courses.");
        }
    }

    private void ensureStatusFields(Application application) {
        String normalized = normalizeExistingStatus(
                application.getCurrentStatus() != null ? application.getCurrentStatus() : application.getStatus());
        application.setStatus(normalized);
        application.setCurrentStatus(normalized);

        if (application.getStatusHistory() == null) {
            application.setStatusHistory(new ArrayList<>());
        }

        if (Application.STATUS_PLACED.equals(normalized)) {
            application.setPlaced(true);
            if (application.getPlacementDate() == null) {
                application.setPlacementDate(application.getUpdatedAt() != null
                        ? application.getUpdatedAt()
                        : LocalDateTime.now());
            }
        }
    }

    private void applyStatusChange(
            Application application,
            String fromStatus,
            String toStatus,
            String changedBy,
            String changedByRole,
            String note) {
        LocalDateTime now = LocalDateTime.now();
        application.setStatus(toStatus);
        application.setCurrentStatus(toStatus);
        application.setUpdatedAt(now);

        if (application.getStatusHistory() == null) {
            application.setStatusHistory(new ArrayList<>());
        }

        application.getStatusHistory().add(new Application.StatusHistory(
                fromStatus,
                toStatus,
                now,
                changedBy,
                changedByRole,
                note == null || note.isBlank() ? "Status updated to " + displayStatus(toStatus) : note));
    }

    private void validateTransition(String fromStatus, String toStatus) {
        if (fromStatus.equals(toStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Application is already in this status.");
        }

        if (TERMINAL_STATUSES.contains(fromStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot update an application after it reached a terminal status.");
        }

        if (Application.STATUS_REJECTED.equals(toStatus)) {
            return;
        }

        int fromIndex = ORDERED_WORKFLOW.indexOf(fromStatus);
        int toIndex = ORDERED_WORKFLOW.indexOf(toStatus);

        if (fromIndex < 0 || toIndex < 0 || toIndex != fromIndex + 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid status transition. Applications must move through the workflow in order.");
        }
    }

    private String normalizeExistingStatus(String status) {
        try {
            return normalizeRequestedStatus(status);
        } catch (ResponseStatusException ignored) {
            return Application.STATUS_PENDING;
        }
    }

    private String normalizeRequestedStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required.");
        }

        String normalized = status.trim().toLowerCase()
                .replace("-", "_")
                .replace(" ", "_");

        return switch (normalized) {
            case "applied", "pending" -> Application.STATUS_PENDING;
            case "shortlisted" -> Application.STATUS_SHORTLISTED;
            case "passed_ctt", "passed_for_ctt", "passed_for_ctt_test" -> Application.STATUS_PASSED_CTT;
            case "passed_interview", "passed_for_interview" -> Application.STATUS_PASSED_INTERVIEW;
            case "placed" -> Application.STATUS_PLACED;
            case "rejected" -> Application.STATUS_REJECTED;
            case "closed_candidate_placed_elsewhere", "closed_placed_elsewhere" ->
                    Application.STATUS_CLOSED_PLACED_ELSEWHERE;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown application status.");
        };
    }

    public String displayStatus(String status) {
        String normalized = normalizeExistingStatus(status);
        return switch (normalized) {
            case Application.STATUS_PENDING -> "Pending";
            case Application.STATUS_SHORTLISTED -> "Shortlisted";
            case Application.STATUS_PASSED_CTT -> "Passed for CTT Test";
            case Application.STATUS_PASSED_INTERVIEW -> "Passed for Interview";
            case Application.STATUS_PLACED -> "Placed";
            case Application.STATUS_REJECTED -> "Rejected";
            case Application.STATUS_CLOSED_PLACED_ELSEWHERE -> "Closed - Candidate Placed Elsewhere";
            default -> "Pending";
        };
    }

    private String actorName(User actor) {
        if (actor.getEmail() != null && !actor.getEmail().isBlank()) {
            return actor.getEmail();
        }
        if (actor.getIndexNumber() != null && !actor.getIndexNumber().isBlank()) {
            return actor.getIndexNumber();
        }
        return actor.getId();
    }
}
