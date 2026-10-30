-- Precise shift track (insert-only).
CREATE TABLE "CollectorTrackPoint" (
    "shiftId" TEXT NOT NULL,
    "atUtc" DATETIME NOT NULL,
    "lat" REAL NOT NULL,
    "lon" REAL NOT NULL,
    "accuracyM" REAL,
    "speedMps" REAL,

    PRIMARY KEY ("shiftId", "atUtc")
);
CREATE INDEX "CollectorTrackPoint_atUtc_idx" ON "CollectorTrackPoint"("atUtc");
