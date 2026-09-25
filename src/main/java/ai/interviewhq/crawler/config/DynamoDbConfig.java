package ai.interviewhq.crawler.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

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
    @Primary
    DynamoDbRepositorySupport dynamoDbRepositorySupport(
            DynamoDbClient client,
            @Value("${spring.dynamodb.table:${DYNAMODB_TABLE:interviewhq-crawler-dev}}") String tableName,
            @Value("${spring.dynamodb.auto-create-table:${DYNAMODB_AUTO_CREATE_TABLE:true}}") boolean autoCreateTable) {
        return createSupport(client, tableName, autoCreateTable);
    }

    @Bean
    @Qualifier("experienceDynamoDbRepositorySupport")
    DynamoDbRepositorySupport experienceDynamoDbRepositorySupport(
            DynamoDbClient client,
            @Value("${spring.dynamodb.experience-table:${DYNAMODB_EXPERIENCE_TABLE:interviewhq-experiences-dev}}") String tableName,
            @Value("${spring.dynamodb.auto-create-table:${DYNAMODB_AUTO_CREATE_TABLE:true}}") boolean autoCreateTable) {
        return createSupport(client, tableName, autoCreateTable);
    }

    @Bean
    @Qualifier("questionDynamoDbRepositorySupport")
    DynamoDbRepositorySupport questionDynamoDbRepositorySupport(
            DynamoDbClient client,
            @Value("${spring.dynamodb.question-table:${DYNAMODB_QUESTION_TABLE:interviewhq-questions-dev}}") String tableName,
            @Value("${spring.dynamodb.auto-create-table:${DYNAMODB_AUTO_CREATE_TABLE:true}}") boolean autoCreateTable) {
        return createSupport(client, tableName, autoCreateTable);
    }

    private DynamoDbRepositorySupport createSupport(
            DynamoDbClient client,
            String tableName,
            boolean autoCreateTable) {
        var support = new DynamoDbRepositorySupport(client, tableName);
        if (autoCreateTable) {
            support.ensureTable();
        }
        return support;
    }
}
