package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test

class LocalizedStringsTest {
    @Test fun arabicAndTurkishCoreLabelsAreTranslated() {
        assertEquals("اتصال", LocalizedStrings.translate("ar", "Connect"))
        assertEquals("Bağlan", LocalizedStrings.translate("tr", "Connect"))
        assertEquals("Ayarlar", LocalizedStrings.translate("tr", "Settings"))
    }

    @Test fun languageSelectorIncludesArabicAndTurkishAndRtlIsCorrect() {
        assertTrue(AppLanguage.SUPPORTED.containsAll(listOf("en", "fa", "zh", "ar", "tr")))
        assertTrue(AppLanguage.isRtl("fa"))
        assertTrue(AppLanguage.isRtl("ar"))
        assertFalse(AppLanguage.isRtl("tr"))
        assertFalse(AppLanguage.isRtl("en"))
    }

    @Test fun untranslatedTextIsExplicitlyAbsentForEnglishFallback() {
        assertNull(LocalizedStrings.translate("ar", "A long unreviewed sentence"))
        assertNull(LocalizedStrings.translate("tr", "A long unreviewed sentence"))
    }
}
