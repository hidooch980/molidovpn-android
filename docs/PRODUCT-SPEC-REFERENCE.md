# مرجع مشخصات محصول Molido Mesh AI

سند مرجع مادر محصول و معماری در مخزن اصلی نگهداری می‌شود:

- [سند مرجع مادر فارسی — MASTER-SPECIFICATION-FA.md](https://github.com/hidooch980/molidovpn/blob/ai-provider-independence-2026-11/docs/MASTER-SPECIFICATION-FA.md)
- [مخزن اصلی Molido Mesh AI](https://github.com/hidooch980/molidovpn)

این فایل عمداً فقط یک ارجاع است تا دو نسخهٔ مستقل و متناقض از مشخصات محصول ایجاد نشود. پیش از تغییر در Android، سند مادر را بررسی کنید. مشخصات محصول به معنی پیاده‌سازی‌شدن قابلیت نیست؛ وضعیت واقعی باید با کد، تست و CI همین مخزن تأیید شود.

قابلیت‌های مهمی که باید هنگام توسعهٔ Android در نظر گرفته شوند: اتصال Express/Instant Connect، پروفایل مستقل کیفیت شبکه برای هر دستگاه، انتخاب Gateway و مسیر بر اساس اندازه‌گیری واقعی، failover سریع، یک VpnService/TUN مرکزی، امنیت DNS/IP، حفاظت از حریم خصوصی، مدیریت مصرف منابع، و به‌روزرسانی امضاشده با حفظ کلید انتشار.
