#!/usr/bin/env bash
# Headless NeoForge client for join testing.
#
# No GPU: render through llvmpipe at a tiny resolution, otherwise the menu panorama
# eats whole cores.
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLSL_VERSION_OVERRIDE=460
# No sound card either. A failed SoundEngine.reload aborts the whole resource reload, so
# onGameLoadFinished() never runs and --quickPlayMultiplayer never fires. OpenAL Soft has a
# null backend for exactly this.
export ALSOFT_DRIVERS=null
# 126 mods, Distant Horizons among them, do not fit portablemc's default 2G heap. The render
# thread stalls in GC, CreatePotato flips to potato mode, the client stops answering keep-alives,
# and the server drops it about thirty seconds after the join - which reads exactly like a server
# fault and is not one. --jvm-args replaces the defaults, so the GC flags are repeated here.
JVM_ARGS="-Xmx6G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M"
cd /home/user/milky/eturlia_new/client
exec xvfb-run -a -s "-screen 0 320x240x24" ./venv/bin/portablemc \
  --main-dir /home/user/milky/eturlia_new/client/mc --work-dir /home/user/milky/eturlia_new/client/mc \
  start neoforge:21.1.248 --resolution 320x240 --jvm-args "$JVM_ARGS" \
  -u EturliaTester -s 127.0.0.1 -p 25963
