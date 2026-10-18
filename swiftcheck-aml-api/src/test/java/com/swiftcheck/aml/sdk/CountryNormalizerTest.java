package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CountryNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Afghanistan|af",
            "AF|af",
            "AFG|af",
            "Iran (Islamic Republic of)|ir",
            "Democratic People's Republic of Korea|kp",
            "Republic of Korea|kr",
            "Democratic Republic of the Congo|cd",
            "Congo|cg",
            "Syrian Arab Republic|sy",
            "Russian Federation|ru",
            "Fédération de Russie|ru",
            "Viet Nam|vn",
            "United Kingdom of Great Britain and Northern Ireland|gb",
            "United States of America|us",
            "State of Palestine|ps",
            "Türkiye|tr",
            "Turkey|tr",
            "China, Hong Kong Special Administrative Region|hk",
            "Côte d’Ivoire|ci",
            "Guinea-Bissau|gw",
            "MALI|ml",
            "Yémen|ye",
            "Arabie saoudite|sa",
            "Luxembourg|lu",
            "Maroc|ma",
            "britannique|gb",
            "française|fr",
            "tunisienne|tn",
            "Bosna|ba",
            "former Soviet Union|suhh",
            "Kosovo|xk",
            "CS|csxx",
            "YU|yucs",
            "Former USSR (now Belarus)|by",
            "Ukrainian SSR, currently Ukraine|ua",
            "Kazakh SSR|suhh",
            "Occupied Palestinian Territories|ps",
            "Trinidad and Tobago|tt",
            "Bosnia and Herzegovina|ba",
            "Antigua and Barbuda|ag",
            "The Gambia|gm"
    })
    void normalizesToIso2(String raw, String expected) {
        assertEquals(expected, CountryNormalizer.toIsoCode(raw));
    }

    @Test
    void rejectsDatesAndUnknownValues() {
        assertNull(CountryNormalizer.toIsoCode("2003-09-03"));
        assertNull(CountryNormalizer.toIsoCode("Royaume de Jérusalem"));
        assertNull(CountryNormalizer.toIsoCode("Mercie"));
        assertNull(CountryNormalizer.toIsoCode(null));
    }

    @Test
    void detectsNullMarkers() {
        assertTrue(CountryNormalizer.isUnknownValue("n.d."));
        assertTrue(CountryNormalizer.isUnknownValue("  "));
        assertTrue(CountryNormalizer.isUnknownValue("Unknown"));
    }
}
