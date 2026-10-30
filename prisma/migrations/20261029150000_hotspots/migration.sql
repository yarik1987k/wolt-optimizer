-- CreateTable
CREATE TABLE "CollectorShift" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "startedAtUtc" DATETIME NOT NULL,
    "endedAtUtc" DATETIME,
    "endReason" TEXT,
    "locationConsent" BOOLEAN NOT NULL,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL
);

-- CreateTable
CREATE TABLE "LocationSample" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "shiftId" TEXT NOT NULL,
    "observedAtUtc" DATETIME NOT NULL,
    "trigger" TEXT NOT NULL,
    "stateCandidate" TEXT,
    "status" TEXT NOT NULL,
    "unknownReason" TEXT,
    "cellId" TEXT,
    "cellCenterLat" REAL,
    "cellCenterLon" REAL,
    "accuracyM" INTEGER,
    "fixAgeS" INTEGER,
    "provider" TEXT,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- CreateTable
CREATE TABLE "CollectorDeliveryEvent" (
    "eventKey" TEXT NOT NULL PRIMARY KEY,
    "type" TEXT NOT NULL,
    "evidence" TEXT NOT NULL,
    "actionAtUtc" DATETIME NOT NULL,
    "action" TEXT NOT NULL,
    "confirmedAtUtc" DATETIME,
    "confirmation" TEXT NOT NULL,
    "tripIndex" INTEGER,
    "ruleValidated" BOOLEAN NOT NULL,
    "path" TEXT NOT NULL,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- CreateTable
CREATE TABLE "CollectorTrip" (
    "tripKey" TEXT NOT NULL PRIMARY KEY,
    "startedAtUtc" DATETIME NOT NULL,
    "startEvidence" TEXT NOT NULL,
    "completedAtUtc" DATETIME,
    "endEvidence" TEXT NOT NULL,
    "orders" INTEGER NOT NULL,
    "pickups" INTEGER NOT NULL,
    "dropoffs" INTEGER NOT NULL,
    "offerAmountsJson" TEXT NOT NULL,
    "expectedDropoffs" INTEGER,
    "endNote" TEXT,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL
);

-- CreateIndex
CREATE INDEX "CollectorShift_startedAtUtc_idx" ON "CollectorShift"("startedAtUtc");

-- CreateIndex
CREATE INDEX "LocationSample_observedAtUtc_idx" ON "LocationSample"("observedAtUtc");

-- CreateIndex
CREATE INDEX "LocationSample_shiftId_idx" ON "LocationSample"("shiftId");

-- CreateIndex
CREATE INDEX "LocationSample_cellId_idx" ON "LocationSample"("cellId");

-- CreateIndex
CREATE INDEX "CollectorDeliveryEvent_actionAtUtc_idx" ON "CollectorDeliveryEvent"("actionAtUtc");

-- CreateIndex
CREATE INDEX "CollectorDeliveryEvent_type_idx" ON "CollectorDeliveryEvent"("type");

-- CreateIndex
CREATE INDEX "CollectorTrip_startedAtUtc_idx" ON "CollectorTrip"("startedAtUtc");

