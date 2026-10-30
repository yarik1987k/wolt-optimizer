-- ETA validation and acceptance-snapshot facts (additive).
ALTER TABLE "CollectorTrip" ADD COLUMN "dropScreenBeforeAgeMs" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "routeAfterDelayMs" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "completionsBetweenBeforeAccept" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "carriedTargetShiftMin" REAL;
ALTER TABLE "CollectorTrip" ADD COLUMN "routeChangedOtherReasons" BOOLEAN;

CREATE TABLE "CollectorEtaCheck" (
    "completedAtUtc" DATETIME NOT NULL PRIMARY KEY,
    "identity" TEXT NOT NULL,
    "orderTimesJson" TEXT NOT NULL,
    "targetAtUtc" DATETIME,
    "routeFirstSeenAtUtc" DATETIME,
    "routeTargetFirstAtUtc" DATETIME,
    "routeTargetLastAtUtc" DATETIME,
    "errorVsDropScreenMin" REAL,
    "errorVsFirstRouteMin" REAL,
    "errorVsLastRouteMin" REAL,
    "leadMin" REAL,
    "notesJson" TEXT NOT NULL,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL
);
