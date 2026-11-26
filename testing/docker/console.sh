#!/bin/sh
set -eu
CONSOLE_FIFO=/tmp/modl-console
if [ ! -p "$CONSOLE_FIFO" ]; then
  echo "console fifo $CONSOLE_FIFO is missing; the server was not started by run.sh" >&2
  exit 1
fi
printf '%s\n' "$*" > "$CONSOLE_FIFO"
