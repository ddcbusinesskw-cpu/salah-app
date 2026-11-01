# تراخيص أصوات الأذان المضمّنة — Bundled adhan recordings: licences and attribution

The «المجدّد» (Al-Mujaddid) Android app ships three adhan recordings inside the APK
(`android/app/src/main/res/raw/`) so the full call to prayer can play while the phone is locked or the
app is closed, without any network access. All three were re-encoded from the originals
(mono, OGG/Opus ≈ 60 kbps constrained VBR, 48 kHz, leading/trailing silence trimmed, loudness
normalised to −16 LUFS integrated / −1.5 dBTP). Every licence below permits modification and
redistribution; the required attribution lines are reproduced verbatim. The app is free, has no
ads and no in-app purchases. Copyright in each recording remains with its respective rights holder.

A copy of this file is bundled as `res/raw/licenses_adhan.md` (lower-case name because Android
resource names only allow `[a-z0-9_.]`), and the same attribution is shown in the app under
«عن التطبيق ← تراخيص أصوات الأذان».

| File | Source | Licence | Original → bundled |
|---|---|---|---|
| `adhan_alafasy.ogg` | AlAdhan.com / Islamic Network, "Yet Another Adhan by Mishary Rashid Alafasy" (`a9.mp3`) | Written permission from the distributor (see 1) | 4:17 MP3 128 kbps stereo → 4:13 Opus mono, 1,928,398 B |
| `adhan_madinah.ogg` | Wikimedia Commons, "Call to prayer from the Prophet's Mosque" by ejaz215 | CC BY 3.0 (attribution required) | 3:07 Ogg Vorbis 128 kbps stereo → 3:06 Opus mono, 1,415,568 B |
| `adhan_fajr_doha.ogg` | Internet Archive, "Adhan Recordings from Doha, Qatar" — `Adhan_Doha_Qatar_01_Fajr_Adhan.mp3` | Public Domain Mark 1.0 | 3:37 MP3 ~107 kbps mono → 3:38 Opus mono, 1,659,190 B |

SHA-256 of the bundled files is in `android/app/src/main/res/raw/sha256sums.txt`.

## 1. `adhan_alafasy.ogg` — Mishary Rashid Alafasy, via AlAdhan.com (Islamic Network)

- Source page: https://aladhan.com/download-adhans — file https://cdn.aladhan.com/audio/adhans/a9.mp3
  ("Yet Another Adhan by Mishary Rashid Alafasy").
- Permission: Islamic Network community thread
  https://community.islamic.network/d/255-permission-to-bundle-an-adhan-audio-file-in-our-android-app-nurafiq
  Question: "Is it permitted to bundle one of these Adhan audio files inside our app (shipped in the app
  package so it plays offline, not streamed from your servers)? Is attribution required, and if so, what
  credit line would you like us to use? Any restrictions on redistribution — the app is completely free,
  no ads, no in-app purchases." — Answer (gokboru, site maintainer): "Wa 3laykum salaam! Yes. No. No.
  May Allah ﷿ grant you success in your endeavours."
  Second thread https://community.islamic.network/d/256-audio-licensing-query-for-azan-pro-app:
  "The files are public domain - the copyright, nonetheless, belongs to the mu'adhins."
- Terms: https://aladhan.com/credits-and-terms — "All audio files used on this website and third party
  libraries own and retain their respective copyrights."
- Courtesy credit used in the app:
  **"Adhan audio courtesy of Islamic Network (aladhan.com). Copyright remains with the respective mu'adhins."**
- Note: this is the distributor's permission for exactly this use (free offline app); it is not a clearance
  from the muezzin. Keep a saved copy of thread 255 with the project records.

## 2. `adhan_madinah.ogg` — "Call to prayer from the Prophet's Mosque" by ejaz215 (CC BY 3.0)

- Source: https://commons.wikimedia.org/wiki/File:33937_ejaz215_call-to-prayer-from-the-prophet-s-mo.ogg
  (original upload: Freesound sound #33937 by ejaz215, now deleted there; Commons record preserves the licence).
  Description on Commons: "Islamic call to prayer (adhan) recorded at the Prophet's Mosque".
- Licence: Creative Commons Attribution 3.0 Unported — https://creativecommons.org/licenses/by/3.0/
- Required attribution (used verbatim in the app):
  **"Call to prayer from the Prophet's Mosque" by ejaz215, via Freesound/Wikimedia Commons, licensed CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/).**
- Changes made (CC BY §4(b) requires noting modifications): converted to mono Opus, trimmed silence,
  loudness-normalised to −16 LUFS. The modified file is distributed under the same CC BY 3.0 terms.

## 3. `adhan_fajr_doha.ogg` — Fajr adhan, "Adhan Recordings from Doha, Qatar" (Public Domain Mark 1.0)

- Source: https://archive.org/details/adhan.recordings.from.doha.qatar — file
  https://archive.org/download/adhan.recordings.from.doha.qatar/Adhan_Doha_Qatar_01_Fajr_Adhan.mp3
  Metadata: creator "Doha, Qatar", description "Recorded between 2013 and 2014.", published 2015-12-22.
- Licence field: `http://creativecommons.org/publicdomain/mark/1.0/` (Public Domain Mark 1.0 — no known
  copyright; no attribution legally required).
- Courtesy credit used in the app:
  **"Fajr adhan from 'Adhan Recordings from Doha, Qatar' (Internet Archive, Public Domain Mark 1.0)."**

## 4. `adhan_alert.wav`

The 3.58-second short alert tone used for the «تنبيه قصير فقط» mode and as the fallback notification sound
was produced for the app itself; no third-party rights.

## Not bundled (checked and rejected during research)

AlAdhan `a3.mp3` (commercial EMI recording of Karl Jenkins' "The Armed Man") and `a4.mp3` (Dubai One TV
broadcast capture) were deliberately excluded; see the research appendix for the full list of sources
examined. In-app streaming of other muezzins from cdn.aladhan.com is unchanged and is described in
`privacy.html`.
