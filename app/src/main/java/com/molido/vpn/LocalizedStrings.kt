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
        "Settings" to "الإعدادات", "Language" to "اللغة", "Dynamic DNS (DDNS)" to "نظام DNS الديناميكي", "Not configured" to "غير مُعدّ", "Follow system" to "اتباع لغة النظام",
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
        "IKEv2" to "IKEv2", "SSTP" to "SSTP", "No backup method on this network" to "لا توجد طريقة احتياطية على هذه الشبكة",
        "Menu" to "القائمة", "Connection" to "الاتصال", "Tools" to "الأدوات",
        "Personalize" to "التخصيص", "Support" to "الدعم", "Search menus" to "البحث في القوائم",
        "No matching menu" to "لا توجد عناصر مطابقة", "Refresh server list" to "تحديث قائمة الخوادم",
        "Refreshing server list…" to "جارٍ تحديث قائمة الخوادم…", "Connection logs" to "سجلات الاتصال",
        "Connection check" to "فحص الاتصال", "Copy problem report" to "نسخ تقرير المشكلة",
        "No internet-capable network detected. Check Wi-Fi or mobile data." to "لم يتم العثور على شبكة متصلة بالإنترنت. تحقق من Wi-Fi أو بيانات الهاتف.",
        "No clear cause found. Copy the problem report or try another method." to "لم يتم تحديد سبب واضح. انسخ تقرير المشكلة أو جرّب طريقة أخرى.",
        "The cached list is empty. Try refreshing; bundled backups may still be available." to "القائمة المخزنة فارغة. حاول التحديث؛ قد تتوفر خوادم احتياطية مضمّنة.",
        "Gaming mode" to "وضع الألعاب", "Ping ms" to "زمن الاستجابة (مللي ثانية)",
        "Download Mb/s" to "سرعة التنزيل ميغابت/ث", "Upload Mb/s" to "سرعة الرفع ميغابت/ث",
        "Privacy" to "الخصوصية", "Security" to "الأمان", "Language" to "اللغة",
        "Follow system" to "اتباع لغة النظام", "Arabic" to "العربية", "Turkish" to "التركية",
        "WireGuard" to "WireGuard", "IKEv2" to "IKEv2", "SSTP" to "SSTP",
    )
    private val turkish = mapOf(
        "Connect" to "Bağlan", "Disconnect" to "Bağlantıyı kes", "Connected" to "Bağlandı",
        "Disconnected" to "Bağlantı kesildi", "Connecting…" to "Bağlanıyor…",
        "Settings" to "Ayarlar", "Language" to "Dil", "Dynamic DNS (DDNS)" to "Dinamik DNS", "Not configured" to "Yapılandırılmadı", "Follow system" to "Sistem dilini kullan",
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
        "IKEv2" to "IKEv2", "SSTP" to "SSTP", "No backup method on this network" to "Bu ağda yedek yöntem yok",
        "Menu" to "Menü", "Connection" to "Bağlantı", "Tools" to "Araçlar",
        "Personalize" to "Kişiselleştir", "Support" to "Destek", "Search menus" to "Menülerde ara",
        "No matching menu" to "Eşleşen menü yok", "Refresh server list" to "Sunucu listesini yenile",
        "Refreshing server list…" to "Sunucu listesi yenileniyor…", "Connection logs" to "Bağlantı günlükleri",
        "Connection check" to "Bağlantı kontrolü", "Copy problem report" to "Sorun raporunu kopyala",
        "No internet-capable network detected. Check Wi-Fi or mobile data." to "İnternet bağlantısı olan ağ bulunamadı. Wi-Fi veya mobil veriyi kontrol edin.",
        "No clear cause found. Copy the problem report or try another method." to "Belirgin bir neden bulunamadı. Sorun raporunu kopyalayın veya başka bir yöntem deneyin.",
        "The cached list is empty. Try refreshing; bundled backups may still be available." to "Önbellekteki liste boş. Yenilemeyi deneyin; uygulama içi yedekler hâlâ kullanılabilir olabilir.",
        "Gaming mode" to "Oyun modu", "Ping ms" to "Ping (ms)",
        "Download Mb/s" to "İndirme Mb/sn", "Upload Mb/s" to "Yükleme Mb/sn",
        "Privacy" to "Gizlilik", "Security" to "Güvenlik", "Language" to "Dil",
        "Follow system" to "Sistem dilini kullan", "Arabic" to "Arapça", "Turkish" to "Türkçe",
        "WireGuard" to "WireGuard", "IKEv2" to "IKEv2", "SSTP" to "SSTP",
    )

    fun translate(language: String, key: String): String? = when (language) {
        "ar" -> arabic[key]
        "tr" -> turkish[key]
        else -> null
    }

    fun contains(language: String, key: String): Boolean = translate(language, key) != null
}
