package ai.interviewhq.crawler.config;

import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DevDataSeeder implements CommandLineRunner {

    private final CrawlSourceRepository repository;
    private final boolean enabled;

    public DevDataSeeder(
            CrawlSourceRepository repository,
            @Value("${crawler.seed.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }

        seed("leetcode-interviews", "LeetCode Interview Experience",
                "https://leetcode.com/discuss/interview-experience/", "leetcode_discuss", 30, 2000);

        seed("reddit-experienced-devs", "Reddit ExperiencedDevs",
                "https://www.reddit.com/r/ExperiencedDevs/new.json?limit=25", "reddit_json", 10, 7000);

        seed("hacker-news-interviews", "Hacker News Interview Discussions",
                "https://hn.algolia.com/api/v1/search?query=interview&tags=story&hitsPerPage=20", "hn_algolia", 30, 3000);

        seed("geeksforgeeks-interviews", "GeeksforGeeks Interview Experiences",
                "https://www.geeksforgeeks.org/category/experiences/interview-experiences/", "html", 10, 5000);

        seed("interviewbit-questions", "InterviewBit Coding Interview Questions",
                "https://www.interviewbit.com/coding-interview-questions/", "html", 10, 5000);

        seed("codeforces-problems", "Codeforces Problemset",
                "https://codeforces.com/problemset", "html", 10, 5000);

        seed("stackoverflow-interview-questions", "Stack Overflow Interview Questions",
                "https://stackoverflow.com/questions/tagged/interview-questions", "html", 10, 5000);

        seed("hackerrank-interview-prep", "HackerRank Interview Preparation Kit",
                "https://www.hackerrank.com/interview/interview-preparation-kit", "html", 10, 5000);

        seed("devto-interview", "DEV Community Interview",
                "https://dev.to/tag/interview", "html", 10, 5000);

        seed("medium-software-interviews", "Medium Software Engineering Interview",
                "https://medium.com/tag/software-engineering-interview", "html", 10, 5000);
    }

    private void seed(String slug, String name, String url, String kind, int rpm, int delayMs) {
        if (repository.findBySlug(slug).isPresent()) {
            return;
        }

        CrawlSource source = new CrawlSource();
        source.setSlug(slug);
        source.setName(name);
        source.setUrl(url);
        source.setSourceKind(kind);
        source.setEnabled(true);
        source.setRateLimitRpm(rpm);
        source.setCrawlDelayMs(delayMs);
        source.setPerHostConcurrency(1);
        source.setRobotsMode("RESPECT");
        source.setCreatedAt(Instant.now());
        source.setUpdatedAt(Instant.now());
        repository.save(source);
    }
}
