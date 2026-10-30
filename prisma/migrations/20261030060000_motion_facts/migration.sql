-- Motion (GPS speed) and between-chain facts (additive).
ALTER TABLE "CollectorChain" ADD COLUMN "gpsStationaryMs" INTEGER;
ALTER TABLE "CollectorChain" ADD COLUMN "gpsMovingMs" INTEGER;
ALTER TABLE "CollectorChain" ADD COLUMN "firstPickupDoneAtUtc" DATETIME;
ALTER TABLE "CollectorChain" ADD COLUMN "pickupPhaseStationaryMs" INTEGER;
ALTER TABLE "CollectorChain" ADD COLUMN "pickupPhaseMovingMs" INTEGER;
ALTER TABLE "CollectorChain" ADD COLUMN "deliveryPhaseStationaryMs" INTEGER;
ALTER TABLE "CollectorChain" ADD COLUMN "deliveryPhaseMovingMs" INTEGER;
ALTER TABLE "CollectorShift" ADD COLUMN "betweenChainsMs" INTEGER;
ALTER TABLE "CollectorShift" ADD COLUMN "betweenChainsKm" REAL;
ALTER TABLE "CollectorShift" ADD COLUMN "betweenChainsMovingMs" INTEGER;
ALTER TABLE "CollectorShift" ADD COLUMN "shiftStationaryMs" INTEGER;
ALTER TABLE "CollectorShift" ADD COLUMN "shiftMovingMs" INTEGER;
