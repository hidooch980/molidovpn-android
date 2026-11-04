package com.molido.vpn

/**
 * Reviewed core navigation/status translations for Arabic and Turkish.
 * Untranslated long-tail strings intentionally fall back to English rather than
 * showing misleading or machine-generated wording. RTL layout is handled separately.
 */
internal object LocalizedStrings {
    private val arabic = mapOf(
        "Connect" to "اتصال", "Disconnect" to "قطع الاتصال", "Connected" to "متصل",
        "Disconnected" to "غير متصل", "Connecting…" to "جارٍ الاتصال…",
        "Settings" to "الإعدادات", "Language" to "اللغة", "Follow system" to "اتباع لغة النظام",
        "English" to "الإنجليزية",
        "Uses a supported device language; English otherwise" to "استخدام لغة الجهاز المدعومة؛ وإلا فالإنجليزية", "فارسی" to "الفارسية", "中文" to "الصينية",
        "Arabic" to "العربية", "Turkish" to "التركية", "VPN" to "الشبكة الخاصة الافتراضية",
        "Smart connect" to "الاتصال الذكي", "Checking which methods can reach out…" to "جارٍ فحص طرق الاتصال…",
        "Connecting with the best…" to "جارٍ الاتصال بأفضل مسار…",
        "Connected with the best method" to "تم الاتصال بأفضل طريقة",
        "No method worked on this network" to "لم تنجح أي طريقة على هذه الشبكة",
        "Winner" to "الأفضل", "Ping ms" to "زمن الاستجابة (مللي ثانية)",
        "Download Mb/s" to "التنزيل ميغابت/ث", "Upload Mb/s" to "الرفع ميغابت/ث",
        "Refresh" to "تحديث", "Cancel" to "إلغاء", "Save" to "حفظ",
        "Delete" to "حذف", "Retry" to "إعادة المحاولة", "Error" to "خطأ",
        "Warning" to "تحذير", "Privacy" to "الخصوصية", "Security" to "الأمان",
        "Gaming mode" to "وضع الألعاب", "WireGuard" to "WireGuard",
        "IKEv2" to "IKEv2", "SSTP" to "SSTP", "No backup method on this network" to "لا توجد طريقة احتياطية على هذه الشبكة"
    )
    private val turkish = mapOf(
        "Connect" to "Bağlan", "Disconnect" to "Bağlantıyı kes", "Connected" to "Bağlandı",
        "Disconnected" to "Bağlantı kesildi", "Connecting…" to "Bağlanıyor…",
        "Settings" to "Ayarlar", "Language" to "Dil", "Follow system" to "Sistem dilini kullan",
        "English" to "İngilizce",
        "Uses a supported device language; English otherwise" to "Desteklenen cihaz dili kullanılır; aksi hâlde İngilizce", "فارسی" to "Farsça", "中文" to "Çince",
        "Arabic" to "Arapça", "Turkish" to "Türkçe", "VPN" to "VPN",
        "Smart connect" to "Akıllı bağlantı", "Checking which methods can reach out…" to "Bağlantı yöntemleri kontrol ediliyor…",
        "Connecting with the best…" to "En iyi yöntemle bağlanılıyor…",
        "Connected with the best method" to "En iyi yöntemle bağlandı",
        "No method worked on this network" to "Bu ağda hiçbir yöntem çalışmadı",
        "Winner" to "En iyi", "Ping ms" to "Ping (ms)",
        "Download Mb/s" to "İndirme Mb/sn", "Upload Mb/s" to "Yükleme Mb/sn",
        "Refresh" to "Yenile", "Cancel" to "İptal", "Save" to "Kaydet",
        "Delete" to "Sil", "Retry" to "Yeniden dene", "Error" to "Hata",
        "Warning" to "Uyarı", "Privacy" to "Gizlilik", "Security" to "Güvenlik",
        "Gaming mode" to "Oyun modu", "WireGuard" to "WireGuard",
        "IKEv2" to "IKEv2", "SSTP" to "SSTP", "No backup method on this network" to "Bu ağda yedek yöntem yok"
    )

    fun translate(language: String, key: String): String? = when (language) {
        "ar" -> arabic[key]
        "tr" -> turkish[key]
        else -> null
    }

    fun contains(language: String, key: String): Boolean = translate(language, key) != null
}
