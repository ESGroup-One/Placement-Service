package bt.rub.gcit.placementservice.service;

import bt.rub.gcit.placementservice.entity.Application;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class StatusEmailService {

    private static final Logger logger = LoggerFactory.getLogger(StatusEmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    public void sendStatusChangeEmail(Application application, String displayStatus) {
        if (application == null || application.getStudent() == null || application.getStudent().getEmail() == null) {
            return;
        }

        String candidateName = safe(application.getStudent().getFullName());
        String collegeName = application.getCourse() != null && application.getCourse().getCollege() != null
                ? safe(application.getCourse().getCollege().getCollegeName())
                : "N/A";
        String jobTitle = application.getCourse() != null ? safe(application.getCourse().getTitle()) : "N/A";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(application.getStudent().getEmail());
        message.setSubject("Application Status Updated - " + jobTitle);
        message.setText(
                "Dear " + candidateName + ",\n\n" +
                        "Your application status has been updated.\n\n" +
                        "Candidate Name: " + candidateName + "\n" +
                        "Company/College Name: " + collegeName + "\n" +
                        "Job/Course Title: " + jobTitle + "\n" +
                        "New Status: " + displayStatus + "\n\n" +
                        "Next Steps: " + nextSteps(displayStatus) + "\n\n" +
                        "Regards,\nNSPS Placement Team");

        try {
            mailSender.send(message);
        } catch (MailException exception) {
            logger.warn("Failed to send application status email to {}", application.getStudent().getEmail(), exception);
        }
    }

    private String nextSteps(String status) {
        if ("Shortlisted".equals(status)) {
            return "Please wait for details about the CTT Test.";
        }
        if ("Passed for CTT Test".equals(status)) {
            return "Please wait for interview schedule details.";
        }
        if ("Passed for Interview".equals(status)) {
            return "Please wait for final placement confirmation.";
        }
        if ("Placed".equals(status)) {
            return "Congratulations. You have been placed. Other active applications will be closed.";
        }
        if ("Rejected".equals(status)) {
            return "Thank you for applying. Please continue exploring other opportunities.";
        }
        if ("Closed - Candidate Placed Elsewhere".equals(status)) {
            return "This application was closed because you have been placed elsewhere.";
        }
        return "Please check your dashboard for further updates.";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }
}
