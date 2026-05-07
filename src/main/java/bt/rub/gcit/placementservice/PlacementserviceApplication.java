package bt.rub.gcit.placementservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableMongoRepositories(basePackages = "bt.rub.gcit.placementservice.dao")
public class PlacementserviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlacementserviceApplication.class, args);
	}

}
