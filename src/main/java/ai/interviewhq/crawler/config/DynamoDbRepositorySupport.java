package ai.interviewhq.crawler.config;

import ai.interviewhq.crawler.domain.InterviewQuestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class DynamoDbRepositorySupport {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final DynamoDbClient client;
    private final String tableName;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public DynamoDbRepositorySupport(DynamoDbClient client, String tableName) { this.client = client; this.tableName = tableName; }

    public void ensureTable() {
        try { client.describeTable(DescribeTableRequest.builder().tableName(tableName).build()); }
        catch (ResourceNotFoundException e) {
            client.createTable(CreateTableRequest.builder().tableName(tableName).keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(), KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()).attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(), AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).billingMode(BillingMode.PAY_PER_REQUEST).build());
            for (int i = 0; i < 30; i++) { try { if (TableStatus.ACTIVE.equals(client.describeTable(DescribeTableRequest.builder().tableName(tableName).build()).table().tableStatus())) return; } catch (ResourceNotFoundException ignored) {} try { Thread.sleep(250); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while waiting for DynamoDB table", exception); } }
            throw new IllegalStateException("DynamoDB table did not become ACTIVE: " + tableName);
        }
    }

    public <T> T save(T entity, String pk, String sk) {
        Map<String, Object> data = objectMapper.convertValue(entity, MAP_TYPE);
        normalizeInterviewQuestionTypes(data);
        data.values().removeIf(Objects::isNull);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.builder().s(pk).build());
        item.put("sk", AttributeValue.builder().s(sk).build());
        item.put("entityType", AttributeValue.builder().s(entity.getClass().getSimpleName()).build());
        item.put("data", toAttributeValue(data));
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
        return entity;
    }

    public <T> Optional<T> find(Class<T> type, String pk, String sk) {
        var response = client.getItem(GetItemRequest.builder().tableName(tableName).key(Map.of("pk", AttributeValue.builder().s(pk).build(), "sk", AttributeValue.builder().s(sk).build())).consistentRead(true).build());
        if (!response.hasItem()) return Optional.empty();
        return Optional.of(fromItem(type, response.item()));
    }

    public <T> List<T> scan(Class<T> type) {
        List<T> result = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        String entityType = type.getSimpleName();
        do {
            var request = ScanRequest.builder().tableName(tableName).consistentRead(true).filterExpression("entityType = :entityType").expressionAttributeValues(Map.of(":entityType", AttributeValue.builder().s(entityType).build()));
            if (start != null) request.exclusiveStartKey(start);
            var response = client.scan(request.build());
            for (var item : response.items()) if (item.containsKey("data")) result.add(fromItem(type, item));
            start = response.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return result;
    }

    public <T> List<T> query(Class<T> type, String pk) {
        var response = client.query(QueryRequest.builder().tableName(tableName).keyConditionExpression("pk = :pk").expressionAttributeValues(Map.of(":pk", AttributeValue.builder().s(pk).build())).consistentRead(true).build());
        return response.items().stream().filter(i -> i.containsKey("data") && i.get("entityType") != null && type.getSimpleName().equals(i.get("entityType").s())).map(i -> fromItem(type, i)).collect(Collectors.toList());
    }

    public void delete(String pk, String sk) { client.deleteItem(DeleteItemRequest.builder().tableName(tableName).key(Map.of("pk", AttributeValue.builder().s(pk).build(), "sk", AttributeValue.builder().s(sk).build())).build()); }

    public int deleteAllByEntityType(Class<?> type) {
        String entityType = type.getSimpleName();
        int deleted = 0;
        Map<String, AttributeValue> start = null;
        do {
            ScanRequest.Builder request = ScanRequest.builder().tableName(tableName).filterExpression("entityType = :entityType").expressionAttributeValues(Map.of(":entityType", AttributeValue.builder().s(entityType).build()));
            if (start != null && !start.isEmpty()) request.exclusiveStartKey(start);
            var response = client.scan(request.build());
            for (var item : response.items()) {
                AttributeValue pk = item.get("pk");
                AttributeValue sk = item.get("sk");
                if (pk != null && sk != null) { delete(pk.s(), sk.s()); deleted++; }
            }
            start = response.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return deleted;
    }

    public int nextId(String sequenceName) {
        var result = client.updateItem(UpdateItemRequest.builder().tableName(tableName).key(Map.of("pk", AttributeValue.builder().s("COUNTER#" + sequenceName).build(), "sk", AttributeValue.builder().s("ENTITY").build())).updateExpression("ADD #value :one").expressionAttributeNames(Map.of("#value", "value")).expressionAttributeValues(Map.of(":one", AttributeValue.builder().n("1").build())).returnValues(ReturnValue.UPDATED_NEW).build());
        return Integer.parseInt(result.attributes().get("value").n());
    }

    public String hashKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException("Unable to hash DynamoDB key", e); }
    }

    private <T> T fromItem(Class<T> type, Map<String, AttributeValue> item) {
        try {
            Object data = fromAttributeValue(item.get("data"));
            if (type == InterviewQuestion.class && data instanceof Map<?, ?> map) {
                normalizeInterviewQuestionTypes((Map<String, Object>) map);
            }
            return objectMapper.readValue(objectMapper.writeValueAsBytes(data), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize DynamoDB entity " + type.getSimpleName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void normalizeInterviewQuestionTypes(Map<String, Object> data) {
        Object questionTypes = data.get("questionTypes");
        if (questionTypes instanceof Collection<?> collection) {
            List<String> normalized = collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .toList();
            data.put("questionTypes", new ArrayList<>(normalized));
            data.remove("questionType");
            return;
        }
        if (questionTypes instanceof Map<?, ?> map) {
            List<String> normalized = map.values().stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .toList();
            data.put("questionTypes", new ArrayList<>(normalized));
            data.remove("questionType");
            return;
        }
        if (questionTypes instanceof String value && !value.isBlank()) {
            data.put("questionTypes", new ArrayList<>(List.of(value)));
            data.remove("questionType");
            return;
        }

        Object legacy = data.get("questionType");
        if (legacy instanceof Collection<?> collection) {
            data.put("questionTypes", new ArrayList<>(collection.stream().filter(Objects::nonNull).map(String::valueOf).filter(v -> !v.isBlank()).toList()));
        } else if (legacy instanceof Map<?, ?> map) {
            data.put("questionTypes", new ArrayList<>(map.values().stream().filter(Objects::nonNull).map(String::valueOf).filter(v -> !v.isBlank()).toList()));
        } else if (legacy instanceof String value && !value.isBlank()) {
            data.put("questionTypes", new ArrayList<>(List.of(value)));
        } else {
            data.put("questionTypes", new ArrayList<>());
        }
        data.remove("questionType");
    }

    private AttributeValue toAttributeValue(Object value) {
        if (value == null) return AttributeValue.builder().nul(true).build();
        if (value instanceof JsonNode node) {
            try { return toAttributeValue(objectMapper.treeToValue(node, Object.class)); }
            catch (Exception e) { throw new IllegalArgumentException("Failed to convert JsonNode to DynamoDB value", e); }
        }
        if (value instanceof String s) return AttributeValue.builder().s(s).build();
        if (value instanceof Number n) return AttributeValue.builder().n(n.toString()).build();
        if (value instanceof Boolean b) return AttributeValue.builder().bool(b).build();
        if (value instanceof Map<?, ?> map) {
            Map<String, AttributeValue> result = new HashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), toAttributeValue(v)));
            return AttributeValue.builder().m(result).build();
        }
        if (value instanceof Collection<?> collection) return AttributeValue.builder().l(collection.stream().map(this::toAttributeValue).toList()).build();
        if (value.getClass().isEnum()) return AttributeValue.builder().s(value.toString()).build();
        return toAttributeValue(objectMapper.convertValue(value, MAP_TYPE));
    }

    private Object fromAttributeValue(AttributeValue value) {
        if (value == null) return null;
        if (value.s() != null) return value.s();
        if (value.n() != null) {
            String n = value.n();
            try { return n.contains(".") ? Double.parseDouble(n) : Long.parseLong(n); }
            catch (NumberFormatException ignored) { return n; }
        }
        if (value.bool() != null) return value.bool();
        if (value.nul() != null && value.nul()) return null;
        if (value.m() != null) {
            Map<String, Object> result = new HashMap<>();
            value.m().forEach((k, v) -> result.put(k, fromAttributeValue(v)));
            return result;
        }
        if (value.l() != null) return value.l().stream().map(this::fromAttributeValue).toList();
        if (value.ss() != null) return new ArrayList<>(value.ss());
        return null;
    }
}
