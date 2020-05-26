DROP TABLE IF EXISTS link;
DROP TABLE IF EXISTS record;
DROP TABLE IF EXISTS visit;
DROP TABLE IF EXISTS snapshot;
DROP TABLE IF EXISTS location;
DROP TABLE IF EXISTS origin;
DROP TABLE IF EXISTS warc;
DROP TABLE IF EXISTS schedule;
DROP TABLE IF EXISTS session;
DROP TABLE IF EXISTS screenshot_cache;

CREATE TABLE crawl_policy
(
  id TINYINT NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL
);

INSERT INTO crawl_policy (id, name) VALUES (0, 'FORBIDDEN'), (1, 'TRANSCLUSIONS'), (2, 'CONTINUOUS');

CREATE TABLE origin
(
    id                 BIGINT       NOT NULL PRIMARY KEY,
    origin             VARCHAR(255) NOT NULL,
    discovered         BIGINT    NOT NULL,
    crawl_policy_id    TINYINT      NOT NULL,
    last_visit         BIGINT,
    next_visit         BIGINT,
    robots_crawl_delay SMALLINT,
    robots_txt         BLOB,
    FOREIGN KEY (crawl_policy_id) REFERENCES crawl_policy
);

CREATE TABLE location_type
(
    id TINYINT NOT NULL PRIMARY KEY,
    location_type VARCHAR(64) NOT NULL UNIQUE
);

INSERT INTO location_type(id, location_type) VALUES (0, 'PAGE'), (1, 'TRANSCLUSION'), (2, 'SITEMAP'), (3, 'ROBOTS');

CREATE TABLE location
(
    origin_id        BIGINT        NOT NULL,
    path_id          BIGINT        NOT NULL,
    path             VARCHAR(4000) NOT NULL,
    location_type_id TINYINT       NOT NULL DEFAULT 0,
    depth            SMALLINT      NOT NULL DEFAULT 0,
    via_origin_id    BIGINT        NULL,
    via_path_id      BIGINT        NULL,
    discovered       BIGINT     NOT NULL,
    last_visit       BIGINT     NULL,
    next_visit       BIGINT     NULL,
    PRIMARY KEY (origin_id, path_id),
    FOREIGN KEY (origin_id) REFERENCES origin (id) ON DELETE CASCADE,
    FOREIGN KEY (location_type_id) REFERENCES location_type
);

create index location_next_visit_index
    on location (origin_id, next_visit, path_id);

CREATE TABLE sitemap_entry
(
    origin_id         BIGINT NOT NULL,
    path_id           BIGINT NOT NULL,
    sitemap_origin_id BIGINT NOT NULL,
    sitemap_path_id   BIGINT NOT NULL,
    changefreq        VARCHAR(16),
    priority          FLOAT,
    lastmod           VARCHAR(40),
    PRIMARY KEY (origin_id, path_id, sitemap_origin_id, sitemap_path_id),
    FOREIGN KEY (origin_id, path_id) REFERENCES location,
    FOREIGN KEY (sitemap_origin_id, sitemap_path_id) REFERENCES location
);

CREATE TABLE warc
(
    id      BINARY(16) NOT NULL PRIMARY KEY,
    path    TEXT      NOT NULL,
    created BIGINT NOT NULL
);

CREATE TABLE method (
    id TINYINT NOT NULL PRIMARY KEY,
    method VARCHAR(64) NOT NULL UNIQUE
);

INSERT INTO method (id, method)
VALUES (0, 'GET'),
       (1, 'POST'),
       (2, 'PUT'),
       (3, 'DELETE'),
       (4, 'PATCH'),
       (5, 'OPTIONS'),
       (6, 'HEAD');

CREATE TABLE content_type
(
    id           TINYINT      NOT NULL PRIMARY KEY,
    content_type VARCHAR(128) NOT NULL UNIQUE
);

