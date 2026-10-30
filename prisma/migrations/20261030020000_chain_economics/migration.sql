-- Chain economics and offer context (additive).
ALTER TABLE "CollectorTrip" ADD COLUMN "offerSeenAtUtc" DATETIME;
ALTER TABLE "CollectorTrip" ADD COLUMN "offerCell" TEXT;
ALTER TABLE "CollectorTrip" ADD COLUMN "acceptedEarliestAtUtc" DATETIME;
ALTER TABLE "CollectorTrip" ADD COLUMN "activeOrdersBefore" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "newOrdersConfidence" TEXT;
ALTER TABLE "CollectorTrip" ADD COLUMN "routeBeforeJson" TEXT;
ALTER TABLE "CollectorTrip" ADD COLUMN "routeAfterJson" TEXT;
ALTER TABLE "CollectorTrip" ADD COLUMN "newPickupStops" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "newDropoffStopsByShape" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "ambiguousDropoffStops" INTEGER;

CREATE TABLE "CollectorChain" (
    "chainKey" TEXT NOT NULL PRIMARY KEY,
    "startEarliestAtUtc" DATETIME,
    "startLatestAtUtc" DATETIME NOT NULL,
    "endEarliestAtUtc" DATETIME,
    "endLatestAtUtc" DATETIME,
    "endEvidence" TEXT NOT NULL,
    "tripIndexesJson" TEXT NOT NULL,
    "offers" INTEGER NOT NULL,
    "revenueAgorot" INTEGER NOT NULL,
    "ordersLow" INTEGER,
    "ordersHigh" INTEGER,
    "gpsSamples" INTEGER NOT NULL,
    "gpsMaxGapMs" INTEGER,
    "gpsCoverage" REAL,
    "gpsPathKm" REAL,
    "gpsSpeedKm" REAL,
    "gpsDistanceReliable" BOOLEAN NOT NULL,
    "missingJson" TEXT NOT NULL,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL
);
CREATE INDEX "CollectorChain_startLatestAtUtc_idx" ON "CollectorChain"("startLatestAtUtc");
