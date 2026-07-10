#!/usr/bin/env bash
# Copies web assets from repo root → www/ before every cap sync.
# The root files are the source of truth (served by GitHub Pages).
# www/ is Capacitor's webDir — gets patched to load native-bridge.js.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "→ Copying web assets to www/"
cp "$ROOT/index.html"            "$ROOT/www/index.html"

# بصمة البناء: hash + وقت — تُحقن في www فقط (يبقى الجذر «dev»).
# Codemagic يوفّر CM_COMMIT؛ محلياً git؛ وإلا "local".
STAMP_HASH="${CM_COMMIT:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo local)}"
STAMP_HASH="$(printf '%s' "$STAMP_HASH" | cut -c1-7)"
BUILD_STAMP="${STAMP_HASH} · $(date -u '+%Y-%m-%d %H:%M UTC')"
echo "→ Injecting build stamp: $BUILD_STAMP"
perl -i -pe "s/__BUILD_STAMP__/${BUILD_STAMP}/g" "$ROOT/www/index.html"

cp "$ROOT/sw.js"                 "$ROOT/www/sw.js"
cp "$ROOT/manifest.json"         "$ROOT/www/manifest.json"
cp "$ROOT/privacy.html"          "$ROOT/www/privacy.html"
for f in favicon.png apple-touch-icon.png icon-192.png icon-512.png icon-maskable-512.png; do
  [ -f "$ROOT/$f" ] && cp "$ROOT/$f" "$ROOT/www/$f"
done

# rm -rf قبل النسخ: cp -r فوق وجهة موجودة يعشّش المصدر داخلها (www/firebase/firebase)
echo "→ Copying firebase/ SDK to www/"
rm -rf "$ROOT/www/firebase"
cp -r "$ROOT/firebase" "$ROOT/www/firebase"

echo "→ Copying models/ to www/"
rm -rf "$ROOT/www/models"
cp -r "$ROOT/models" "$ROOT/www/models"

echo "→ Copying mushaf/ (QCF fonts + layout) to www/"
rm -rf "$ROOT/www/mushaf"
cp -r "$ROOT/mushaf" "$ROOT/www/mushaf"

echo "→ Injecting native-bridge.js into www/index.html"
# Append script tag before </body> — only if not already present
if ! grep -q 'native-bridge.js' "$ROOT/www/index.html"; then
  perl -i -pe 's|</body>|<script src="native-bridge.js"></script>\n</body>|' "$ROOT/www/index.html"
fi

cp "$ROOT/native-bridge.js" "$ROOT/www/native-bridge.js"

echo "✓ www/ ready for cap sync"
