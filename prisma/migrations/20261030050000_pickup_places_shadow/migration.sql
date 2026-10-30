-- Pickup places (verified pickup area) and insert-only shadow predictions.
CREATE TABLE "CollectorPickupPlace" (
    "key" TEXT NOT NULL PRIMARY KEY,
    "name" TEXT NOT NULL,
    "address" TEXT NOT NULL,
    "firstSeenAt" DATETIME NOT NULL,
    "geoStatus" TEXT NOT NULL,
    "cellId" TEXT,
    "geoNote" TEXT,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL
);

CREATE TABLE "ShadowPrediction" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "offerAtUtc" DATETIME NOT NULL,
    "createdAtUtc" DATETIME NOT NULL,
    "ruleVersion" TEXT NOT NULL,
    "amountAgorot" INTEGER NOT NULL,
    "decision" TEXT NOT NULL,
    "payloadJson" TEXT NOT NULL,
    "importedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ShadowPrediction_offerAtUtc_idx" ON "ShadowPrediction"("offerAtUtc");
