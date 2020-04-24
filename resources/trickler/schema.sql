DROP TABLE IF EXISTS link;
DROP TABLE IF EXISTS snapshot;
DROP TABLE IF EXISTS location;
DROP TABLE IF EXISTS origin;
DROP TABLE IF EXISTS record;

CREATE TABLE origin
(
    id                 BIGINT    NOT NULL PRIMARY KEY,
    name               TEXT      NOT NULL,
    discovered         TIMESTAMP NOT NULL,
    last_visit         TIMESTAMP,
    next_visit         TIMESTAMP,
    robots_crawl_delay SMALLINT
);

CREATE TABLE location
(
    id                 BIGINT PRIMARY KEY                                                         NOT NULL,
    url                TEXT                                                                       NOT NULL,
    type               ENUM ('ROBOTS', 'SITEMAP', 'PAGE')                                         NOT NULL DEFAULT 'SEED',
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
    FOREIGN KEY (origin_id) REFERENCES origin (id) ON DELETE CASCADE
);

CREATE TABLE record
(
    id       UUID   NOT NULL PRIMARY KEY,
    position BIGINT NOT NULL
);

CREATE TABLE snapshot
(
    location_id    BIGINT    NOT NULL,
    date           TIMESTAMP NOT NULL,
    status         SMALLINT  NOT NULL,
    content_type   TEXT      NULL,
    content_length BIGINT    NULL,
    request_id     UUID      NOT NULL,
    response_id    UUID      NOT NULL,
    PRIMARY KEY (location_id, date),
    FOREIGN KEY (location_id) REFERENCES location (id) ON DELETE CASCADE,
    FOREIGN KEY (request_id) REFERENCES record (id) ON DELETE SET NULL,
    FOREIGN KEY (response_id) REFERENCES record (id) ON DELETE SET NULL
);

CREATE TABLE link
(
    src BIGINT NOT NULL,
    dst BIGINT NOT NULL,
    FOREIGN KEY (src) REFERENCES location ON DELETE CASCADE,
    FOREIGN KEY (dst) REFERENCES location ON DELETE CASCADE,
    PRIMARY KEY (src, dst)
);