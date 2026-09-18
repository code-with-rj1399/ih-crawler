package ai.interviewhq.crawler.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;

@Configuration
public class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(
            @Value("${spring.dynamodb.region:${AWS_REGION:ap-south-1}}") String region,
            @Value("${spring.dynamodb.endpoint:${DYNAMODB_ENDPOINT:}}") String endpoint) {

        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    DynamoDbRepositorySupport dynamoDbRepositorySupport(
            DynamoDbClient client,
            @Value("${spring.dynamodb.table:${DYNAMODB_TABLE:interviewhq-crawler-dev}}") String tableName) {

        var support = new DynamoDbRepositorySupport(client, tableName);
        support.ensureTable();
        return support;
    }
}
