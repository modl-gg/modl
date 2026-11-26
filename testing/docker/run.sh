#!/bin/sh
set -eu
: "${SERVER_JAR:?SERVER_JAR is required}"
CONSOLE_FIFO=/tmp/modl-console
rm -f "$CONSOLE_FIFO"
mkfifo "$CONSOLE_FIFO"
cd /data
export HOME=/tmp
exec java ${JVM_OPTS:-} -jar "$SERVER_JAR" ${SERVER_ARGS:-} 0<>"$CONSOLE_FIFO"
