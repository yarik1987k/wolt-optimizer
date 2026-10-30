-- CreateTable
CREATE TABLE "OfferImportBatch" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "sourceFileName" TEXT NOT NULL,
    "sourceHash" TEXT NOT NULL,
    "exportFormat" TEXT NOT NULL,
    "exportedAtUtc" TEXT NOT NULL,
    "appVersion" TEXT NOT NULL,
    "selectedPackage" TEXT,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "screenEventCount" INTEGER NOT NULL,
    "offerScreenCount" INTEGER NOT NULL,
    "newObservationCount" INTEGER NOT NULL,
    "skippedObservationCount" INTEGER NOT NULL
);

-- CreateTable
CREATE TABLE "OfferObservation" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "screenEventId" TEXT NOT NULL,
    "batchId" TEXT NOT NULL,
    "observedAtUtc" DATETIME NOT NULL,
    "sourcePackage" TEXT NOT NULL,
    "amountAgorot" INTEGER NOT NULL,
    "distanceMeters" INTEGER,
    "durationMin" INTEGER,
    "stopsCount" INTEGER,
    "pickupNamesJson" TEXT NOT NULL,
    "parseVersion" TEXT NOT NULL,
    "coalescedEventCount" INTEGER NOT NULL,
    "offerId" TEXT,
    CONSTRAINT "OfferObservation_batchId_fkey" FOREIGN KEY ("batchId") REFERENCES "OfferImportBatch" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "OfferObservation_offerId_fkey" FOREIGN KEY ("offerId") REFERENCES "Offer" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "Offer" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "offerKey" TEXT NOT NULL,
    "source" TEXT NOT NULL DEFAULT 'ANDROID_COLLECTOR',
    "sourcePackage" TEXT NOT NULL,
    "firstSeenAtUtc" DATETIME NOT NULL,
    "lastSeenAtUtc" DATETIME NOT NULL,
    "onScreenSeconds" INTEGER NOT NULL,
    "amountAgorot" INTEGER NOT NULL,
    "amountBasis" TEXT NOT NULL DEFAULT 'AS_DISPLAYED_VAT_UNKNOWN',
    "distanceMeters" INTEGER,
    "durationMin" INTEGER,
    "stopsCount" INTEGER,
    "pickupNamesJson" TEXT NOT NULL DEFAULT '[]',
    "observationCount" INTEGER NOT NULL,
    "detection" TEXT NOT NULL,
    "detectionConfidence" REAL NOT NULL,
    "verification" TEXT NOT NULL DEFAULT 'UNVERIFIED',
    "verificationSource" TEXT,
    "verifiedAt" DATETIME,
    "outcome" TEXT NOT NULL DEFAULT 'UNKNOWN',
    "outcomeSource" TEXT,
    "completed" BOOLEAN,
    "shiftId" TEXT,
    "paymentRecordId" TEXT,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL,
    CONSTRAINT "Offer_shiftId_fkey" FOREIGN KEY ("shiftId") REFERENCES "Shift" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "Offer_paymentRecordId_fkey" FOREIGN KEY ("paymentRecordId") REFERENCES "DeliveryRecord" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateIndex
CREATE INDEX "OfferImportBatch_sourceHash_idx" ON "OfferImportBatch"("sourceHash");

-- CreateIndex
CREATE UNIQUE INDEX "OfferObservation_screenEventId_key" ON "OfferObservation"("screenEventId");

-- CreateIndex
CREATE INDEX "OfferObservation_observedAtUtc_idx" ON "OfferObservation"("observedAtUtc");

-- CreateIndex
CREATE UNIQUE INDEX "Offer_offerKey_key" ON "Offer"("offerKey");

-- CreateIndex
CREATE UNIQUE INDEX "Offer_paymentRecordId_key" ON "Offer"("paymentRecordId");

-- CreateIndex
CREATE INDEX "Offer_firstSeenAtUtc_idx" ON "Offer"("firstSeenAtUtc");

-- CreateIndex
CREATE INDEX "Offer_detection_idx" ON "Offer"("detection");
