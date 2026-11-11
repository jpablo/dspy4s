#!/usr/bin/env bash
# Makes f01.txt to f10.txt. The file fNN.txt holds the number NN * 3, so the sum is 165. With the step limit 10, the
# model must read f10.txt in the last step that can run a cell, and answer in the step after it.
set -euo pipefail
for i in $(seq 1 10); do
  printf '%d\n' $(( i * 3 )) > "$(printf 'f%02d.txt' "$i")"
done
