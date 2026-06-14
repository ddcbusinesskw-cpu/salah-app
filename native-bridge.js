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
       4. Local Notifications — إشعارات مواقيت الصلاة
          دالة scheduleNoorPrayerAlerts() تُستدعى من index.html
          إن وُجد، وإلا نُعرِّفها هنا.
    ════════════════════════════════════════════ */
    if (LocalNotif) {
      /* طلب الإذن عند أول تشغيل */
      LocalNotif.requestPermissions().then(function (r) {
        if (r.display !== 'granted') return;
        window._nativeNotifGranted = true;
        /* إن كانت خاصية الجدولة موجودة في التطبيق — ادعُها */
        if (typeof window.scheduleNoorPrayerAlerts === 'function') {
          window.scheduleNoorPrayerAlerts();
        }
      });

      /**
       * scheduleNoorNotifications(times) — API مفتوحة لـ index.html
       * times: [{ id, title, body, date }]
       */
      window.scheduleNoorNotifications = function (times) {
        if (!window._nativeNotifGranted || !times || !times.length) return;
        var notifications = times.map(function (t) {
          return {
            id:       t.id,
            title:    t.title,
            body:     t.body,
            schedule: { at: new Date(t.date) },
            sound:    null,
            smallIcon: 'ic_stat_noor',
            iconColor: '#3fae8e'
          };
        });
        LocalNotif.cancel({ notifications: notifications }).catch(function () {});
        LocalNotif.schedule({ notifications: notifications });
      };

      /**
       * scheduleTodayPrayers(prayerMap) — helper مباشر
       * prayerMap: { fajr: Date, dhuhr: Date, asr: Date, maghrib: Date, isha: Date }
       */
      window.scheduleTodayPrayers = function (prayerMap) {
        var LABELS = {
          fajr: 'الفجر', dhuhr: 'الظهر', asr: 'العصر', maghrib: 'المغرب', isha: 'العشاء'
        };
        var notifs = [];
        var id = 1000;
        Object.keys(LABELS).forEach(function (key) {
          if (prayerMap[key] && prayerMap[key] > new Date()) {
            notifs.push({
              id:    id++,
              title: 'نور · ' + LABELS[key],
              body:  'حان وقت صلاة ' + LABELS[key],
              date:  prayerMap[key]
            });
          }
        });
        if (notifs.length) window.scheduleNoorNotifications(notifs);
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

    /* ════════════════════════════════════════════
       8. Firebase Auth — diagnostic only
          المسار الأصلي لتسجيل الدخول بجوجل مدمج مباشرة في
          window.fbSignIn داخل index.html (تحقق منصّة عند الضغط).
          هنا نسجّل فقط حالة الإضافة لمساعدة التشخيص.
    ════════════════════════════════════════════ */
    var _Cap2=window.Capacitor||{};
    var _fbAuthPlugin=(_Cap2.Plugins&&_Cap2.Plugins.FirebaseAuthentication)||null;
    console.log('[NoorBridge] FirebaseAuthentication plugin:'
      +(_fbAuthPlugin?'found':'NOT FOUND')
      +' | PluginKeys='+JSON.stringify(Object.keys(_Cap2.Plugins||{})));

    console.log('[NoorBridge] Native platform ready — haptics, geo, notifications, auth active');
  });
})();
