package bt.rub.gcit.placementservice.controller;

import bt.rub.gcit.placementservice.entity.Application;
import bt.rub.gcit.placementservice.service.ApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    @Autowired
    private ApplicationService applicationService;

    @PostMapping("/check-eligibility/{courseId}")
    public ResponseEntity<Map<String, Object>> check(@RequestHeader("Authorization") String token,
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
}