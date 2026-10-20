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
    var App          = Plugins.App;
    var StatusBar    = Plugins.StatusBar;
    var SplashScreen = Plugins.SplashScreen;

    /* ════════════════════════════════════════════
       1. StatusBar + SplashScreen
    ════════════════════════════════════════════ */
    if (StatusBar) {
      /* لون الأشرطة يتبع السِمة عبر إضافة SystemBars (_syncSystemBars في index.html)؛
         StatusBar احتياط فقط إن لم تتوفر الإضافة */
      if (Plugins.SystemBars && typeof window._syncSystemBars === 'function') {
        window._syncSystemBars();
      } else {
        StatusBar.setStyle({ style: 'DARK' });
        StatusBar.setBackgroundColor({ color: '#070A1A' });
      }
    }
    if (SplashScreen) {
      /* fix1 «general» N36: أخفِ شاشة البداية الأصلية بعد أن تُملأ الرئيسية (المواقيت) لا قبلها: index.html يطلق
         «noor-booted» بعد route() (أو بعد إظهار شاشة البداية للتشغيل الأول). احتياط ١٫٥ ث لنسخة index.html أقدم بلا
         الحدث، ويبقى launchShowDuration (١٫٨ ث) سقفاً أصلياً */
      var _splHid = false;
      var _splHide = function () { if (_splHid) return; _splHid = true; try { SplashScreen.hide({ fadeOutDuration: 250 }); } catch (e) {} };
      if (window._noorBootDone) _splHide();
      else { document.addEventListener('noor-booted', _splHide); setTimeout(_splHide, 1500); }
    }

    /* ════════════════════════════════════════════
       1.b KeepAwake — بديل أصلي موثوق لـ navigator.wakeLock
       (الغائب في كثير من WebView). جسر يستهلكه rakWakeLock في التطبيق.
    ════════════════════════════════════════════ */
    var KeepAwake = Plugins.KeepAwake;
    if (KeepAwake) {
      window._nativeKeepAwake = function (on) {
        try { if (on) KeepAwake.keepAwake(); else KeepAwake.allowSleep(); } catch (e) {}
      };
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
          - قناة 'reminders': التذكيرات الاختيارية (أذكار الصباح/المساء/النوم، قبل الأذان، الحفظ، الختمة، الكهف) IDs 4000‑4299 — index.html (fix2 «worship2» N86)
    ════════════════════════════════════════════ */
    if (LocalNotif) {

      /* fix1 «i18n» N30: نصوص الإشعارات وأسماء القنوات بلغة الواجهة (العربية افتراضاً). نصّ الذِّكر نفسه يبقى عربياً.
         body_<صلاة> يغلب body حين تحتاج اللغة صيغة لكل صلاة (أدوات التعريف في الفرنسية) */
      var _NB_TX = {
        ar: { fajr: 'الفجر', dhuhr: 'الظهر', asr: 'العصر', maghrib: 'المغرب', isha: 'العشاء',
              title: '🕌 {p}', body: 'حان وقت صلاة {p}',
              chAdhan: 'أذان الصلاة', chAdhanD: 'تنبيه عند دخول وقت الصلاة',
              chDhikr: 'تذكير ذِكر', chDhikrD: 'ذِكر ساعي خفيف على شاشة القفل',
              chRenew: 'تجديد مواقيت الأذان', chRenewD: 'تذكير بفتح التطبيق قبل انتهاء جدولة الأذان',
              renewT: '🔄 المجدّد', renewB: 'افتح التطبيق لتجديد مواقيت الأذان — تنتهي الجدولة الحالية بعد أيام قليلة',
              tzB: 'تغيّرت المنطقة الزمنية — افتح التطبيق لتحديث مواقيت الأذان لموقعك الجديد',
              dhikrT: 'المجدّد · ذِكر' },
        en: { fajr: 'Fajr', dhuhr: 'Dhuhr', asr: 'Asr', maghrib: 'Maghrib', isha: 'Isha',
              title: '🕌 {p}', body: 'It’s time for {p} prayer',
              chAdhan: 'Prayer adhan', chAdhanD: 'Alert when each prayer time begins',
              chDhikr: 'Dhikr reminders', chDhikrD: 'A gentle hourly dhikr on the lock screen',
              chRenew: 'Adhan schedule renewal', chRenewD: 'Reminds you to open the app before the adhan schedule runs out',
              renewT: '🔄 Al-Mujaddid', renewB: 'Open the app to renew your adhan times — the current schedule ends in a few days',
              tzB: 'Your time zone changed — open the app to update the adhan times for your new location',
              dhikrT: 'Al-Mujaddid · Dhikr' },
        fr: { fajr: 'Fajr', dhuhr: 'Dhohr', asr: 'Asr', maghrib: 'Maghreb', isha: 'Icha',
              title: '🕌 {p}', body: 'C’est l’heure de la prière {p}',
              body_fajr: 'C’est l’heure de la prière du Fajr', body_dhuhr: 'C’est l’heure de la prière du Dhohr',
              body_asr: 'C’est l’heure de la prière de l’Asr', body_maghrib: 'C’est l’heure de la prière du Maghreb',
              body_isha: 'C’est l’heure de la prière de l’Icha',
              chAdhan: 'Adhan des prières', chAdhanD: 'Alerte au début de chaque heure de prière',
              chDhikr: 'Rappels de dhikr', chDhikrD: 'Un dhikr discret chaque heure sur l’écran de verrouillage',
              chRenew: 'Renouvellement des horaires d’adhan', chRenewD: 'Vous rappelle d’ouvrir l’application avant la fin de la programmation de l’adhan',
              renewT: '🔄 Al-Mujaddid', renewB: 'Ouvrez l’application pour renouveler les horaires d’adhan : la programmation actuelle se termine dans quelques jours',
              tzB: 'Votre fuseau horaire a changé : ouvrez l’application pour mettre à jour les horaires d’adhan de votre nouvel emplacement',
              dhikrT: 'Al-Mujaddid · Dhikr' },
        de: { fajr: 'Fadschr', dhuhr: 'Dhuhr', asr: 'Asr', maghrib: 'Maghrib', isha: 'Ischa',
              title: '🕌 {p}', body: 'Es ist Zeit für das {p}-Gebet',
              chAdhan: 'Gebetsruf (Adhan)', chAdhanD: 'Hinweis, wenn eine Gebetszeit beginnt',
              chDhikr: 'Dhikr-Erinnerungen', chDhikrD: 'Ein dezenter stündlicher Dhikr auf dem Sperrbildschirm',
              chRenew: 'Adhan-Zeiten erneuern', chRenewD: 'Erinnert Sie daran, die App zu öffnen, bevor die Adhan-Planung endet',
              renewT: '🔄 Al-Mujaddid', renewB: 'Öffnen Sie die App, um die Adhan-Zeiten zu erneuern – die aktuelle Planung endet in wenigen Tagen',
              tzB: 'Ihre Zeitzone hat sich geändert – öffnen Sie die App, um die Adhan-Zeiten für Ihren neuen Ort zu aktualisieren',
              dhikrT: 'Al-Mujaddid · Dhikr' },
        hi: { fajr: 'फ़ज्र', dhuhr: 'ज़ुहर', asr: 'अस्र', maghrib: 'मग़रिब', isha: 'इशा',
              title: '🕌 {p}', body: '{p} की नमाज़ का समय हो गया है',
              chAdhan: 'नमाज़ की अज़ान', chAdhanD: 'हर नमाज़ का समय शुरू होने पर सूचना',
              chDhikr: 'ज़िक्र अनुस्मारक', chDhikrD: 'लॉक स्क्रीन पर हर घंटे एक हल्का ज़िक्र',
              chRenew: 'अज़ान के समय का नवीनीकरण', chRenewD: 'अज़ान का शेड्यूल खत्म होने से पहले ऐप खोलने की याद दिलाता है',
              renewT: '🔄 अल-मुजद्दिद', renewB: 'अज़ान के समय नवीनीकृत करने के लिए ऐप खोलें — मौजूदा शेड्यूल कुछ दिनों में खत्म हो जाएगा',
              tzB: 'आपका समय क्षेत्र बदल गया है — अपने नए स्थान के अज़ान समय अपडेट करने के लिए ऐप खोलें',
              dhikrT: 'अल-मुजद्दिद · ज़िक्र' },
        zh: { fajr: '晨礼', dhuhr: '晌礼', asr: '晡礼', maghrib: '昏礼', isha: '宵礼',
              title: '🕌 {p}', body: '{p}时间到了',
              chAdhan: '礼拜宣礼', chAdhanD: '每次礼拜时间开始时提醒',
              chDhikr: '记主提醒', chDhikrD: '每小时在锁屏上轻声提醒一句记主词',
              chRenew: '宣礼时间续期', chRenewD: '在宣礼计划到期前提醒您打开应用',
              renewT: '🔄 穆贾迪德', renewB: '请打开应用以更新宣礼时间——当前计划将在几天后结束',
              tzB: '您的时区已更改——请打开应用，更新新位置的宣礼时间',
              dhikrT: '穆贾迪德 · 记主' }
      };
      function _nbLang() { var l = 'ar'; try { l = window.noorLang || localStorage.getItem('noor_lang') || 'ar'; } catch (e) {} return _NB_TX[l] ? l : 'ar'; }
      function _nbTx(k, p) { var T = _NB_TX[_nbLang()], s = T[k]; if (s == null) s = _NB_TX.ar[k]; return p != null ? String(s).replace('{p}', p) : s; }
      window._nbTx = _nbTx;

      /* إنشاء القنوات — ونداؤها ثانيةً بالمعرّف نفسه يحدّث الاسم والوصف فقط (لا الصوت ولا الأهمية) عند تغيير اللغة */
      function _nbChannels() {
        if (!LocalNotif.createChannel) return;
        LocalNotif.createChannel({
          id: 'adhan', name: _nbTx('chAdhan'),
          description: _nbTx('chAdhanD'),
          importance: 5, sound: 'adhan_alert.wav', vibration: true, visibility: 1
        }).catch(function () {});

        /* قناة جديدة بمعرّف جديد: القديمة 'reminder' مجمّدة على صوت النظام
           الافتراضي (لا يمكن تصحيح قناة قائمة) — LOW(2) صامتة فعلاً */
        LocalNotif.createChannel({
          id: 'reminder_silent', name: _nbTx('chDhikr'),
          description: _nbTx('chDhikrD'),
          importance: 2, vibration: false, visibility: 1
        }).catch(function () {});

        /* تذكير تجديد الأذان: قناة عادية بصوت الإشعار الافتراضي (DEFAULT=3) — كان يصل على القناة الصامتة */
        LocalNotif.createChannel({
          id: 'adhan_renew', name: _nbTx('chRenew'),
          description: _nbTx('chRenewD'),
          importance: 3, vibration: true, visibility: 1
        }).catch(function () {});
      }
      _nbChannels();
      /* تغيير لغة الواجهة (applyLang في index.html): أسماء القنوات بالجديدة، ثم يعيد التطبيق جدولة الأذان بنصوصها */
      window._noorNotifLang = function () { _nbChannels(); };

      /* لا نطلب الإذن عند الإقلاع (حوار مبكر بلا سياق = رفض ثم حظر).
         نكتفي بمعرفة الحالة الحالية؛ الطلب الفعلي عند تفعيل الأذان من
         شاشة المواقيت. ونعيد الجدولة إن كان الإذن ممنوحاً مسبقاً. */
      if (LocalNotif.checkPermissions) {
        LocalNotif.checkPermissions().then(function (r) {
          window._nativeNotifGranted = r && r.display === 'granted';
          if (window._nativeNotifGranted) {
            if (typeof window.scheduleNoorPrayerAlerts === 'function') window.scheduleNoorPrayerAlerts();
            if (typeof window.scheduleHourlyDhikr === 'function') window.scheduleHourlyDhikr();
            if (typeof window.scheduleNoorReminders === 'function') window.scheduleNoorReminders();
          }
        }).catch(function () {});
      }

      /**
       * scheduleNoorNotifications(times) — لإشعارات الأذان
       * times: [{ id, title, body, date, channelId? }]
       * بلا إذن: عودة صامتة — طلب الإذن من فعل المستخدم فقط
       * (تفعيل الأذان/التجربة)، لا عند كل عودة للتطبيق.
       */
      window.scheduleNoorNotifications = function (times) {
        if (!times || !times.length) return;
        if (!window._nativeNotifGranted) return;
        var cancelIds = times.map(function (t) { return { id: t.id }; });
        LocalNotif.cancel({ notifications: cancelIds }).catch(function () {});
        var notifications = times.map(function (t) {
          var n = {
            id: t.id, title: t.title, body: t.body,
            schedule: { at: new Date(t.date), allowWhileIdle: true },
            channelId: t.channelId || 'adhan',
            smallIcon: 'ic_stat_noor', iconColor: '#3fae8e'
          };
          if (t.extra) n.extra = t.extra;
          /* نغمة الأذان لقناة الأذان فقط */
          if (!t.channelId) n.sound = 'adhan_alert.wav';
          /* iOS بلا قنوات: الإشعار «المسموع» يحتاج اسم صوت (غير الموجود في الحزمة = صوت النظام الافتراضي) */
          else if (t.audible && window.Capacitor.getPlatform && window.Capacitor.getPlatform() === 'ios') n.sound = 'adhan_alert.wav';
          return n;
        });
        LocalNotif.schedule({ notifications: notifications }).catch(function (e) {
          console.warn('[NoorAdhan] schedule failed:', e && (e.message || String(e)));
          if (typeof window.toast === 'function') {
            /* N16: جملة واضحة بلا نصّ الاستثناء (يبقى في console.warn أعلاه) */
            var _m = 'تعذّر جدولة الأذان — أعد المحاولة من «الأذان والإشعارات»';
            window.toast(typeof window._tsT === 'function' ? window._tsT(_m) : _m, 'error');
          }
        });
      };

      /* معرّفات الأذان 2000‑2189 (حتى ٣٨ يوماً × ٥ صلوات) + تذكير التجديد 2199
         (2035 كان التذكير في الإصدار السابق — داخل المدى فيُلغى/يُعاد استعماله) */
      var _ADHAN_ID0 = 2000, _ADHAN_MAXD = 38, _RENEW_ID = 2199;
      window.cancelAdhanNotifications = function () {
        var ids = [];
        for (var i = _ADHAN_ID0; i <= _RENEW_ID; i++) ids.push({ id: i });
        LocalNotif.cancel({ notifications: ids }).catch(function () {});
      };

      /* scheduleAdhanAlerts — مصفوفة خرائط يومية (اليوم الأول = الأمس/اليوم…؛ ما مضى يُهمَل).
         حدّ المعلّقات: iOS ٦٤ إشعاراً للتطبيق كله (٣٦ للأذان + ٢٤ للذكر)، أندرويد ~٥٠٠ منبّه (≤١٦٠ للأذان + ٤٨ للذكر) */
      var _ADHAN_LABELS = { fajr:'الفجر', dhuhr:'الظهر', asr:'العصر', maghrib:'المغرب', isha:'العشاء' }; /* العربية؛ بلغة الواجهة عبر _nbTx */
      var _ADHAN_KEYS   = ['fajr','dhuhr','asr','maghrib','isha'];
      window.scheduleAdhanAlerts = function (mapsArr, legacyTomorrow) {
        /* توافق خلفي: إذا نودي بـ (todayMap, tomorrowMap) بدل مصفوفة */
        if (!Array.isArray(mapsArr)) mapsArr = legacyTomorrow ? [mapsArr, legacyTomorrow] : [mapsArr];
        var ios = !!(window.Capacitor.getPlatform && window.Capacitor.getPlatform() === 'ios');
        var cap = ios ? 36 : 160;
        var now = new Date(), notifs = [];
        mapsArr.forEach(function (dayMap, dayIdx) {
          if (dayIdx >= _ADHAN_MAXD) return;
          _ADHAN_KEYS.forEach(function (k, ki) {
            if (dayMap && dayMap[k] && new Date(dayMap[k]) > now)
              notifs.push({ id: _ADHAN_ID0 + dayIdx * 5 + ki, title: _nbTx('title', _nbTx(k)), body: (_NB_TX[_nbLang()]['body_' + k] || _nbTx('body', _nbTx(k))), date: dayMap[k] });
          });
        });
        notifs.sort(function (a, b) { return new Date(a.date) - new Date(b.date); });
        if (notifs.length > cap) notifs = notifs.slice(0, cap);
        /* N06: نصّ «تغيّرت المنطقة الزمنية» بلغة الواجهة يُخزَّن مع آخر أذان مجدول (يبقى مخزّناً حتى نهاية الأفق)
           ليقرأه مستقبِل النظام الأصلي والتطبيق مغلق — اقتراح proposals/N06 */
        var _tzX = { nbTzT: _nbTx('renewT'), nbTzB: _nbTx('tzB'), nbTzC: _nbTx('chRenew') };
        if (notifs.length) notifs[notifs.length - 1].extra = _tzX;
        /* تذكير التجديد قبل نفاد الأفق (أندرويد ~اليوم ٢٦، iOS ~اليوم ٦) — مسموع على قناة عادية */
        var _until = notifs.length ? new Date(notifs[notifs.length - 1].date).getTime() : 0;
        if (notifs.length) {
          var _last = new Date(_until);
          var _renew = new Date(_last.getTime() - (ios ? 1 : 4) * 86400000);
          _renew.setHours(9, 0, 0, 0);
          if (_renew.getTime() - now.getTime() > 12 * 3600000)
            notifs.push({ id: _RENEW_ID, title: _nbTx('renewT'), body: _nbTx('renewB'), date: _renew.toISOString(), channelId: 'adhan_renew', audible: true, extra: _tzX });
        }
        /* إلغاء المعرّفات التي لم تعد مجدولة — صلاة عُطّل أذانها أو مضى وقتها أو خرجت من الأفق */
        var _keep = {}, _stale = [];
        notifs.forEach(function (n) { _keep[n.id] = 1; });
        for (var si = _ADHAN_ID0; si <= _RENEW_ID; si++) if (!_keep[si]) _stale.push({ id: si });
        if (_stale.length && window._nativeNotifGranted)
          LocalNotif.cancel({ notifications: _stale }).catch(function () {});
        if (notifs.length && window._nativeNotifGranted) {
          try { localStorage.setItem('_adhan_sched_ts', String(Date.now())); } catch (e) {}
          try { localStorage.setItem('_adhan_sched_until', String(_until)); } catch (e) {}
          window.scheduleNoorNotifications(notifs);
        }
      };
      window.scheduleTodayPrayers = function (prayerMap) { window.scheduleAdhanAlerts([prayerMap]); };

      /* إعادة الجدولة عند كل عودة للتطبيق (لا فقط بعد 24 ساعة) — يضمن
         تجديد الأفق قبل انقضائه ما دام المستخدم يفتح التطبيق دورياً */
      if (App) {
        App.addListener('appStateChange', function (state) {
          if (!state || !state.isActive) return;
          /* إحداثيات قديمة (سفر) → تحديث خلفي صامت؛ نجاحه يعيد الحساب والجدولة */
          try {
            if (typeof window._refreshCoordsIfStale === 'function') window._refreshCoordsIfStale();
          } catch (e) {}
          try {
            if (typeof window.scheduleNoorPrayerAlerts === 'function') window.scheduleNoorPrayerAlerts();
          } catch (e) {}
          /* الذِكر الساعي: ١١٢ إشعاراً ≈ ٧ أيام — جدِّده عند العودة لا عند الإقلاع فقط.
             مرة يومياً على الأكثر: كل جدولة تبدأ الدورة من أول ذِكر */
          try {
            var _dts = 0;
            try { _dts = +localStorage.getItem('_dhikr_sched_ts') || 0; } catch (e) {}
            if (Date.now() - _dts > 86400000 && typeof window.scheduleHourlyDhikr === 'function') {
              window.scheduleHourlyDhikr();
              /* بلا إذن لم يُجدوَل شيء — أعِد المحاولة في العودة التالية */
              if (window._nativeNotifGranted) { try { localStorage.setItem('_dhikr_sched_ts', String(Date.now())); } catch (e) {} }
            }
          } catch (e) {}
          /* fix2 «worship2» N86: التذكيرات الاختيارية — تُعاد عند كل عودة (index.html يتخطّى إن لم يتغيّر شيء خلال ٦ ساعات) */
          try {
            if (typeof window.scheduleNoorReminders === 'function') window.scheduleNoorReminders();
          } catch (e) {}
        });
        /* نقر الإشعار → افتح شاشة المواقيت */
        if (LocalNotif.addListener) {
          LocalNotif.addListener('localNotificationActionPerformed', function (ev) {
            try {
              var id = ev && ev.notification && ev.notification.id;
              /* جلسة نشطة (تسميع/متابع صلاة): لا تقطعها بالتنقّل */
              if ((window.LM && window.LM.rec) || (window.SW && window.SW.active)) return;
              /* fix2 «worship2» N86: تذكير اختياري → شاشته (الأذكار/المواقيت/الحفظ/الختمة/الكهف) */
              if (id >= 4000 && id < 4300) { if (typeof window._remTap === 'function') window._remTap((ev.notification && ev.notification.extra) || null); return; }
              /* تذكير الذكر: افتح التطبيق فقط */
              if (id >= 3000 && id < 3200) return;
              if (typeof window.go === 'function') window.go('times');
            } catch (e) {}
          });
        }
      }

      /**
       * _nativeHourlyDhikr(dhikrList)
       * يُجدِّل 112 إشعاراً ساعياً ≈ ٧ أيام (24 على iOS) (IDs 3000‑3199) ضمن ساعات 7 ص–11 م — fix2 «worship2» N86: كان 48 (≈ ٣ أيام فيتوقف)
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
        /* iOS يحتفظ بأقرب 64 إشعاراً معلّقاً فقط: 36 للأذان + 24 للذكر. أندرويد ~٥٠٠ منبّه: الأذان ≤161 + الذكر 112 + التذكيرات ≤72 */
        var _max = (window.Capacitor.getPlatform && window.Capacitor.getPlatform() === 'ios') ? 24 : 112;
        outerDhikr: for (var d = 0; d < 8; d++) {
          for (var h = 7; h <= 22; h++) {
            var dt = new Date(now);
            dt.setDate(dt.getDate() + d);
            dt.setHours(h, 0, 0, 0);
            if (dt <= now) continue;
            notifs.push({
              id:        3000 + notifs.length,
              title:     _nbTx('dhikrT'),
              body:      dhikrList[idx++ % dhikrList.length],
              /* fix1 «general» N37: تذكير الذكر ليس منبّهاً — بلا allowWhileIdle فلا يوقظ الهاتف من السكون (setExact غير
                 موقظ يتأجّل في Doze إلى نافذة الصيانة، وبلا إذن المنبّه الدقيق يصير set() غير دقيق). الأذان وحده allowWhileIdle */
              schedule:  { at: dt },
              channelId: 'reminder_silent',
              smallIcon: 'ic_stat_noor',
              iconColor: '#3fae8e'
            });
            if (notifs.length >= _max) break outerDhikr;
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
