#!/usr/bin/env bash
set -euo pipefail
TABPREFIX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
[[ $# -le 1 ]] || { printf 'Usage: bash test-hd.sh [--offline|--skip-build]\n' >&2; exit 1; }
case "${1:-}" in
  --skip-build) ;;
  --offline) bash "$TABPREFIX_ROOT/build.sh" --offline ;;
  '') bash "$TABPREFIX_ROOT/build.sh" ;;
  *) printf 'Usage: bash test-hd.sh [--offline|--skip-build]\n' >&2; exit 1 ;;
esac
# Test the actual shaded deliverable. The temporary copies only relocate Gson imports.
command -v python3 >/dev/null
command -v javac >/dev/null
command -v java >/dev/null
rm -rf "$TABPREFIX_ROOT/.build/hd-tests/sources" "$TABPREFIX_ROOT/.build/hd-tests/classes"
mkdir -p "$TABPREFIX_ROOT/.build/hd-tests/sources" "$TABPREFIX_ROOT/.build/hd-tests/classes" "$TABPREFIX_ROOT/.build/test-data"
python3 - "$TABPREFIX_ROOT" <<'PY'
import pathlib,sys
root=pathlib.Path(sys.argv[1])
for name in ['HdVersionsIntegrationTest','ArtworkIntegrationTest','ArtworkQualityIntegrationTest','HudFinishIntegrationTest','TextQualityIntegrationTest','ContourQualityIntegrationTest']:
    path=root/'tests/me/snowsun/tabprefix/test'/(name+'.java')
    (root/'.build/hd-tests/sources'/(name+'.java')).write_text(path.read_text().replace('com.google.gson','me.snowsun.tabprefix.internal.gson'))
PY
case "$(javac -version 2>&1)" in
  'javac 1.8.'*) TABPREFIX_JAVA_TARGET=(-source 8 -target 8) ;;
  *) TABPREFIX_JAVA_TARGET=(--release 8) ;;
esac
javac -J-Xmx384m "${TABPREFIX_JAVA_TARGET[@]}" -encoding UTF-8 -cp "$TABPREFIX_ROOT/dist/TabPrefix.jar" -d "$TABPREFIX_ROOT/.build/hd-tests/classes" "$TABPREFIX_ROOT/.build/hd-tests/sources/"*.java
for TABPREFIX_TEST in ArtworkIntegrationTest ArtworkQualityIntegrationTest HudFinishIntegrationTest HdVersionsIntegrationTest TextQualityIntegrationTest ContourQualityIntegrationTest; do
  java -Xmx512m -Djava.awt.headless=true -cp "$TABPREFIX_ROOT/.build/hd-tests/classes:$TABPREFIX_ROOT/dist/TabPrefix.jar" "me.snowsun.tabprefix.test.$TABPREFIX_TEST" "$TABPREFIX_ROOT"
done
