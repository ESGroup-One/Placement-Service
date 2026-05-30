package bt.rub.gcit.placementservice.controller;

import bt.rub.gcit.placementservice.entity.Application;
import bt.rub.gcit.placementservice.service.ApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    @Autowired
    private ApplicationService applicationService;

    @PostMapping("/check-eligibility/{courseId}")
    public ResponseEntity<Map<String, Object>> check(
            @RequestHeader("Authorization") String token,
            @PathVariable String courseId) {
        return ResponseEntity.ok(applicationService.checkEligibility(token, courseId));
    }

    @PostMapping("/apply/{courseId}")
    public ResponseEntity<Application> submitApplication(
            @RequestHeader("Authorization") String token,
            @PathVariable String courseId,
            @RequestBody Map<String, String> body) {

        String type = body.getOrDefault("applicationType", "higher-education");
        Application savedApplication = applicationService.submitApplication(token, courseId, type);

        return ResponseEntity.ok(savedApplication);
    }

    @GetMapping("/course/{courseId}")
    public ResponseEntity<Map<String, Object>> getApplicationsForCourse(
            @RequestHeader("Authorization") String token,
            @PathVariable String courseId) {
        return ResponseEntity.ok(applicationService.getApplicationsForCourse(token, courseId));
    }

    @GetMapping("/my")
    public ResponseEntity<List<Application>> getMyApplications(@RequestHeader("Authorization") String token) {
        return ResponseEntity.ok(applicationService.getMyApplications(token));
    }

    @GetMapping("/placement-status")
    public ResponseEntity<Map<String, Object>> getPlacementStatus(@RequestHeader("Authorization") String token) {
        return ResponseEntity.ok(applicationService.getPlacementStatus(token));
    }

    @GetMapping("/{applicationId}/history")
    public ResponseEntity<List<Application.StatusHistory>> getStatusHistory(
            @RequestHeader("Authorization") String token,
            @PathVariable String applicationId) {
        return ResponseEntity.ok(applicationService.getStatusHistory(token, applicationId));
    }

    @RequestMapping(value = "/{applicationId}/status", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<Application> updateStatus(
            @RequestHeader("Authorization") String token,
            @PathVariable String applicationId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(applicationService.updateApplicationStatus(
                token,
                applicationId,
                body.get("status"),
                body.get("note")));
    }
}
