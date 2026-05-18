#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
    echo "Usage: $0 [-c|-u|-p|-i|-o|-w workloadfile]"
    echo "  -c              Compile all services"
    echo "  -u              Start UserService"
    echo "  -p              Start ProductService"
    echo "  -i              Start ISCS"
    echo "  -o              Start OrderService"
    echo "  -w workloadfile Run WorkloadParser on workloadfile"
    exit 1
}

case "$1" in
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
