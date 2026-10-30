-- Orders per trip (additive) and Wolt's official day statistics.
ALTER TABLE "CollectorTrip" ADD COLUMN "orderEvidence" TEXT;
ALTER TABLE "CollectorTrip" ADD COLUMN "ordersLow" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "ordersHigh" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "cardOrderCount" INTEGER;
ALTER TABLE "CollectorTrip" ADD COLUMN "timelineChanged" BOOLEAN;

CREATE TABLE "OfficialDayStat" (
    "day" TEXT NOT NULL PRIMARY KEY,
    "completedDeliveries" INTEGER NOT NULL,
    "earningsAgorot" INTEGER,
    "earningsWithoutTipsAgorot" INTEGER,
    "tipsAgorot" INTEGER,
    "distanceMeters" INTEGER,
    "distanceDefinition" TEXT NOT NULL DEFAULT 'UNVERIFIED',
    "source" TEXT NOT NULL,
    "recordedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL
);
