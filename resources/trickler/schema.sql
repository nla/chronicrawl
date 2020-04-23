DROP TABLE IF EXISTS snapshot;
DROP TABLE IF EXISTS location;
DROP TABLE IF EXISTS origin;

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
    FOREIGN KEY (origin_id) REFERENCES origin (id) ON DELETE CASCADE
);

CREATE TABLE snapshot
(
    location_id BIGINT NOT NULL,
    date TIMESTAMP NOT NULL,
    response_offset BIGINT NOT NULL,
    PRIMARY KEY (location_id, date),
    FOREIGN KEY (location_id) REFERENCES location (id) ON DELETE CASCADE
);