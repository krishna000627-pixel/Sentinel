#!/data/data/com.termux/files/usr/bin/bash
# Commit, push, wait for the CI build, download the signed APK.
set -e
cd "$(dirname "$0")/.."
MSG="${1:-update}"
REPO="${SENTINEL_REPO:-krishna000627-pixel/Sentinel}"
[ -d .git ] || git init -q -b main
git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$REPO.git"
git add -A
git commit -qm "$MSG" || echo "Nothing new to commit"
git push -u origin main
if command -v gh >/dev/null 2>&1; then
  sleep 8
  RUN=$(gh run list --repo "$REPO" --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')
  gh run watch "$RUN" --repo "$REPO" --exit-status
  V=$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
  mkdir -p ~/storage/downloads
  gh release download "v$V" --repo "$REPO" --pattern '*.apk' --dir ~/storage/downloads --clobber
  echo "APK saved to ~/storage/downloads (Sentinel v$V)"
else
  echo "Pushed. Run: pkg install gh  (then gh auth login) to watch the build and download the APK."
fi
