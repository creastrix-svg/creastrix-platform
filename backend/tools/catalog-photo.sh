#!/usr/bin/env bash
# A local OS operator acts for the single Creastrix development seller. No web-store role is granted.
set -euo pipefail
umask 077
backend=$(cd "$(dirname "$0")/.." && pwd -P)
case "${1:-}" in
  compile)
    [[ $# == 1 ]] || exit 2
    cd "$backend"
    exec ./mvnw -B -ntp test-compile org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath \
      -Dmdep.includeScope=test -Dmdep.outputFile=target/local-catalog-photo.classpath ;;
  seller)
    [[ $# == 3 && "$2" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ && "$3" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || exit 2
    class_file="$backend/target/local-catalog-photo.classpath"
    [[ -s "$class_file" && -f "$backend/target/test-classes/com/creastrix/platform/localcatalog/LocalSellerPhotoCommand.class" ]] || {
      echo 'Run bash backend/tools/catalog-photo.sh compile first.' >&2; exit 1; }
    export CREASTRIX_PHOTO_CLASSPATH="$backend/target/test-classes:$backend/target/classes:$(<"$class_file")"
    exec bash "$backend/tools/local-catalog-db.sh" run creastrix-local-photo-writer node "$backend/tools/catalog-photo-panel.mjs" "$2" "$3" ;;
  *) echo 'Usage: bash backend/tools/catalog-photo.sh {compile|seller PRODUCT VARIANT}' >&2; exit 2 ;;
esac
