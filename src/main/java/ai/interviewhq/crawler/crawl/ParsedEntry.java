package ai.interviewhq.crawler.crawl;

import java.time.Instant;

public record ParsedEntry(
        String url,
        String canonicalUrl,
        String externalId,
        String title,
        String author,
        Instant publishedAt,
        String rawCompany,
        String rawRole,
        String bodyText,
        String contentType,
        Integer httpStatus,
        String etag,
        String contentHash,
        Boolean robotsAllowed
) {
    public static Builder builder(String url) {
        return new Builder(url);
    }

    public static final class Builder {
        private final String url;
        private String canonicalUrl;
        private String externalId;
        private String title;
        private String author;
        private Instant publishedAt;
        private String rawCompany;
        private String rawRole;
        private String bodyText;
        private String contentType;
        private Integer httpStatus;
        private String etag;
        private String contentHash;
        private Boolean robotsAllowed = Boolean.TRUE;

        private Builder(String url) {
            this.url = url;
        }

        public Builder canonicalUrl(String canonicalUrl) {
            this.canonicalUrl = canonicalUrl;
            return this;
        }

        public Builder externalId(String externalId) {
            this.externalId = externalId;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder author(String author) {
            this.author = author;
            return this;
        }

        public Builder publishedAt(Instant publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        public Builder rawCompany(String rawCompany) {
            this.rawCompany = rawCompany;
            return this;
        }

        public Builder rawRole(String rawRole) {
            this.rawRole = rawRole;
            return this;
        }

        public Builder bodyText(String bodyText) {
            this.bodyText = bodyText;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder httpStatus(Integer httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder etag(String etag) {
            this.etag = etag;
            return this;
        }

        public Builder contentHash(String contentHash) {
            this.contentHash = contentHash;
            return this;
        }

        public Builder robotsAllowed(Boolean robotsAllowed) {
            this.robotsAllowed = robotsAllowed;
            return this;
        }

        public ParsedEntry build() {
            return new ParsedEntry(
                    url, canonicalUrl, externalId, title, author, publishedAt,
                    rawCompany, rawRole, bodyText, contentType, httpStatus, etag,
                    contentHash, robotsAllowed
            );
        }
    }
}
