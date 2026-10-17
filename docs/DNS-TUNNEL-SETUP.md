# تونل DNS (dnstt) — راهنمای راه‌اندازی سرور

> **وضعیت در این نسخه:** فقط زیرساخت و تنظیمات آماده است و حالت به‌صورت پیش‌فرض **خاموش** است.
> خود برنامهٔ dnstt هنوز داخل APK نیست و حالت اتصالش هنوز به سرویس VPN وصل نشده (فهرست کارهای باقی‌مانده در `app/src/main/java/com/molido/vpn/DnsTunnel.kt`).
> این راهنما برای وقتی است که یک سرور (VPS) و یک دامنه داشته باشید.

## این روش چه کار می‌کند؟

وقتی اینترنت «ملی» می‌شود، معمولاً فقط DNS هنوز تا مدتی به بیرون راه دارد. dnstt ترافیک را داخل
درخواست‌های DNS می‌گذارد: گوشی از DNS داخلی (اپراتور یا شکن/رادار/الکترو) یک نام زیر دامنهٔ شما را
می‌پرسد، آن DNS درخواست را به سرور شما (که «صاحب» آن زیردامنه است) می‌رساند و جواب برمی‌گردد.

- سرعت: خیلی کم (معمولاً ۱۰ تا ۵۰ کیلوبایت بر ثانیه). برای پیام‌رسان و متن، نه برای ویدیو.
- تضمینی نیست: اگر DNSهای داخلی هم فقط دامنه‌های داخلی را جواب بدهند، این روش هم کار نمی‌کند.

## چیزهایی که لازم دارید (هزینه‌دار)

| مورد | توضیح | هزینهٔ تقریبی |
|---|---|---|
| یک VPS خارج از ایران | هر سرور لینوکسی با IP ثابت (Hetzner، DigitalOcean، …) | از حدود ۴ تا ۶ دلار در ماه |
| یک دامنه | هر دامنه‌ای که بتوانید رکوردهای DNS آن را عوض کنید | از حدود ۲ تا ۱۰ دلار در سال |

> بدون VPS این روش ممکن نیست؛ Cloudflare Worker نمی‌تواند سرور DNS (پورت UDP 53) باشد.

## ۱. رکوردهای DNS دامنه

فرض کنید دامنهٔ شما `example.com` و IP سرور `203.0.113.10` است. در پنل DNS دامنه:

```
A     tns.example.com   203.0.113.10
NS    t.example.com     tns.example.com
```

یعنی هر نامی زیر `t.example.com` را سرور خود شما جواب می‌دهد. اسم‌ها کوتاه باشند (`t` بهتر از `tunnel`)،
چون هر حرف از ظرفیت هر بسته کم می‌کند. اگر دامنه روی کلادفلر است، رکورد A را **DNS only** (ابر خاکستری) بگذارید.

## ۲. نصب روی سرور (Ubuntu / Debian)

```bash
sudo apt update && sudo apt install -y golang git microsocks
git clone https://www.bamsoftware.com/git/dnstt.git
cd dnstt/dnstt-server && go build
./dnstt-server -gen-key -privkey-file server.key -pubkey-file server.pub
cat server.pub      # این ۶۴ حرف همان pubkey است که در تنظیمات برنامه می‌رود
```

یک پروکسی SOCKS فقط روی خود سرور (به اینترنت باز نیست):

```bash
microsocks -i 127.0.0.1 -p 1080 &
```

dnstt روی پورت 5300 گوش می‌دهد و پورت 53 به آن هدایت می‌شود:

```bash
sudo iptables -I INPUT -p udp --dport 5300 -j ACCEPT
sudo iptables -t nat -I PREROUTING -i eth0 -p udp --dport 53 -j REDIRECT --to-ports 5300
./dnstt-server -udp :5300 -privkey-file server.key t.example.com 127.0.0.1:1080
```

برای اجرای دائمی، هر دو دستور (`microsocks` و `dnstt-server`) را سرویس systemd کنید.

### تست از کامپیوتر خودتان

```bash
cd dnstt/dnstt-client && go build
./dnstt-client -doh https://dns.google/dns-query -pubkey-file server.pub t.example.com 127.0.0.1:7000
curl --socks5-hostname 127.0.0.1:7000 https://example.com -I
```

(این زنجیره روی کامپیوتر ما با یک سرور آزمایشی محلی تست شد و درست کار کرد.)

## ۳. روشن کردن در برنامه، بدون ساخت نسخهٔ جدید

فایل `remote/resilience.json` همین مخزن را در وب‌سایت گیت‌هاب ویرایش کنید:

```json
"dns_tunnel": {
  "enabled": true,
  "domain": "t.example.com",
  "pubkey": "<۶۴ حرف داخل server.pub>",
  "system_dns": true,
  "in_auto": true,
  "udp": ["178.22.122.100", "185.51.200.2", "10.202.10.10", "78.157.42.100"],
  "doh": ["https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query"],
  "dot": []
}
```

- `udp`: DNSهای داخلی (شکن، رادار، الکترو، بگذر). در قطعی کامل، این‌ها و DNS خود اپراتور (`system_dns`) شانس اصلی‌اند.
- `doh` و `dot`: وقتی DNS خارجی هنوز در دسترس است، رمزنگاری‌شده و پایدارترند.
- `in_auto`: اگر `true` باشد، حالت «خودکار» این روش را به‌عنوان آخرین گزینه امتحان می‌کند.

برنامه این فایل را هر ۶ ساعت می‌خواند (از گیت‌هاب، jsDelivr، Worker و آینه‌ها). فایل اشتباه نادیده
گرفته می‌شود و برنامه با تنظیمات قبلی کار می‌کند. پیش از روشن کردن، کارهای باقی‌ماندهٔ داخل `DnsTunnel.kt` باید انجام شده باشد.
