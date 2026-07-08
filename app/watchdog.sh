#!/bin/bash
# Watchdog: auto-restarts yano-node on crash, logs deaths
cd /Users/satya/work/bloxbean/yano/app

while true; do
    echo "$(date): Starting yano-node..." >> watchdog.log
    /Users/satya/.sdkman/candidates/java/25.0.2-librca/bin/java -jar build/yano-node.jar >> sync.log 2>&1
    EXIT_CODE=$?
    echo "$(date): yano-node exited with code $EXIT_CODE" >> watchdog.log

    # Check if we should stop (touch stop-watchdog to exit)
    if [ -f stop-watchdog ]; then
        echo "$(date): stop-watchdog file found, exiting watchdog" >> watchdog.log
        rm stop-watchdog
        exit 0
    fi

    echo "$(date): Restarting in 5 seconds..." >> watchdog.log
    sleep 5
done
