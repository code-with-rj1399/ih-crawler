package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.domain.CrawlJobLog;
import ai.interviewhq.crawler.repo.CrawlJobLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JobLogWriter {

    private static final Logger log = LoggerFactory.getLogger("ai.interviewhq.crawler.job");

    private final CrawlJobLogRepository repository;

    public JobLogWriter(CrawlJobLogRepository repository) {
        this.repository = repository;
    }

    public void info(Integer jobId, Integer sourceId, String eventCode, String message) {
        write(jobId, sourceId, "info", eventCode, message, Map.of());
    }

    public void info(Integer jobId, Integer sourceId, String eventCode, String message, Map<String, Object> meta) {
        write(jobId, sourceId, "info", eventCode, message, meta);
    }

    public void warn(Integer jobId, Integer sourceId, String eventCode, String message) {
        write(jobId, sourceId, "warn", eventCode, message, Map.of());
    }

    public void warn(Integer jobId, Integer sourceId, String eventCode, String message, Map<String, Object> meta) {
        write(jobId, sourceId, "warn", eventCode, message, meta);
    }

    public void error(Integer jobId, Integer sourceId, String eventCode, String message) {
        write(jobId, sourceId, "error", eventCode, message, Map.of());
    }

    public void error(Integer jobId, Integer sourceId, String eventCode, String message, Map<String, Object> meta) {
        write(jobId, sourceId, "error", eventCode, message, meta);
    }

        public void write(Integer jobId, Integer sourceId, String level, String eventCode, String message, Map<String, Object> meta) {
        String previousJob = MDC.get("jobId");
        try {
            if (jobId != null) {
                MDC.put("jobId", String.valueOf(jobId));
            }
            String line = eventCode + " " + message;
            switch (level) {
                case "error" -> log.error(line);
                case "warn" -> log.warn(line);
                default -> log.info(line);
            }
            CrawlJobLog row = new CrawlJobLog();
            row.setJobId(jobId);
            row.setSourceId(sourceId);
            row.setLevel(level);
            row.setEventCode(eventCode);
            row.setMessage(message);
            row.setMeta(meta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(meta));
            repository.save(row);
        } catch (RuntimeException ex) {
            log.warn("failed to persist job log: {}", ex.getMessage());
        } finally {
            if (previousJob == null) {
                MDC.remove("jobId");
            } else {
                MDC.put("jobId", previousJob);
            }
        }
    }
}
