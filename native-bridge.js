/**
 * native-bridge.js — شرطي خالص، لا يُعدِّل سلوك الويب.
 * يُحقن فقط في www/index.html (نسخة Capacitor)؛ الجذر يبقى نظيفاً.
 *
 * يعترض الـ APIs الموجودة ويستبدلها بمقابلاتها الأصلية عند التشغيل
 * على Native Platform. على الويب لا يفعل شيئاً.
 */
(function () {
  'use strict';

  /* ── انتظر Capacitor ── */
  function onCap(cb) {
    if (window.Capacitor && window.Capacitor.isNativePlatform) {
      cb();
    } else {
      document.addEventListener('DOMContentLoaded', function () {
        if (window.Capacitor && window.Capacitor.isNativePlatform) cb();
      });
    }
  }

  onCap(function () {
    if (!window.Capacitor.isNativePlatform()) return;

    var Plugins = window.Capacitor.Plugins;
    var Haptics      = Plugins.Haptics;
    var LocalNotif   = Plugins.LocalNotifications;
    var Geo          = Plugins.Geolocation;
    var StatusBar    = Plugins.StatusBar;
    var SplashScreen = Plugins.SplashScreen;

    /* ════════════════════════════════════════════
       1. StatusBar + SplashScreen
    ════════════════════════════════════════════ */
    if (StatusBar) {
      StatusBar.setStyle({ style: 'DARK' });
      StatusBar.setBackgroundColor({ color: '#051a16' });
    }
    if (SplashScreen) {
      SplashScreen.hide({ fadeOutDuration: 400 });
    }

    /* ════════════════════════════════════════════
       2. Haptics — يعترض buzz() الموجودة في التطبيق
          buzz() تُعرَّف في index.html وتستخدم navigator.vibrate
    ════════════════════════════════════════════ */
    if (Haptics) {
      window._nativeBuzzOrig = window.buzz;
      window.buzz = function (pattern) {
        if (Array.isArray(pattern)) {
          /* نمط: [40,20,65] → Heavy + Light */
          Haptics.vibrate({ duration: pattern.reduce(function (a, b) { return a + b; }, 0) });
        } else if (typeof pattern === 'number') {
          if (pattern <= 20) {
            Haptics.impact({ style: 'LIGHT' });
          } else if (pattern <= 50) {
            Haptics.impact({ style: 'MEDIUM' });
          } else {
            Haptics.impact({ style: 'HEAVY' });
          }
        }
      };
    }

    /* ════════════════════════════════════════════
       3. Geolocation — يعترض navigator.geolocation.getCurrentPosition
          التطبيق يستدعيه لحساب مواقيت الصلاة والقبلة
    ════════════════════════════════════════════ */
    if (Geo) {
      var _geoOrig = navigator.geolocation;
      Object.defineProperty(navigator, 'geolocation', {
        value: {
          getCurrentPosition: function (success, error, opts) {
            Geo.getCurrentPosition({ enableHighAccuracy: true, timeout: 10000 })
              .then(function (r) {
                /* مطابقة شكل GeolocationPosition القياسي */
                success({
                  coords: {
                    latitude:         r.coords.latitude,
                    longitude:        r.coords.longitude,
                    accuracy:         r.coords.accuracy,
                    altitude:         r.coords.altitude,
                    altitudeAccuracy: r.coords.altitudeAccuracy,
                    heading:          r.coords.heading,
                    speed:            r.coords.speed
                  },
                  timestamp: r.timestamp
                });
              })
              .catch(function (e) {
                if (error) error({ code: 2, message: e.message });
              });
          },
          watchPosition: function (success, error, opts) {
            var wid;
            Geo.watchPosition({ enableHighAccuracy: true }, function (r, err) {
              if (err && error) { error({ code: 2, message: err.message }); return; }
              if (r && success) success({
                coords: {
                  latitude:  r.coords.latitude,
                  longitude: r.coords.longitude,
                  accuracy:  r.coords.accuracy,
                  altitude:  r.coords.altitude || null,
                  altitudeAccuracy: r.coords.altitudeAccuracy || null,
                  heading:   r.coords.heading || null,
                  speed:     r.coords.speed || null
                },
                timestamp: r.timestamp
              });
            }).then(function (id) { wid = id; });
            return wid;
          },
          clearWatch: function (id) { if (id) Geo.clearWatch({ id: id }); }
        },
        writable: false,
        configurable: true
      });
    }

    /* ════════════════════════════════════════════
       4. Local Notifications
          - قناة 'adhan'   : صوت adhan_alert.wav، أولوية عالية (IDs 2000‑2019)
          - قناة 'reminder': ذِكر ساعي هادئ، بدون صوت (IDs 3000‑3199)
    ════════════════════════════════════════════ */
    if (LocalNotif) {

      /* إنشاء القنوات */
      if (LocalNotif.createChannel) {
        LocalNotif.createChannel({
          id: 'adhan', name: 'أذان الصلاة',
          description: 'تنبيه عند دخول وقت الصلاة',
          importance: 5, sound: 'adhan_alert.wav', vibration: true, visibility: 1
        }).catch(function () {});

        LocalNotif.createChannel({
          id: 'reminder', name: 'تذكير ذِكر',
          description: 'ذِكر ساعي خفيف على شاشة القفل',
          importance: 3, vibration: false, visibility: 1
        }).catch(function () {});
      }

      /* طلب الإذن — يُعيد جدولة الصلاة والذِكر فور المنح */
      LocalNotif.requestPermissions().then(function (r) {
        window._nativeNotifGranted = r.display === 'granted';
        if (window._nativeNotifGranted) {
          if (typeof window.scheduleNoorPrayerAlerts === 'function') window.scheduleNoorPrayerAlerts();
          if (typeof window.scheduleHourlyDhikr === 'function') window.scheduleHourlyDhikr();
        }
      });

      /**
       * scheduleNoorNotifications(times) — لإشعارات الأذان
       * times: [{ id, title, body, date }]
       */
      window.scheduleNoorNotifications = function (times) {
        if (!window._nativeNotifGranted || !times || !times.length) return;
        var cancelIds = times.map(function (t) { return { id: t.id }; });
        LocalNotif.cancel({ notifications: cancelIds }).catch(function () {});
        var notifications = times.map(function (t) {
          return {
            id: t.id, title: t.title, body: t.body,
            schedule: { at: new Date(t.date), allowWhileIdle: true },
            channelId: 'adhan', sound: 'adhan_alert.wav',
            smallIcon: 'ic_stat_noor', iconColor: '#3fae8e'
          };
        });
        LocalNotif.schedule({ notifications: notifications }).catch(function () {});
      };

      /* scheduleAdhanAlerts / scheduleTodayPrayers */
      var _ADHAN_LABELS = { fajr:'الفجر', dhuhr:'الظهر', asr:'العصر', maghrib:'المغرب', isha:'العشاء' };
      var _ADHAN_KEYS   = ['fajr','dhuhr','asr','maghrib','isha'];
      window.scheduleAdhanAlerts = function (todayMap, tomorrowMap) {
        var now = new Date(), notifs = [];
        _ADHAN_KEYS.forEach(function (k, i) {
          if (todayMap && todayMap[k] && new Date(todayMap[k]) > now)
            notifs.push({ id: 2000 + i, title: '🕌 ' + _ADHAN_LABELS[k], body: 'حان وقت صلاة ' + _ADHAN_LABELS[k], date: todayMap[k] });
          if (tomorrowMap && tomorrowMap[k] && new Date(tomorrowMap[k]) > now)
            notifs.push({ id: 2010 + i, title: '🕌 ' + _ADHAN_LABELS[k], body: 'حان وقت صلاة ' + _ADHAN_LABELS[k], date: tomorrowMap[k] });
        });
        if (notifs.length) window.scheduleNoorNotifications(notifs);
      };
      window.scheduleTodayPrayers = function (prayerMap) { window.scheduleAdhanAlerts(prayerMap, null); };

      /**
       * _nativeHourlyDhikr(dhikrList)
       * يُجدِّل 48 إشعاراً ساعياً (IDs 3000‑3199) ضمن ساعات 7 ص–11 م
       * يستخدم قناة 'reminder' الهادئة.
       */
      window._cancelNativeHourlyDhikr = function () {
        var ids = [];
        for (var i = 3000; i < 3200; i++) ids.push({ id: i });
        LocalNotif.cancel({ notifications: ids }).catch(function () {});
      };

      window._nativeHourlyDhikr = function (dhikrList) {
        if (!window._nativeNotifGranted || !dhikrList || !dhikrList.length) return;
        var now = new Date(), notifs = [], idx = 0;
        outerDhikr: for (var d = 0; d < 4; d++) {
          for (var h = 7; h <= 22; h++) {
            var dt = new Date(now);
            dt.setDate(dt.getDate() + d);
            dt.setHours(h, 0, 0, 0);
            if (dt <= now) continue;
            notifs.push({
              id:        3000 + notifs.length,
              title:     'نور · ذِكر',
              body:      dhikrList[idx++ % dhikrList.length],
              schedule:  { at: dt, allowWhileIdle: true },
              channelId: 'reminder',
              smallIcon: 'ic_stat_noor',
              iconColor: '#3fae8e'
            });
            if (notifs.length >= 48) break outerDhikr;
          }
        }
        if (!notifs.length) return;
        var cancelIds = [];
        for (var ci = 3000; ci < 3200; ci++) cancelIds.push({ id: ci });
        LocalNotif.cancel({ notifications: cancelIds }).catch(function () {});
        LocalNotif.schedule({ notifications: notifs }).catch(function (e) { console.warn('[NoorDhikr]', e); });
      };
    }

    /* ════════════════════════════════════════════
       5. Motion — مقياس تسارع native لوضع orient
          يُعيِّن DeviceMotionEvent listener نظيف بدون اشتراط إذن
          (على iOS native الـ WebView يرث الإذن تلقائياً)
    ════════════════════════════════════════════ */
    /* لا تغيير مطلوب — Capacitor WebView يمرِّر DeviceMotionEvent مباشرةً */

    /* ════════════════════════════════════════════
       6. ProximitySensor — native WebView يدعمه مباشرةً على Android.
          لا plugin إضافي مطلوب.
          إن فشل (iOS) rakProxOpen() تتراجع للـ touch mode تلقائياً.
    ════════════════════════════════════════════ */

    /* ════════════════════════════════════════════
       7. Camera — getUserMedia يعمل في WebView مع Capacitor.
          نحتفظ بالكاميرا الحالية دون تغيير.
          Capacitor Camera plugin متاح للصور الفوتوغرافية إن احتجناه لاحقاً.
    ════════════════════════════════════════════ */

    console.log('[NoorBridge] Native platform ready — haptics, geo, notifications, auth active');
  });
})();
