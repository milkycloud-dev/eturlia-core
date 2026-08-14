#!/usr/bin/env bash
# Compile the probe plugin against the server that is actually built, and deploy it.
set -euo pipefail
N=/home/user/milky/eturlia_new
SRC=$N/tools/probe
OUT=$SRC/classes
API=$N/core/Folia-API/build/libs/folia-api-1.21.1-R0.1-SNAPSHOT.jar
SERVER=$N/core/Folia-Server/build/libs/folia-server-1.21.1-R0.1-SNAPSHOT-mojang-mapped.jar
LIBS=$(find "$N/core/Folia-Server/build/eturlia/standalone/libraries" -name '*.jar' 2>/dev/null | tr '\n' ':')
JAVA=$(dirname "$(readlink -f "$(command -v javac)")")

rm -rf "$OUT"
mkdir -p "$OUT"
javac -nowarn -proc:none --release 21 \
      -cp "$API:$SERVER:$LIBS" \
      -d "$OUT" "$SRC/eturlia/probe/EturliaProbe.java"

cp "$SRC/plugin.yml" "$OUT/plugin.yml"
cd "$OUT"
python3 -c "import os,zipfile,sys; out=sys.argv[1]; z=zipfile.ZipFile(out,chr(119),zipfile.ZIP_DEFLATED); [z.write(os.path.join(r,f), os.path.relpath(os.path.join(r,f),chr(46)).replace(os.sep,chr(47))) for r,_,fs in os.walk(chr(46)) for f in fs]; z.close()" "$N/server/plugins/EturliaProbe.jar"
echo "built $(stat -c%s "$N/server/plugins/EturliaProbe.jar") bytes"
