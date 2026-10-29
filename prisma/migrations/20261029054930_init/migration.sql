-- CreateTable
CREATE TABLE "ImportBatch" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "sourceFileName" TEXT NOT NULL,
    "sourceHash" TEXT NOT NULL,
    "reportNumbers" TEXT NOT NULL,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "rowCount" INTEGER NOT NULL,
    "insertedCount" INTEGER NOT NULL,
    "skippedCount" INTEGER NOT NULL
);

-- CreateTable
CREATE TABLE "DeliveryRecord" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "importBatchId" TEXT NOT NULL,
    "externalRowKey" TEXT NOT NULL,
    "reportNumber" TEXT,
    "eventDate" TEXT NOT NULL,
    "eventTime" TEXT NOT NULL,
    "eventTimestampLocal" TEXT NOT NULL,
    "weekday" INTEGER NOT NULL,
    "merchantRaw" TEXT NOT NULL,
    "notes" TEXT NOT NULL,
    "amountAgorot" INTEGER NOT NULL,
    "type" TEXT NOT NULL,
    "typeAutoClassified" TEXT NOT NULL,
    "typeManuallySet" BOOLEAN NOT NULL DEFAULT false,
    "isBundle" BOOLEAN NOT NULL,
    "sourceFileName" TEXT NOT NULL,
    "rowIndex" INTEGER NOT NULL,
    CONSTRAINT "DeliveryRecord_importBatchId_fkey" FOREIGN KEY ("importBatchId") REFERENCES "ImportBatch" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "Shift" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "date" TEXT NOT NULL,
    "startTime" TEXT NOT NULL,
    "endTime" TEXT NOT NULL,
    "startedAt" TEXT NOT NULL,
    "endedAt" TEXT NOT NULL,
    "areaText" TEXT NOT NULL,
    "distanceKm" REAL,
    "vehicleCostPerKmAgorot" INTEGER,
    "followedRecommendation" BOOLEAN NOT NULL DEFAULT false,
    "notes" TEXT,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- CreateTable
CREATE TABLE "ShiftRecordLink" (
    "shiftId" TEXT NOT NULL,
    "recordId" TEXT NOT NULL,
    "manuallyConfirmed" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY ("shiftId", "recordId"),
    CONSTRAINT "ShiftRecordLink_shiftId_fkey" FOREIGN KEY ("shiftId") REFERENCES "Shift" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "ShiftRecordLink_recordId_fkey" FOREIGN KEY ("recordId") REFERENCES "DeliveryRecord" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "Experiment" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "title" TEXT NOT NULL,
    "hypothesisText" TEXT NOT NULL,
    "targetMetric" TEXT NOT NULL DEFAULT 'DELIVERY_PER_SHIFT_HOUR_EX_EXTRAS',
    "startDate" TEXT,
    "insightId" TEXT,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "status" TEXT NOT NULL DEFAULT 'PLANNED',
    CONSTRAINT "Experiment_insightId_fkey" FOREIGN KEY ("insightId") REFERENCES "Insight" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "ExperimentShift" (
    "experimentId" TEXT NOT NULL,
    "shiftId" TEXT NOT NULL,
    "group" TEXT NOT NULL,

    PRIMARY KEY ("experimentId", "shiftId"),
    CONSTRAINT "ExperimentShift_experimentId_fkey" FOREIGN KEY ("experimentId") REFERENCES "Experiment" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "ExperimentShift_shiftId_fkey" FOREIGN KEY ("shiftId") REFERENCES "Shift" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "Insight" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "kind" TEXT NOT NULL,
    "insightKey" TEXT NOT NULL,
    "generatedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "filterSnapshotJson" TEXT NOT NULL,
    "metricJson" TEXT NOT NULL,
    "rationale" TEXT NOT NULL,
    "sampleSize" INTEGER NOT NULL,
    "status" TEXT NOT NULL
);

-- CreateTable
CREATE TABLE "Setting" (
    "key" TEXT NOT NULL PRIMARY KEY,
    "valueJson" TEXT NOT NULL
);

-- CreateIndex
CREATE INDEX "ImportBatch_sourceHash_idx" ON "ImportBatch"("sourceHash");

-- CreateIndex
CREATE UNIQUE INDEX "DeliveryRecord_externalRowKey_key" ON "DeliveryRecord"("externalRowKey");

-- CreateIndex
CREATE INDEX "DeliveryRecord_eventDate_eventTime_idx" ON "DeliveryRecord"("eventDate", "eventTime");

-- CreateIndex
CREATE INDEX "DeliveryRecord_type_idx" ON "DeliveryRecord"("type");

-- CreateIndex
CREATE INDEX "DeliveryRecord_reportNumber_idx" ON "DeliveryRecord"("reportNumber");

-- CreateIndex
CREATE INDEX "DeliveryRecord_merchantRaw_idx" ON "DeliveryRecord"("merchantRaw");

-- CreateIndex
CREATE UNIQUE INDEX "ShiftRecordLink_recordId_key" ON "ShiftRecordLink"("recordId");
