package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DateNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "1971,1971",
            "1971-03,1971-03",
            "1971-3,1971-03",
            "1971-03-05,1971-03-05",
            "1971-03-05T00:00:00Z,1971-03-05",
            "2015-03-27-04:00,2015-03-27",
            "2015-03-27+0200,2015-03-27",
            "2015-03-27Z,2015-03-27",
            "1971/03/05,1971-03-05",
            "05/03/1971,1971-03-05",
            "05.03.1971,1971-03-05",
            "03/1971,1971-03"
    })
    void normalizesToIsoPrefix(String raw, String expected) {
        assertEquals(expected, DateNormalizer.toIsoDate(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "n.d.", "Afghanistan", "1971-02-30", "1971-13", "0075", "circa 1970", "31/31/1971"})
    void rejectsInvalidValues(String raw) {
        assertNull(DateNormalizer.toIsoDate(raw));
    }
}
