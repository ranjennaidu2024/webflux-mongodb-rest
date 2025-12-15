package com.example.rewards.config;

import com.example.rewards.model.Reward;
import com.example.rewards.repo.RewardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Configuration
public class DataInitializer {

  private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
  private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  @Order(2) // Run after MongoConnectionValidator (Order 1)
  ApplicationRunner initData(ReactiveMongoTemplate template, RewardRepository repository, Environment environment) {
    return args -> {
      // Check if we're in a cloud environment
      boolean isCloudEnvironment = isCloudEnvironment(environment);
      String mongoUri = environment.getProperty("spring.data.mongodb.uri");
      
      log.info("=".repeat(60));
      log.info("Database Initialization");
      log.info("Environment: {}", isCloudEnvironment ? "CLOUD" : "LOCAL");
      log.info("MongoDB URI: {}", mongoUri != null ? maskUri(mongoUri) : "NOT_CONFIGURED");
      log.info("=".repeat(60));
      
      // CRITICAL: For cloud environments, validate MongoDB URI before proceeding
      if (isCloudEnvironment) {
        if (mongoUri == null || mongoUri.isEmpty()) {
          String errorMsg = 
              "❌ CRITICAL: Cannot initialize database in CLOUD environment!\n" +
              "MongoDB URI is NOT configured.\n" +
              "Database initialization is BLOCKED to prevent fallback to local database.\n" +
              "Please fix the MongoDB configuration in GCP Secret Manager.";
          log.error(errorMsg);
          throw new IllegalStateException(errorMsg);
        }
        
        if (mongoUri.contains("localhost") || mongoUri.contains("127.0.0.1")) {
          String errorMsg = String.format(
              "❌ CRITICAL: Cannot initialize database in CLOUD environment!\n" +
              "MongoDB URI points to LOCALHOST: %s\n" +
              "Cloud environments must use REMOTE MongoDB instances.\n" +
              "Database initialization is BLOCKED to prevent using wrong database.",
              maskUri(mongoUri)
          );
          log.error(errorMsg);
          throw new IllegalStateException(errorMsg);
        }
        
        log.info("✓ Cloud environment: MongoDB URI validated (points to remote database)");
      }
      
      // Proceed with initialization
      log.info("Starting database initialization...");
      ensureCollection(template)
        .then(repository.count())
        .timeout(OPERATION_TIMEOUT)
        .filter(count -> count == 0)
        .flatMapMany(ignored -> {
          log.info("Database is empty. Seeding with sample data...");
          return repository.saveAll(sampleData());
        })
        .doOnComplete(() -> {
          log.info("✅ Database initialization completed successfully.");
          log.info("=".repeat(60));
        })
        .doOnError(e -> {
          log.error("❌ CRITICAL: Database initialization FAILED!", e);
          log.error("This indicates a MongoDB connection issue. Application cannot continue.");
          log.error("Error details: {}", e.getMessage());
        })
        .onErrorResume(e -> {
          // Let the error propagate to stop the application
          return Mono.error(new IllegalStateException(
            "Failed to initialize database. MongoDB connection is not properly configured.", e));
        })
        .then()
        .subscribe(
          null,
          error -> {
            log.error("Fatal error during database initialization. Application will terminate.", error);
            System.exit(1); // Force exit on critical database errors
          }
        );
    };
  }
  
  private boolean isCloudEnvironment(Environment environment) {
    String[] activeProfiles = environment.getActiveProfiles();
    for (String profile : activeProfiles) {
      if ("local".equals(profile)) {
        return false;
      }
    }
    // If not local, assume cloud (dev/qa/uat/prod)
    return true;
  }
  
  private String maskUri(String uri) {
    if (uri == null || uri.isEmpty()) {
      return "NOT_CONFIGURED";
    }
    // Mask password in connection string
    return uri.replaceAll("://([^:]+):([^@]+)@", "://$1:***@");
  }

  private Mono<Void> ensureCollection(ReactiveMongoTemplate template) {
    return template.getMongoDatabase()
      .timeout(OPERATION_TIMEOUT)
      .doOnError(e -> log.error("Cannot access MongoDB database. Check connection configuration.", e))
      .flatMap(db -> Mono.from(db.listCollectionNames().first())
        .timeout(OPERATION_TIMEOUT)
        .onErrorResume(e -> {
          log.error("Cannot list MongoDB collections. Connection may be invalid.", e);
          return Mono.error(e);
        }))
      .then(
        template.collectionExists(Reward.class)
          .timeout(OPERATION_TIMEOUT)
          .flatMap(exists -> {
            if (exists) {
              log.info("Collection '{}' already exists.", Reward.class.getSimpleName());
              return Mono.empty();
            } else {
              log.info("Creating collection '{}'...", Reward.class.getSimpleName());
              return template.createCollection(Reward.class).then();
            }
          })
      )
      .then();
  }

  private Flux<Reward> sampleData() {
    return Flux.just(
      new Reward("user-1", 100, "welcome bonus"),
      new Reward("user-2", 250, "referral bonus"),
      new Reward("user-3", 75, "feedback reward")
    );
  }
}

