package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TextUtilsTest {

    @Test
    void genderFromCodesWordsAndCivilTitles() {
        assertEquals("male", TextUtils.toFtmGender("M"));
        assertEquals("male", TextUtils.toFtmGender("Monsieur"));
        assertEquals("female", TextUtils.toFtmGender("F"));
        assertEquals("female", TextUtils.toFtmGender("Madame"), "commence par m mais est féminin");
        assertEquals("female", TextUtils.toFtmGender("Mme."));
        assertNull(TextUtils.toFtmGender(" "));
        assertNull(TextUtils.toFtmGender("X"));
    }

    @Test
    void joinNonBlankSkipsEmptyParts() {
        assertEquals("1 Main St, Paris", TextUtils.joinNonBlank(", ", "1 Main St", null, " ", "Paris"));
        assertNull(TextUtils.joinNonBlank(", ", null, ""));
    }
}
