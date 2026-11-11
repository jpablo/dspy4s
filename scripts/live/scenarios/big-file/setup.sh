#!/usr/bin/env bash
# Makes app.log: 3000 lines of about 55 characters, an ERROR line every 250 lines, and a last line to find.
set -euo pipefail
for i in $(seq 1 3000); do
  if (( i % 250 == 0 )); then
    printf '2026-11-10 12:%02d:%02d ERROR request %d failed after %d ms\n' $(( i / 60 % 60 )) $(( i % 60 )) "$i" $(( i * 7 % 900 ))
  else
    printf '2026-11-10 12:%02d:%02d INFO request %d handled in %d ms\n' $(( i / 60 % 60 )) $(( i % 60 )) "$i" $(( i * 7 % 900 ))
  fi
done > app.log
echo '2026-11-10 13:00:00 ERROR FAKE-LAST: the disk /data is full' >> app.log
