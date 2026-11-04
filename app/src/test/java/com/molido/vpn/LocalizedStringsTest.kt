package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test

class LocalizedStringsTest {
    @Test fun arabicAndTurkishCoreLabelsAreTranslated() {
        assertEquals("اتصال", LocalizedStrings.translate("ar", "Connect"))
        assertEquals("Bağlan", LocalizedStrings.translate("tr", "Connect"))
        assertEquals("Ayarlar", LocalizedStrings.translate("tr", "Settings"))
    }

    @Test fun untranslatedTextIsExplicitlyAbsentForEnglishFallback() {
        assertNull(LocalizedStrings.translate("ar", "A long unreviewed sentence"))
        assertNull(LocalizedStrings.translate("tr", "A long unreviewed sentence"))
    }
}
