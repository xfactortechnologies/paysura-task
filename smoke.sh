#!/usr/bin/env bash
# Exercises every trigger against a running stub server and prints what came back.
# Start the server first (in another terminal):
#
#     RESOLVE_MS=3000 SLOW_MS=2000 node server.js
#
# then run:  ./smoke.sh
set -u

HOST="${HOST:-http://localhost:4499}"
PAY="$HOST/api/method/paysura.api.accounting.bills.pay_bill"
STATUS="$HOST/api/method/paysura.api.accounting.bills.get_transaction_status"

if ! curl -sf "$HOST/health" >/dev/null; then
	echo "No server at $HOST — start it with: node server.js"
	exit 1
fi

curl -sf -X POST "$HOST/__reset" >/dev/null

pay() { # pay <txn_id> <amount> [curl_timeout]
	curl -s -m "${3:-15}" -w '\n  HTTP %{http_code}\n' -X POST "$PAY" \
		-H 'Content-Type: application/json' \
		-d "{\"transaction_id\":\"$1\",\"agent_id\":\"AGT-0001\",\"biller\":\"mtn\",
		     \"customer_id\":\"920000000\",\"amount\":$2,\"currency\":\"SSP\"}"
}

status() { # status <txn_id>
	curl -s -X POST "$STATUS" -H 'Content-Type: application/json' \
		-d "{\"transaction_id\":\"$1\",\"agent_id\":\"AGT-0001\"}"
}

hr() { printf '\n\033[1m── %s\033[0m\n' "$1"; }

hr ".00  immediate success";        pay t-00 100.00
hr ".04  declined";                 pay t-04 100.04
hr ".05  insufficient_balance";     pay t-05 100.05
hr ".07  401 on first attempt";     pay t-07 100.07
hr ".07  retry succeeds";           pay t-07 100.07
hr ".00  replay -> duplicate";      pay t-00 100.00
hr ".01  slow success (waits)";     pay t-01 100.01
hr ".02  never responds (2s cap)";  pay t-02 100.02 2

# Created last, so the status check below still sees them unresolved even when
# RESOLVE_MS has been turned right down for a fast smoke run.
hr ".03  202 pending";              pay t-03 100.03
hr ".06  500 server error";         pay t-06 100.06

hr "status: pending ones, before they resolve"
for t in t-02 t-03 t-06; do printf '  %-6s ' "$t"; status $t | tr -d '\n '; echo; done

hr "waiting for server-side resolution…"
sleep 5

hr "status: after resolution"
for t in t-02 t-03 t-06; do printf '  %-6s ' "$t"; status $t | tr -d '\n '; echo; done

hr "status: unknown id"
printf '  %-6s ' "nope"; status nope | tr -d '\n '; echo

echo
