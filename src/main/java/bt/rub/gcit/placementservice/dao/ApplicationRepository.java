package bt.rub.gcit.placementservice.dao;

import bt.rub.gcit.placementservice.entity.Application;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends MongoRepository<Application, String> {
    Optional<Application> findByStudentIdAndCourseId(String studentId, String courseId);
    List<Application> findByCourseIdOrderByTotalMeritScoreDesc(String courseId);
    long countByStatus(String status);
    List<Application> findBySubmittedAtGreaterThanEqual(LocalDateTime date);
}