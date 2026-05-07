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
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ApplicationService {

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    // Helper to handle subject mapping (e.g., Mathematics -> math)
    private Double getStudentMark(Map<String, Object> marks, String subject) {
        String lower = subject.toLowerCase();
        if (marks.containsKey(lower))
            return Double.parseDouble(marks.get(lower).toString());

        // Alias mapping
        if (lower.equals("mathematics") && marks.containsKey("math"))
            return Double.parseDouble(marks.get("math").toString());
        if (lower.equals("math") && marks.containsKey("mathematics"))
            return Double.parseDouble(marks.get("mathematics").toString());

        return null;
    }

    private void markAsUsed(Set<String> usedSet, String subject) {
        String lower = subject.toLowerCase();
        usedSet.add(lower);
        if (lower.equals("math"))
            usedSet.add("mathematics");
        if (lower.equals("mathematics"))
            usedSet.add("math");
    }

    public Map<String, Object> checkEligibility(String token, String courseId) {
        User student = getAuthenticatedUser(token);
        if (student.getRole() != User.Role.student) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students can check eligibility.");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found."));

        if (applicationRepository.findByStudentIdAndCourseId(student.getId(), courseId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You have already applied for this course.");
        }

        Map<String, Object> studentMarks = (Map<String, Object>) student.getAcademicMarks();
        Map<String, Object> criteria = course.getEligibility_criteria();

        boolean isEligible = true;
        Map<String, Object> details = new HashMap<>();

        // 1. Calculate Overall Aggregate
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

        // 2. Fixed Subject Check
        Set<String> usedForEligibility = new HashSet<>();
        for (String subject : criteria.keySet()) {
            String lowerSub = subject.toLowerCase();
            if (lowerSub.equals("overall_aggregate") || lowerSub.contains("best subject"))
                continue;

            double reqMark = Double.parseDouble(criteria.get(subject).toString());
            Double studentMark = getStudentMark(studentMarks, subject);

            boolean met = (studentMark != null && studentMark >= reqMark);
            if (!met)
                isEligible = false;

            Map<String, Object> subjectStat = new HashMap<>();
            subjectStat.put("required", reqMark);
            subjectStat.put("studentMark", studentMark != null ? studentMark : 0);
            subjectStat.put("met", met);
            details.put(subject, subjectStat);

            if (met)
                markAsUsed(usedForEligibility, subject);
        }

        // 3. Best Subject Check
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
            } else {
                Map.Entry<String, Double> best = remaining.get(bestSubCounter - 1);
                boolean met = best.getValue() >= reqMark;
                if (!met)
                    isEligible = false;

                Map<String, Object> stat = new HashMap<>();
                stat.put("required", reqMark);
                stat.put("studentMark", best.getValue());
                stat.put("met", met);
                stat.put("subjectName", best.getKey());
                details.put(key, stat);
            }
            bestSubCounter++;
        }

        // 4. Aggregate Check
        if (criteria.containsKey("Overall_Aggregate")) {
            double reqAgg = Double.parseDouble(criteria.get("Overall_Aggregate").toString());
            boolean met = aggregate >= reqAgg;
            if (!met)
                isEligible = false;

            Map<String, Object> aggStat = new HashMap<>();
            aggStat.put("required", reqAgg);
            aggStat.put("studentMark", Math.round(aggregate * 100.0) / 100.0);
            aggStat.put("met", met);
            details.put("Overall_Aggregate", aggStat);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("isEligible", isEligible);
        response.put("eligibilityDetails", details);

        if (isEligible) {
            Map<String, Object> merit = calculateMerit(studentMarks, course.getMerit_ranking());
            Map<String, Object> appData = new HashMap<>();
            appData.put("totalMeritScore", merit.get("totalScore"));
            appData.put("meritRankingBreakdown", merit.get("breakdown"));
            response.put("applicationData", appData);
        }

        return response;
    }

    private Map<String, Object> calculateMerit(Map<String, Object> marks, Map<String, Object> ranking) {
        double total = 0;
        Map<String, Double> breakdown = new HashMap<>();
        Set<String> usedInMerit = new HashSet<>();

        // Fixed subjects in merit ranking
        for (String key : ranking.keySet()) {
            if (key.toLowerCase().contains("best subject"))
                continue;

            Double studentMark = getStudentMark(marks, key);
            if (studentMark != null) {
                double weight = Double.parseDouble(ranking.get(key).toString());
                double score = studentMark * weight;
                total += score;
                breakdown.put(key, score);
                markAsUsed(usedInMerit, key);
            }
        }

        // Dynamic Best subjects for merit
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

    private User getAuthenticatedUser(String token) {
        if (token.startsWith("Bearer "))
            token = token.substring(7);
        String identifier = jwtUtil.extractIdentifier(token);
        return userRepository.findByEmail(identifier)
                .or(() -> userRepository.findByIndexNumber(identifier))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public Application submitApplication(String token, String courseId, String type) {
        Map<String, Object> eligibility = checkEligibility(token, courseId);

        if (!(boolean) eligibility.get("isEligible")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Eligibility criteria not met.");
        }

        User student = getAuthenticatedUser(token);

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found."));

        Map<String, Object> appData = (Map<String, Object>) eligibility.get("applicationData");

        Application application = new Application();

        application.setStudent(student);
        application.setCourse(course);

        application.setApplicationType(type);
        application.setTotalMeritScore((Double) appData.get("totalMeritScore"));
        application.setMeritRankingBreakdown((Map<String, Double>) appData.get("meritRankingBreakdown"));
        application.setStatus("pending");
        application.setSubmittedAt(LocalDateTime.now());

        return applicationRepository.save(application);
    }

}