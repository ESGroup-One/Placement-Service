package bt.rub.gcit.placementservice.service;

import bt.rub.gcit.placementservice.dao.ApplicationRepository;
import bt.rub.gcit.placementservice.dao.CourseRepository;
import bt.rub.gcit.placementservice.dao.UserRepository;
import bt.rub.gcit.placementservice.entity.Application;
import bt.rub.gcit.placementservice.entity.Course;
import bt.rub.gcit.placementservice.entity.User;
import bt.rub.gcit.placementservice.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private StatusEmailService statusEmailService;

    @InjectMocks
    private ApplicationService applicationService;

    private User admin;
    private User student;
    private Course course;
    private Application application;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId("admin-1");
        admin.setEmail("admin@example.com");
        admin.setRole(User.Role.admin);

        student = new User();
        student.setId("student-1");
        student.setEmail("student@example.com");
        student.setFullName("Student One");
        student.setRole(User.Role.student);

        course = new Course();
        course.setId("course-1");
        course.setTitle("Software Engineering");

        Course.CollegeInfo college = new Course.CollegeInfo();
        college.setId(admin.getId());
        college.setCollegeName("GCIT");
        course.setCollege(college);

        application = new Application();
        application.setId("application-1");
        application.setStudent(student);
        application.setCourse(course);
        application.setStatus(Application.STATUS_PENDING);
        application.setCurrentStatus(Application.STATUS_PENDING);
    }

    @Test
    void updateApplicationStatusMovesPendingToShortlistedAndSendsEmail() {
        mockAdminToken();
        when(applicationRepository.findById(application.getId())).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Application updated = applicationService.updateApplicationStatus(
                "Bearer token",
                application.getId(),
                Application.STATUS_SHORTLISTED,
                "Strong merit score");

        assertThat(updated.getCurrentStatus()).isEqualTo(Application.STATUS_SHORTLISTED);
        assertThat(updated.getStatus()).isEqualTo(Application.STATUS_SHORTLISTED);
        assertThat(updated.getStatusHistory()).hasSize(1);
        assertThat(updated.getStatusHistory().get(0).getFromStatus()).isEqualTo(Application.STATUS_PENDING);
        assertThat(updated.getStatusHistory().get(0).getToStatus()).isEqualTo(Application.STATUS_SHORTLISTED);
        assertThat(updated.getStatusHistory().get(0).getNote()).isEqualTo("Strong merit score");

        verify(statusEmailService).sendStatusChangeEmail(updated, "Shortlisted");
    }

    @Test
    void updateApplicationStatusRejectsOutOfOrderWorkflowTransition() {
        mockAdminToken();
        when(applicationRepository.findById(application.getId())).thenReturn(Optional.of(application));

        assertThatThrownBy(() -> applicationService.updateApplicationStatus(
                "Bearer token",
                application.getId(),
                Application.STATUS_PASSED_CTT,
                null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(applicationRepository, never()).save(any(Application.class));
        verify(statusEmailService, never()).sendStatusChangeEmail(any(), any());
    }

    @Test
    void updateApplicationStatusRejectsStudentActor() {
        when(jwtUtil.extractIdentifier("token")).thenReturn(student.getEmail());
        when(userRepository.findByEmail(student.getEmail())).thenReturn(Optional.of(student));
        when(applicationRepository.findById(application.getId())).thenReturn(Optional.of(application));

        assertThatThrownBy(() -> applicationService.updateApplicationStatus(
                "Bearer token",
                application.getId(),
                Application.STATUS_SHORTLISTED,
                null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(applicationRepository, never()).save(any(Application.class));
    }

    @Test
    void placingCandidateMarksStudentPlacedAndClosesOtherActiveApplications() {
        application.setStatus(Application.STATUS_PASSED_INTERVIEW);
        application.setCurrentStatus(Application.STATUS_PASSED_INTERVIEW);

        Application otherApplication = new Application();
        otherApplication.setId("application-2");
        otherApplication.setStudent(student);
        otherApplication.setCourse(course);
        otherApplication.setStatus(Application.STATUS_PENDING);
        otherApplication.setCurrentStatus(Application.STATUS_PENDING);

        mockAdminToken();
        when(applicationRepository.findById(application.getId())).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(applicationRepository.findByStudentIdAndIdNot(student.getId(), application.getId()))
                .thenReturn(List.of(otherApplication));

        Application updated = applicationService.updateApplicationStatus(
                "Bearer token",
                application.getId(),
                Application.STATUS_PLACED,
                "Placement confirmed");

        assertThat(updated.getCurrentStatus()).isEqualTo(Application.STATUS_PLACED);
        assertThat(updated.isPlaced()).isTrue();
        assertThat(updated.getPlacementDate()).isNotNull();
        assertThat(student.isPlaced()).isTrue();
        assertThat(student.getPlacementDate()).isNotNull();
        assertThat(otherApplication.getCurrentStatus()).isEqualTo(Application.STATUS_CLOSED_PLACED_ELSEWHERE);

        verify(userRepository).save(student);
        verify(applicationRepository, times(2)).save(any(Application.class));
        verify(statusEmailService).sendStatusChangeEmail(updated, "Placed");
        verify(statusEmailService).sendStatusChangeEmail(otherApplication, "Closed - Candidate Placed Elsewhere");
    }

    private void mockAdminToken() {
        when(jwtUtil.extractIdentifier("token")).thenReturn(admin.getEmail());
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
    }
}
