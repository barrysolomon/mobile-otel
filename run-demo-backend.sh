#!/usr/bin/env bash
# Start/stop the demo backend server.
# Usage:
#   ./run-demo-backend.sh            # start (foreground, with logs)
#   ./run-demo-backend.sh --bg       # start in background
#   ./run-demo-backend.sh --stop     # stop running backend
#   ./run-demo-backend.sh --status   # check if running
set -euo pipefail
source "$(dirname "$0")/scripts/demo-common.sh"

ACTION="foreground"
for arg in "$@"; do
  case "$arg" in
    --bg)     ACTION="background" ;;
    --stop)   ACTION="stop" ;;
    --status) ACTION="status" ;;
  esac
done

case "$ACTION" in
  stop)
    stop_backend
    ;;
  status)
    if curl -sf http://localhost:3001/api/doctors > /dev/null 2>&1; then
      ok "Backend is running on port 3001"
      pids=$(lsof -ti :3001 2>/dev/null || true)
      [ -n "$pids" ] && echo "  PIDs: $pids"
    else
      warn "Backend is not running"
    fi
    ;;
  background)
    start_backend
    ;;
  foreground)
    log "Starting demo backend (foreground, Ctrl+C to stop)"
    cd "$DEMO_BACKEND"
    npm install --silent 2>/dev/null
    exec npm run dev
    ;;
esac
