#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

PID_FILE="$SCRIPT_DIR/.service_pids"

usage() {
    echo "Usage: $0 [-c|-s|-k|-u|-p|-i|-o|-w workloadfile]"
    echo "  -c              Compile all services"
    echo "  -s              Start all services in the background"
    echo "  -k              Stop all background services"
    echo "  -u              Start UserService"
    echo "  -p              Start ProductService"
    echo "  -i              Start ISCS"
    echo "  -o              Start OrderService"
    echo "  -w workloadfile Run WorkloadParser on workloadfile"
    exit 1
}

case "$1" in
    -s)
        echo "=== Starting all services in background ==="
        > "$PID_FILE"
        (cd "$SCRIPT_DIR/compiled/ISCS"           && java ISCS           config.json) & echo $! >> "$PID_FILE"
        sleep 1
        (cd "$SCRIPT_DIR/compiled/UserService"    && java UserService    config.json) & echo $! >> "$PID_FILE"
        (cd "$SCRIPT_DIR/compiled/ProductService" && java ProductService config.json) & echo $! >> "$PID_FILE"
        (cd "$SCRIPT_DIR/compiled/OrderService"   && java OrderService   config.json) & echo $! >> "$PID_FILE"
        sleep 2
        echo "=== All services started. PIDs: $(cat "$PID_FILE" | tr '\n' ' ') ==="
        ;;
    -k)
        if [ ! -f "$PID_FILE" ]; then
            echo "No PID file found — killing by port instead"
            for port in 14000 14001 15000 16000; do
                pid=$(lsof -ti :$port 2>/dev/null)
                [ -n "$pid" ] && kill $pid && echo "Killed PID $pid on :$port"
            done
        else
            echo "=== Stopping all services ==="
            while read -r pid; do
                if kill "$pid" 2>/dev/null; then
                    echo "Stopped PID $pid"
                fi
            done < "$PID_FILE"
            rm -f "$PID_FILE"
            echo "=== All services stopped ==="
        fi
        ;;
    -c)
        echo "=== Compiling all services ==="
        javac -d "$SCRIPT_DIR/compiled/UserService"    "$SCRIPT_DIR/src/UserService/UserService.java"
        javac -d "$SCRIPT_DIR/compiled/ProductService" "$SCRIPT_DIR/src/ProductService/ProductService.java"
        javac -d "$SCRIPT_DIR/compiled/OrderService"   "$SCRIPT_DIR/src/OrderService/OrderService.java"
        javac -d "$SCRIPT_DIR/compiled/ISCS"           "$SCRIPT_DIR/src/ISCS/ISCS.java"
        javac -d "$SCRIPT_DIR/compiled/WorkloadParser" "$SCRIPT_DIR/src/WorkloadParser/WorkloadParser.java"
        for svc in UserService ProductService OrderService ISCS WorkloadParser; do
            cp "$SCRIPT_DIR/config.json" "$SCRIPT_DIR/compiled/$svc/config.json"
        done
        echo "=== Compilation complete ==="
        ;;
    -u)
        cd "$SCRIPT_DIR/compiled/UserService" && java UserService config.json
        ;;
    -p)
        cd "$SCRIPT_DIR/compiled/ProductService" && java ProductService config.json
        ;;
    -i)
        cd "$SCRIPT_DIR/compiled/ISCS" && java ISCS config.json
        ;;
    -o)
        cd "$SCRIPT_DIR/compiled/OrderService" && java OrderService config.json
        ;;
    -w)
        if [ -z "$2" ]; then usage; fi
        WORKLOAD="$2"
        [[ "$WORKLOAD" != /* ]] && WORKLOAD="$(pwd)/$WORKLOAD"
        cd "$SCRIPT_DIR/compiled/WorkloadParser" && java WorkloadParser config.json "$WORKLOAD"
        ;;
    *)
        usage
        ;;
esac
