package bt.rub.gcit.placementservice.dao;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import bt.rub.gcit.placementservice.entity.User;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByIndexNumber(String indexNumber);
    Optional<User> findByEmail(String email);
    Optional<User> findByPasswordToken(String passwordToken);
}
