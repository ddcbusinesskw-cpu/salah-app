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
       4. Local Notifications — إشعارات الأذان
          - قناة مخصّصة بصوت adhan_alert.wav (Android 26+)
          - scheduleNoorNotifications([{id,title,body,date}])
          - scheduleAdhanAlerts(todayMap, tomorrowMap)
          - عند الإطلاق: تُعاد الجدولة تلقائياً عبر scheduleNoorPrayerAlerts()
    ════════════════════════════════════════════ */
    if (LocalNotif) {

      /* إنشاء قناة أذان مخصّصة بصوت وإشعار عالي الأولوية */
      if (LocalNotif.createChannel) {
        LocalNotif.createChannel({
          id:          'adhan',
          name:        'أذان الصلاة',
          description: 'تنبيه عند دخول وقت الصلاة',
          importance:  5,
          sound:       'adhan_alert.wav',
          vibration:   true,
          visibility:  1
        }).catch(function () {});
      }

      /* طلب الإذن — يُعيد جدولة التنبيهات فور المنح */
      LocalNotif.requestPermissions().then(function (r) {
        window._nativeNotifGranted = r.display === 'granted';
        if (window._nativeNotifGranted) {
          if (typeof window.scheduleNoorPrayerAlerts === 'function') {
            window.scheduleNoorPrayerAlerts();
          }
        }
      });

      /**
       * scheduleNoorNotifications(times)
       * times: [{ id, title, body, date }]
       */
      window.scheduleNoorNotifications = function (times) {
        if (!window._nativeNotifGranted || !times || !times.length) return;
        var cancelIds = times.map(function (t) { return { id: t.id }; });
        LocalNotif.cancel({ notifications: cancelIds }).catch(function () {});
        var notifications = times.map(function (t) {
          return {
            id:        t.id,
            title:     t.title,
            body:      t.body,
            schedule:  { at: new Date(t.date), allowWhileIdle: true },
            channelId: 'adhan',
            sound:     'adhan_alert.wav',
            smallIcon: 'ic_stat_noor',
            iconColor: '#3fae8e'
          };
        });
        LocalNotif.schedule({ notifications: notifications }).catch(function () {});
      };

      /**
       * scheduleAdhanAlerts(todayMap, tomorrowMap)
       * todayMap/tomorrowMap: { fajr:Date, dhuhr:Date, asr:Date, maghrib:Date, isha:Date }
       * ضع في الـ map الصلوات المُفعَّلة فقط.
       */
      var _ADHAN_LABELS = { fajr:'الفجر', dhuhr:'الظهر', asr:'العصر', maghrib:'المغرب', isha:'العشاء' };
      var _ADHAN_KEYS   = ['fajr','dhuhr','asr','maghrib','isha'];
      window.scheduleAdhanAlerts = function (todayMap, tomorrowMap) {
        var now = new Date();
        var notifs = [];
        _ADHAN_KEYS.forEach(function (k, i) {
          if (todayMap && todayMap[k] && new Date(todayMap[k]) > now) {
            notifs.push({ id: 2000 + i, title: '🕌 ' + _ADHAN_LABELS[k], body: 'حان وقت صلاة ' + _ADHAN_LABELS[k], date: todayMap[k] });
          }
          if (tomorrowMap && tomorrowMap[k] && new Date(tomorrowMap[k]) > now) {
            notifs.push({ id: 2010 + i, title: '🕌 ' + _ADHAN_LABELS[k], body: 'حان وقت صلاة ' + _ADHAN_LABELS[k], date: tomorrowMap[k] });
          }
        });
        if (notifs.length) window.scheduleNoorNotifications(notifs);
      };

      /* backwards-compatible helper */
      window.scheduleTodayPrayers = function (prayerMap) {
        window.scheduleAdhanAlerts(prayerMap, null);
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
