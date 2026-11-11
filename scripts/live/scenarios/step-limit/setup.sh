#!/usr/bin/env bash
# Makes f01.txt to f14.txt. The file fNN.txt holds the number NN * 3, so the sum is 315.
set -euo pipefail
for i in $(seq 1 14); do
  printf '%d\n' $(( i * 3 )) > "$(printf 'f%02d.txt' "$i")"
done
