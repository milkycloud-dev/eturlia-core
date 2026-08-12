#!/usr/bin/env bash
# Type a line into the running headless client's chat. Folia refuses entity selectors from the
# console (the console runs on the global region), so anything that has to look at entities has to
# be typed by a player - this is that player's keyboard.
set -uo pipefail
CPID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
[ -n "$CPID" ] || { echo "no client running"; exit 1; }
export DISPLAY=$(strings /proc/$CPID/environ | grep '^DISPLAY=' | head -1 | cut -d= -f2)
export XAUTHORITY=$(strings /proc/$CPID/environ | grep '^XAUTHORITY=' | head -1 | cut -d= -f2)
WIN=$(xdotool search --name Minecraft | tail -1)
[ -n "$WIN" ] || { echo "no client window"; exit 1; }

xdotool windowfocus "$WIN"
for line in "$@"; do
    xdotool key t
    sleep 1
    xdotool type --delay 45 "$line"
    sleep 1
    xdotool key Return
    sleep 2
done
