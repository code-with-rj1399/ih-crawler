package ai.interviewhq.crawler.config;

import ai.interviewhq.crawler.domain.InterviewQuestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/** DynamoDB support for the separate experience and question tables. */
public class TwoTableDynamoDbRepositorySupport {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final DynamoDbClient client;
    private final String experiencesTable;
    private final String questionsTable;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public TwoTableDynamoDbRepositorySupport(DynamoDbClient client, String experiencesTable, String questionsTable) {
        this.client = client;
        this.experiencesTable = experiencesTable;
        this.questionsTable = questionsTable;
    }

    public void ensureTables() {
        ensureTable(experiencesTable, false);
        ensureTable(questionsTable, true);
    }

    private void ensureTable(String tableName, boolean questionTable) {
        try { client.describeTable(DescribeTableRequest.builder().tableName(tableName).build()); return; }
        catch (ResourceNotFoundException ignored) { }
        CreateTableRequest.Builder builder = CreateTableRequest.builder().tableName(tableName)
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.N).build())
                .billingMode(BillingMode.PAY_PER_REQUEST);
        if (questionTable) {
            builder.globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("experienceId-index")
                    .keySchema(KeySchemaElement.builder().attributeName("experienceId").keyType(KeyType.HASH).build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build())
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("experienceId").attributeType(ScalarAttributeType.N).build());
        }
        client.createTable(builder.build());
        for (int i = 0; i < 60; i++) {
            try { if (TableStatus.ACTIVE.equals(client.describeTable(DescribeTableRequest.builder().tableName(tableName).build()).table().tableStatus())) return; }
            catch (ResourceNotFoundException ignored) { }
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted waiting for DynamoDB table", e); }
        }
        throw new IllegalStateException("DynamoDB table did not become ACTIVE: " + tableName);
    }

    public <T> T save(T entity, String tableName, int id) {
        Map<String, Object> data = objectMapper.convertValue(entity, MAP_TYPE);
        data.values().removeIf(Objects::isNull);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.builder().n(String.valueOf(id)).build());
        item.put("entityType", AttributeValue.builder().s(entity.getClass().getSimpleName()).build());
        item.put("data", toAttributeValue(data));
        if (entity instanceof InterviewQuestion q && q.getExperienceId() != null) {
            item.put("experienceId", AttributeValue.builder().n(String.valueOf(q.getExperienceId())).build());
        }
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
        return entity;
    }

    public <T> Optional<T> find(Class<T> type, String tableName, int id) {
        var response = client.getItem(GetItemRequest.builder().tableName(tableName).key(Map.of("id", AttributeValue.builder().n(String.valueOf(id)).build())).consistentRead(true).build());
        if (!response.hasItem()) return Optional.empty();
        return Optional.of(fromItem(type, response.item()));
    }

    public <T> List<T> scan(Class<T> type, String tableName) {
        List<T> result = new ArrayList<>(); Map<String, AttributeValue> start = null;
        do {
            var b = ScanRequest.builder().tableName(tableName).consistentRead(true);
            if (start != null) b.exclusiveStartKey(start);
            var response = client.scan(b.build());
            response.items().forEach(i -> { if (i.containsKey("data")) result.add(fromItem(type, i)); });
            start = response.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return result;
    }

    public List<InterviewQuestion> findQuestionsByExperienceId(int experienceId) {
        var response = client.query(QueryRequest.builder().tableName(questionsTable).indexName("experienceId-index")
                .keyConditionExpression("experienceId = :experienceId")
                .expressionAttributeValues(Map.of(":experienceId", AttributeValue.builder().n(String.valueOf(experienceId)).build()))
                .consistentRead(false).build());
        return response.items().stream().filter(i -> i.containsKey("data")).map(i -> fromItem(InterviewQuestion.class, i)).collect(Collectors.toList());
    }

    public int nextId(String tableName) {
        String counterId = tableName + "#counter";
        var result = client.updateItem(UpdateItemRequest.builder().tableName(tableName)
                .key(Map.of("id", AttributeValue.builder().n("0").build()))
                .updateExpression("ADD #value :one").expressionAttributeNames(Map.of("#value", "value"))
                .expressionAttributeValues(Map.of(":one", AttributeValue.builder().n("1").build()))
                .returnValues(ReturnValue.UPDATED_NEW).build());
        return Integer.parseInt(result.attributes().get("value").n());
    }

    public Optional<Integer> findExperienceIdByUrl(String url) {
        String hash = hashKey(url);
        var response = client.scan(ScanRequest.builder().tableName(experiencesTable)
                .filterExpression("#data.#dedupeHash = :hash")
                .expressionAttributeNames(Map.of("#data", "data", "#dedupeHash", "dedupeHash"))
                .expressionAttributeValues(Map.of(":hash", AttributeValue.builder().s(hash).build())).build());
        return response.items().stream().map(i -> i.get("id")).filter(Objects::nonNull).map(a -> Integer.parseInt(a.n())).findFirst();
    }

    private <T> T fromItem(Class<T> type, Map<String, AttributeValue> item) {
        try { return objectMapper.readValue(objectMapper.writeValueAsBytes(fromAttributeValue(item.get("data"))), type); }
        catch (Exception e) { throw new IllegalStateException("Failed to deserialize DynamoDB entity " + type.getSimpleName(), e); }
    }

    private AttributeValue toAttributeValue(Object value) {
        if (value == null) return AttributeValue.builder().nul(true).build();
        if (value instanceof JsonNode node) try { return toAttributeValue(objectMapper.treeToValue(node, Object.class)); } catch (Exception e) { throw new IllegalArgumentException(e); }
        if (value instanceof String s) return AttributeValue.builder().s(s).build();
        if (value instanceof Number n) return AttributeValue.builder().n(n.toString()).build();
        if (value instanceof Boolean b) return AttributeValue.builder().bool(b).build();
        if (value instanceof Map<?, ?> map) { Map<String, AttributeValue> m = new HashMap<>(); map.forEach((k,v)->m.put(String.valueOf(k),toAttributeValue(v))); return AttributeValue.builder().m(m).build(); }
        if (value instanceof Collection<?> c) return AttributeValue.builder().l(c.stream().map(this::toAttributeValue).toList()).build();
        if (value.getClass().isEnum()) return AttributeValue.builder().s(value.toString()).build();
        return toAttributeValue(objectMapper.convertValue(value, MAP_TYPE));
    }

    private Object fromAttributeValue(AttributeValue value) {
        if (value == null) return null; if (value.s()!=null) return value.s(); if (value.n()!=null) { try { return value.n().contains(".") ? Double.parseDouble(value.n()) : Long.parseLong(value.n()); } catch (NumberFormatException e) { return value.n(); } }
        if (value.bool()!=null) return value.bool(); if (Boolean.TRUE.equals(value.nul())) return null;
        if (value.m()!=null) { Map<String,Object> m=new HashMap<>(); value.m().forEach((k,v)->m.put(k,fromAttributeValue(v))); return m; }
        if (value.l()!=null) return value.l().stream().map(this::fromAttributeValue).toList(); if (value.ss()!=null) return new ArrayList<>(value.ss()); return null;
    }
    public String hashKey(String value) { try { byte[] d=MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNullElse(value,"").getBytes(StandardCharsets.UTF_8)); StringBuilder h=new StringBuilder(); for(byte b:d) h.append(String.format("%02x",b)); return h.toString(); } catch(Exception e){throw new IllegalStateException(e);} }
}
