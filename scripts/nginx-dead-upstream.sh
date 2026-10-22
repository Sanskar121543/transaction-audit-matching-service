#!/usr/bin/env bash
# What nginx (with the repo's nginx/nginx.conf) does when an app instance dies. Two stand-in upstreams, a
# few lines of Python with a slow upload endpoint, sit behind it. One is SIGKILLed in the middle of an
# upload, then a GET goes through nginx every 150 ms. Prints how the GETs were answered, per round.
#
# Usage: scripts/nginx-dead-upstream.sh [fail_timeout] [rounds] [polls]
#        (defaults: the value in nginx.conf, 8 rounds, 300 GETs per round, about 50 s each)
# Needs only Docker. Uses host port 18089 and a Docker network named nginx-dead-upstream.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: leave the container paths below alone

cd "$(dirname "$0")/.."
fail_timeout="${1:-}"
rounds="${2:-8}"
polls="${3:-300}"
net=nginx-dead-upstream
# In the working directory: a relative path means the same file to Git Bash and to a Windows docker CLI.
conf=$(mktemp ./.nginx-dead-upstream.XXXXXX)
if [ -n "$fail_timeout" ]; then
    sed "s/fail_timeout=[0-9]*s/fail_timeout=$fail_timeout/g" nginx/nginx.conf > "$conf"
else
    cp nginx/nginx.conf "$conf"
fi
server='
import http.server, time
class H(http.server.BaseHTTPRequestHandler):
    def reply(self):
        self.send_response(200); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"ok")
    def do_GET(self): self.reply()
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0))); time.sleep(3); self.reply()
    def log_message(self, *args): pass
http.server.ThreadingHTTPServer(("", 8080), H).serve_forever()'

cleanup() { docker rm -f nda-app1 nda-app2 nda-nginx > /dev/null 2>&1 || true; }
trap 'cleanup; docker network rm "$net" > /dev/null 2>&1 || true; rm -f "$conf"' EXIT
docker network create "$net" > /dev/null 2>&1 || true

echo "nginx.conf with fail_timeout=$(grep -o 'fail_timeout=[0-9]*s' "$conf" | head -1 | cut -d= -f2), $rounds rounds of $polls GETs"
for round in $(seq 1 "$rounds"); do
    cleanup
    for app in app1 app2; do
        docker run -d --name "nda-$app" --network "$net" --network-alias "$app" python:3.13-alpine \
            python -c "$server" > /dev/null
    done
    docker create --name nda-nginx --network "$net" -p 127.0.0.1:18089:80 nginx:1.30.6-alpine > /dev/null
    docker cp "$conf" nda-nginx:/etc/nginx/nginx.conf
    docker start nda-nginx > /dev/null
    until curl -fs -o /dev/null http://127.0.0.1:18089/v1/runs; do sleep 0.5; done
    # One slow upload per instance; app1 dies while its upload is in flight (nginx resends it to app2).
    for _ in 1 2; do curl -s -o /dev/null -X POST -d x http://127.0.0.1:18089/v1/runs/1/statement & done
    sleep 1
    docker kill nda-app1 > /dev/null
    codes=""
    for _ in $(seq 1 "$polls"); do
        codes="$codes $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18089/v1/runs/1)"
        sleep 0.15
    done
    wait
    lookups=$(docker logs nda-nginx 2>&1 | grep -o 'app1 could not be resolved ([0-9]: [A-Za-z ]*)' \
        | sed 's/.*(\(.*\))/\1/' | tr '\n' ',' | sed 's/,$//')
    echo "round $round: $(echo $codes | tr ' ' '\n' | sort | uniq -c | awk '{printf "%s x %s  ", $1, $2}')(app1 lookups: $lookups)"
done
