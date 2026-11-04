# مرجع مشخصات محصول Molido Mesh AI

سند مرجع مادر محصول و معماری در مخزن اصلی نگهداری می‌شود:

- [سند مرجع مادر فارسی — MASTER-SPECIFICATION-FA.md](https://github.com/hidooch980/molidovpn/blob/ai-provider-independence-2026-11/docs/MASTER-SPECIFICATION-FA.md)
- [مخزن اصلی Molido Mesh AI](https://github.com/hidooch980/molidovpn)

این فایل عمداً فقط یک ارجاع است تا دو نسخهٔ مستقل و متناقض از مشخصات محصول ایجاد نشود. پیش از تغییر در Android، سند مادر را بررسی کنید. مشخصات محصول به معنی پیاده‌سازی‌شدن قابلیت نیست؛ وضعیت واقعی باید با کد، تست و CI همین مخزن تأیید شود.

قابلیت‌های مهمی که باید هنگام توسعهٔ Android در نظر گرفته شوند: اتصال Express/Instant Connect، پروفایل مستقل کیفیت شبکه برای هر دستگاه، انتخاب Gateway و مسیر بر اساس اندازه‌گیری واقعی، failover سریع، یک VpnService/TUN مرکزی، امنیت DNS/IP، حفاظت از حریم خصوصی، مدیریت مصرف منابع، و به‌روزرسانی امضاشده با حفظ کلید انتشار.


## وضعیت پیاده‌سازی Android — Device-aware Instant Connect

در شاخهٔ کاری Android، بخش اولیهٔ پروفایل شبکهٔ دستگاه به Smart Connect متصل شده است:

- انتخاب uplink فیزیکی با اولویت شبکهٔ فعال، اعتبارسنجی سیستم‌عامل و default route؛ رابط VPN از اندازه‌گیری uplink حذف می‌شود.
- تشخیص نوع اتصال Wi-Fi، cellular، Ethernet و سایر شبکه‌ها.
- استفاده از وضعیت validated، metered و roaming و hintهای پهنای باند سیستم‌عامل؛ hintها throughput واقعی تلقی نمی‌شوند.
- محدودکردن concurrency و حجم probe روی اینترنت نامعتبر، metered، roaming، کند یا ناشناخته.
- ثبت محلی و مستقل به‌ازای کلید شبکه از RTT میانه، jitter میانه، packet loss، throughput اندازه‌گیری‌شده و زمان آخرین نمونه.
- ادغام تدریجی نمونه‌ها با EWMA؛ دادهٔ ناموجود، مقدار معتبر قبلی را پاک نمی‌کند.
- ذخیره‌سازی فقط در SharedPreferences محلی؛ هیچ داده‌ای به سرور یا سرویس AI ارسال نمی‌شود.

این بخش **Partial** است: پروفایل‌ساز و سیاست محدودسازی probe به چرخهٔ Smart Connect متصل شده‌اند؛ استفاده از پروفایل تاریخی برای وزن‌دهی نهایی Gateway/route، تست handover روی گوشی واقعی، و اندازه‌گیری مستقل زمان تا اولین بستهٔ موفق هنوز gateهای جداگانه دارند. این commit به‌تنهایی اثبات‌کنندهٔ سرعت یا نبود DNS/IP leak نیست.


### Old/new network data validation and handover protection
- Merge old and new quality observations with bounded EWMA; accumulate successful/total probe counters with a hard cap of 10,000.
- Discard a Smart-trial observation if the physical network key changed during the trial, preventing Wi-Fi/mobile handover samples from contaminating the prior network profile.
- Reject persisted profiles older than seven days, implausibly future-dated records, inconsistent success/total counters, packet-loss values outside 0..1, and impossible bandwidth hints.
- CI unit tests cover old/new sample merging, fresh versus stale history, handover attribution guard, bounded counters and corrupt persisted data.
- Validation remains pending until the latest Android CI run completes; no APK release or production-device validation is implied by these commits.


## دسترسی مقصدهای ایرانی هنگام روشن بودن VPN — وضعیت Android

**وضعیت: Partial؛ وجود rule در config به معنی تأیید دسترسی عملی همهٔ سایت‌ها نیست.**

بررسی کد فعلی `ShardConfigs.kt` نشان می‌دهد بخشی از رفتار از قبل در Smart Split و پیکربندی node-only وجود دارد:
- دامنه‌های `.ir` و در صورت وجود geo assets، دستهٔ `geosite:category-ir` به outbound مستقیم `direct-plain` هدایت می‌شوند.
- در Smart Split، مقصدهای `geoip:ir` و private IPها نیز rule مستقیم دارند.
- DNS برای دستهٔ ایرانی در resolver محلی سیستم تنظیم شده است؛ این مسیر باید همراه با route مقصد و بدون fallback ناخواسته بررسی شود.
- ترتیب ruleها مهم است: مقصدهای استثناشده/مجاز برای عبور از تونل باید پیش از ruleهای عمومی ایران قرار گیرند تا مقصدهای خاص اشتباهاً bypass نشوند.

**محدودیت‌ها و کارهای باقی‌مانده:**
- فهرست دامنه/IP ایران و geo assets باید از منبع معتبر، نسخه‌دار و قابل اعتبارسنجی تهیه و به‌روزرسانی شوند؛ نبود یا کهنگی فایل جغرافیایی نباید به تشخیص قطعی منجر شود.
- Direct route فقط از اینترنت فعلی دستگاه خارج می‌شود و IP ایرانی ایجاد نمی‌کند.
- هنوز Gateway خروجی ایران با IP مبدأ مستقل و تأییدشده، انتخاب خودکار بین Direct و Iran Egress، کنترل per-destination در UI و fallback end-to-end تأیید نشده‌اند.
- باید rule ordering، DNS split، IPv4/IPv6، CDN و تغییر IP دامنه، handover شبکه، مسیرهای استثناشده، عدم نشت مقصدهای غیرایرانی و IP خروجی واقعی در تست‌های خودکار و دستگاه فیزیکی پوشش داده شوند.
- تا زمان اجرای این تست‌ها، این قابلیت را کامل یا تضمین‌شده معرفی نکنید.
