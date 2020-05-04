DROP TABLE IF EXISTS link;
DROP TABLE IF EXISTS record;
DROP TABLE IF EXISTS visit;
DROP TABLE IF EXISTS snapshot;
DROP TABLE IF EXISTS location;
DROP TABLE IF EXISTS origin;
DROP TABLE IF EXISTS warc;
DROP TABLE IF EXISTS session;

CREATE TABLE origin
(
    id                 BIGINT                                            NOT NULL PRIMARY KEY,
    name               TEXT                                              NOT NULL,
    discovered         TIMESTAMP                                         NOT NULL,
    last_visit         TIMESTAMP,
    next_visit         TIMESTAMP,
    robots_crawl_delay SMALLINT,
    robots_txt         BLOB,
    crawl_policy       ENUM ('FORBIDDEN', 'TRANSCLUSIONS', 'CONTINUOUS') NULL
);

CREATE TABLE location
(
    id                 BIGINT PRIMARY KEY                                                         NOT NULL,
    url                VARCHAR(4000)                                                              NOT NULL,
    type               ENUM ('ROBOTS', 'SITEMAP', 'PAGE', 'TRANSCLUSION')                         NOT NULL DEFAULT 'SEED',
    origin_id          BIGINT                                                                     NOT NULL,
    via                BIGINT                                                                     NOT NULL DEFAULT 0,
    discovered         TIMESTAMP                                                                  NOT NULL,
    last_visit         TIMESTAMP                                                                  NULL,
    next_visit         TIMESTAMP                                                                  NULL,
    priority           INT                                                                        NOT NULL,
    sitemap_changefreq ENUM ('ALWAYS', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'NEVER') NULL,
    sitemap_priority   FLOAT                                                                      NULL,
    sitemap_lastmod    VARCHAR(40)                                                                NULL,
    etag               TEXT                                                                       NULL,
    last_modified      TIMESTAMP                                                                  NULL,
    etag_response_id   UUID                                                                       NULL,
    etag_date          TIMESTAMP                                                                  NULL,
    FOREIGN KEY (origin_id) REFERENCES origin (id) ON DELETE CASCADE
);

CREATE TABLE warc
(
    id      UUID      NOT NULL PRIMARY KEY,
    path    TEXT      NOT NULL,
    created TIMESTAMP NOT NULL
);

CREATE TABLE visit
(
    id             UUID         NOT NULL PRIMARY KEY,
    method         VARCHAR(16)  NOT NULL,
    location_id    BIGINT       NOT NULL,
    date           TIMESTAMP    NOT NULL,
    status         SMALLINT     NOT NULL,
    content_type   VARCHAR(128) NULL,
    content_length BIGINT       NULL,
    FOREIGN KEY (location_id) REFERENCES location (id) ON DELETE CASCADE
);

CREATE TABLE record
(
    id             UUID                                                              NOT NULL PRIMARY KEY,
    visit_id       UUID                                                              NOT NULL,
    type           ENUM ('request', 'response', 'revisit', 'metadata', 'conversion') NOT NULL,
    warc_id        UUID                                                              NOT NULL,
    position       BIGINT                                                            NOT NULL,
    length         BIGINT                                                            NOT NULL,
    payload_digest VARBINARY(128)                                                    NULL,
    FOREIGN KEY (visit_id) REFERENCES visit (id) ON DELETE CASCADE,
    FOREIGN KEY (warc_id) REFERENCES warc (id) ON DELETE CASCADE
);

CREATE TABLE link
(
    src BIGINT NOT NULL,
    dst BIGINT NOT NULL,
    FOREIGN KEY (src) REFERENCES location ON DELETE CASCADE,
    FOREIGN KEY (dst) REFERENCES location ON DELETE CASCADE,
    PRIMARY KEY (src, dst)
);

CREATE TABLE session (
    id VARCHAR(30) NOT NULL,
    username VARCHAR(128) NULL,
    role ENUM('anonymous', 'admin') NOT NULL DEFAULT 'anonymous',
    oidc_state VARCHAR(30) NOT NULL,
    expiry TIMESTAMP NOT NULL
);