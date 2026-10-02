-- How often a few actions happen on the separation pages, for the phase 3.2 decision
-- (ROADMAP.md, ABD-8). A counter per day and per action, nothing else: no visitor, no
-- address, no identifier, so nothing here can tell two people apart.
CREATE TABLE usage_counts (
    day date NOT NULL,
    event varchar(40) NOT NULL,
    count bigint NOT NULL CHECK (count > 0),
    PRIMARY KEY (day, event)
);
