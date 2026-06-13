#!/usr/bin/env bash
# Copies web assets from repo root → www/ before every cap sync.
# The root files are the source of truth (served by GitHub Pages).
# www/ is Capacitor's webDir — gets patched to load native-bridge.js.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "→ Copying web assets to www/"
cp "$ROOT/index.html"            "$ROOT/www/index.html"
cp "$ROOT/sw.js"                 "$ROOT/www/sw.js"
cp "$ROOT/manifest.json"         "$ROOT/www/manifest.json"
for f in favicon.png apple-touch-icon.png icon-192.png icon-512.png icon-maskable-512.png; do
  [ -f "$ROOT/$f" ] && cp "$ROOT/$f" "$ROOT/www/$f"
done

echo "→ Injecting native-bridge.js into www/index.html"
# Append script tag before </body> — only if not already present
if ! grep -q 'native-bridge.js' "$ROOT/www/index.html"; then
  sed -i 's|</body>|<script src="native-bridge.js"></script>\n</body>|' "$ROOT/www/index.html"
fi

cp "$ROOT/native-bridge.js" "$ROOT/www/native-bridge.js"

echo "✓ www/ ready for cap sync"