INSERT INTO content_type (id, content_type)
VALUES (0, 'text/html'),
       (1, 'image/jpeg'),
       (2, 'image/png'),
       (3, 'image/gif'),
       (4, 'text/css'),
       (5, 'application/javascript'),
       (6, 'application/pdf'),
       (7, 'text/plain'),
       (8, 'application/json'),
       (9, 'application/octet-stream');

CREATE TABLE visit
(
    origin_id               BIGINT     NOT NULL,
    path_id                 BIGINT     NOT NULL,
    date                    BIGINT     NOT NULL,
    method_id               TINYINT    NOT NULL,
    status                  SMALLINT   NOT NULL,
    content_type_id         TINYINT    NOT NULL,
    content_length          BIGINT     NOT NULL,
    warc_id                 BINARY(16) NULL,
    request_position        BIGINT     NOT NULL,
    request_length          BIGINT     NOT NULL,
    request_payload_digest  BINARY(8)  NULL,
    response_position       BIGINT     NOT NULL,
    response_length         BIGINT     NOT NULL,
    response_payload_digest BINARY(8)  NULL,
    revisit_of_date         BIGINT     NULL,
    PRIMARY KEY (origin_id, path_id, date),
    FOREIGN KEY (origin_id, path_id) REFERENCES location ON DELETE CASCADE,
    FOREIGN KEY (method_id) REFERENCES method,
    FOREIGN KEY (content_type_id) REFERENCES content_type,
    FOREIGN KEY (origin_id, path_id, revisit_of_date) REFERENCES visit
);

CREATE TABLE session
(
    id         VARCHAR(30)  NOT NULL PRIMARY KEY,
    username   VARCHAR(128) NULL,
    role       VARCHAR(64)  NOT NULL DEFAULT 'anonymous',
    oidc_state VARCHAR(30)  NOT NULL,
    expiry     BIGINT    NOT NULL
);

CREATE TABLE screenshot_cache
(
    origin_id  BIGINT    NOT NULL,
    path_id    BIGINT    NOT NULL,
    date       BIGINT NOT NULL,
    screenshot BLOB      NOT NULL,
    PRIMARY KEY (origin_id, path_id, date),
    FOREIGN KEY (origin_id, path_id, date) REFERENCES visit ON DELETE CASCADE
);

CREATE TABLE schedule
(
    id           BIGINT       NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL UNIQUE,
    years        INT          NOT NULL DEFAULT 0,
    months       INT          NOT NULL DEFAULT 0,
    days         INT          NOT NULL DEFAULT 0,
    days_of_week TINYINT      NOT NULL DEFAULT 0,
    hours_of_day INT          NOT NULL DEFAULT 0
);

INSERT INTO schedule (id, name, years, months, days, days_of_week, hours_of_day)
VALUES (0, 'Daily', 0, 0, 1, 0, 0),
       (1, 'Weekly', 0, 0, 7, 0, 0),
       (2, 'Fortnightly', 0, 0, 14, 0, 0),
       (3, '2-Monthly', 0, 2, 0, 0, 0),
       (4, 'Quarterly', 0, 3, 0, 0, 0),
       (5, 'Half-Annual', 0, 6, 0, 0, 0),
       (6, '9-Monthly', 0, 9, 0, 0, 0),
       (7, 'Annual', 1, 0, 0, 0, 0),
       (8, '18-Monthly', 0, 18, 0, 0, 0),
       (9, 'Biennial', 2, 0, 0, 0, 0),
       (10, 'Twice-Daily', 0, 0, 0, 0, 1048832),
       (11, 'Weekdays', 0, 0, 0, 31, 0),
       (12, 'Weekends', 0, 0, 0, 96, 0);


CREATE TABLE rule
(
    origin_id   BIGINT       NOT NULL,
    pattern     VARCHAR(512) NOT NULL,
    schedule_id BIGINT       NULL,
    PRIMARY KEY (origin_id, pattern),
    FOREIGN KEY (origin_id) REFERENCES origin ON DELETE CASCADE,
    FOREIGN KEY (schedule_id) REFERENCES schedule
);

CREATE TABLE config
(
    name VARCHAR(128) NOT NULL PRIMARY KEY,
    value VARCHAR(4096)
);