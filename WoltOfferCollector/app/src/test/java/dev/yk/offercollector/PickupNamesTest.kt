package dev.yk.offercollector

import dev.yk.offercollector.core.RawNode
import dev.yk.offercollector.core.SnapshotAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupNamesTest {
    private fun tv(text: String) = RawNode("android.widget.TextView", null, text, null, false, 1)

    // Synthetic card with the SAME line order as the real Wolt card (ru-RU), invented content.
    private fun card(pickupLine: String) = listOf(
        tv("Новое задание"),
        tv("1 доставка"),
        tv("₪27.49"),
        tv("12 мин · 4,5 км"),
        tv(pickupLine), // line 5: restaurant
        tv("Иван Петров"),
        tv("ул. Пример 12, Ришон-ле-Цион 7520001"),
        tv("Принять"),
    )

    @Test fun storesOnlyTheLineBelowTheDistance() {
        val f = SnapshotAnalyzer.analyze("FrameLayout", card("בורגר סאלון | חולון"), false).fields
        assertEquals(listOf("בורגר סאלון | חולון"), f.pickup_names)
        val all = f.toString()
        assertFalse(all.contains("Иван"))
        assertFalse(all.contains("Пример"))
        assertFalse(all.contains("7520001"))
        assertEquals(2, f.card_lines_between_distance_and_address) // name line, customer line, then the address (index 2)
    }

    @Test fun woltStepLabelsAreNotBusinesses() {
        // Seen in the business slot on the 29.10.2026 shift.
        for (label in listOf("Заберите заказы: 2", "Выдача покупателю", "Проверка документов")) {
            val f = SnapshotAnalyzer.analyze("FrameLayout", card(label), false).fields
            assertEquals(label, emptyList<String>(), f.pickup_names)
            assertEquals("WOLT_STEP_LABEL", f.pickup_rejected_reason)
        }
    }

    /** Real card layout seen on the phone at 10:24 (ru-RU): size tag, then restaurant, then its address. */
    private fun cardWithSizeTag(tag: String, name: String) = listOf(
        tv("Новое задание"),
        tv("1 доставка"),
        tv("₪40.16"),
        tv("25 мин · 8,1 км"),
        tv(tag),
        tv(name),
        tv("ул. Пример 12, Ришон-ле-Цион 7520001"),
        tv("Принять"),
    )

    @Test fun skipsPackageSizeTagAndTakesTheRestaurant() {
        // Regression: the real 10:24 offer stored "Тяжелая (более 13 кг)" as the restaurant name.
        val f = SnapshotAnalyzer.analyze("F", cardWithSizeTag("Тяжелая (более 13 кг)", "Вольт Маркет | Ришон Запад"), false).fields
        assertEquals(listOf("Вольт Маркет | Ришон Запад"), f.pickup_names)
        assertEquals("Тяжелая (более 13 кг)", f.package_size)
        for (tag in listOf("Лёгкая (до 5 кг)", "Средняя (5–13 кг)", "Heavy (over 13 kg)")) {
            assertEquals(tag, listOf("Pizza Place"), SnapshotAnalyzer.analyze("F", cardWithSizeTag(tag, "Pizza Place"), false).fields.pickup_names)
        }
    }

    @Test fun restaurantNamesThatLookLikeSizeWordsAreKept() {
        // No size tag on this card: the first line below the distance IS the restaurant.
        for (name in listOf("Light Burger | Holon", "קלמנטינה | ראשון לציון", "Средиземноморская кухня")) {
            assertEquals(name, listOf(name), SnapshotAnalyzer.analyze("F", card(name), false).fields.pickup_names)
        }
    }

    @Test fun keepsBranchNumberAndSplitsMultiplePickups() {
        assertEquals(listOf("194 פיצה | רחובות"), SnapshotAnalyzer.analyze("F", card("194 פיצה | רחובות"), false).fields.pickup_names)
        assertEquals(
            listOf("מוזס שופ | באר יעקב", "גולדה כשר | באר יעקב"),
            SnapshotAnalyzer.analyze("F", card("מוזס שופ | באר יעקב, גולדה כשר | באר יעקב"), false).fields.pickup_names,
        )
    }

    @Test fun unsafeLinesAreNeverStored() {
        for ((line, reason) in listOf(
            "Позвоните 050-1234567" to "PHONE_LIKE",
            "Склад 7520001" to "LONG_NUMBER",
            "a b c d e f g h i j" to "TOO_MANY_WORDS",
        )) {
            val f = SnapshotAnalyzer.analyze("F", card(line), false).fields
            assertTrue(line, f.pickup_names.isEmpty())
            assertEquals(reason, f.pickup_rejected_reason)
        }
    }

    @Test fun nothingOutsideAnOfferCard() {
        // Same lines but no accept button: not an offer card, so no names at all.
        val f = SnapshotAnalyzer.analyze("F", card("Pizza").dropLast(1), false).fields
        assertTrue(f.pickup_names.isEmpty())
    }

    /** Synthetic content in the REAL line order (business name, then its address with a postal code). */
    @Test fun storesTheBusinessAddressDirectlyBelowTheName() {
        val nodes = listOf(tv("Отказаться"), tv("₪27.25"), tv("1 заказ · 3 км"), tv("Pita Basar | Rishon Lezion West"), tv("Rothschild 12, Rishon LeZion 7525001"), tv("Принять"))
        val f = SnapshotAnalyzer.analyze("FrameLayout", nodes, false).fields
        assertEquals(listOf("Pita Basar | Rishon Lezion West"), f.pickup_names)
        assertEquals("Rothschild 12, Rishon LeZion 7525001", f.pickup_address)
    }

    @Test fun noBusinessAddressWhenSomethingSitsBetweenOrSeveralBusinesses() {
        // A person line between the name and an address → nothing (it could be the customer's).
        assertEquals(null, SnapshotAnalyzer.analyze("FrameLayout", card("בורגר סאלון | חולון"), false).fields.pickup_address)
        // Two businesses in one line → which address belongs to which is unknown → nothing.
        val two = listOf(tv("₪40.00"), tv("6 км"), tv("Shufersal | Bat Yam, Pizza Story"), tv("Rothschild 12, Bat Yam 5940001"), tv("Принять"))
        assertEquals(null, SnapshotAnalyzer.analyze("FrameLayout", two, false).fields.pickup_address)
        // A phone-like line is never stored.
        val phone = listOf(tv("₪30.00"), tv("4 км"), tv("Goomba | Rishon LeZion"), tv("050-123-4567"), tv("Принять"))
        assertEquals(null, SnapshotAnalyzer.analyze("FrameLayout", phone, false).fields.pickup_address)
    }
}
