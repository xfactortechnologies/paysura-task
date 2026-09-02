#!/usr/bin/env node
'use strict';

/**
 * Paysura POS — paid-exercise stub payment API.
 *
 * A deliberately small, dependency-free stand-in for the real Frappe backend.
 * It exists so every candidate implements against exactly the same server, with
 * exactly the same failure behaviour, so the exercise can be scored fairly.
 *
 * Everything the app needs to be driven through is triggered by the CENTS of the
 * amount — see the trigger table in README.md. Nothing is random and nothing is
 * time-of-day dependent: the same request always produces the same behaviour.
 *
 *     node server.js                 # listens on :4499
 *     PORT=8080 node server.js
 *     SLOW_MS=15000 node server.js   # tune the slow-response trigger
 *
 * No npm install. No dependencies. Node 18+.
 */

const http = require('http');

// ----------------------------------------------------------------- config
const PORT = Number(process.env.PORT || 4499);
/** How long trigger .01 waits before answering (must exceed the app's 8 s budget). */
const SLOW_MS = Number(process.env.SLOW_MS || 12_000);
/** How long a `pending` payment takes to settle server-side, mimicking reconciliation. */
const RESOLVE_MS = Number(process.env.RESOLVE_MS || 30_000);
const QUIET = process.env.QUIET === '1';

const PAY_PATH = '/api/method/paysura.api.accounting.bills.pay_bill';
const STATUS_PATH = '/api/method/paysura.api.accounting.bills.get_transaction_status';

// ----------------------------------------------------------------- state
/**
 * transaction_id -> record. The server is authoritative about what happened,
 * even when the client never found out — which is the whole point of the
 * indeterminate cases.
 */
const store = new Map();

/**
 * transaction_ids that have already been challenged with a 401 (trigger .07).
 * Kept apart from `store` because a challenge is not a payment — nothing was
 * charged, so the retry must be allowed to proceed as a first attempt.
 */
const authChallenged = new Set();

// ----------------------------------------------------------------- helpers
function log(...args) {
	if (!QUIET) console.log(new Date().toISOString().slice(11, 19), ...args);
}

/** Frappe wraps every whitelisted method's return value in a `message` key. */
function ok(res, message) {
	send(res, 200, { message });
}

function send(res, code, obj) {
	const body = JSON.stringify(obj, null, 2);
	res.writeHead(code, {
		'Content-Type': 'application/json',
		'Content-Length': Buffer.byteLength(body),
	});
	res.end(body);
}

/**
 * Mirrors the real error envelope: Frappe's own `exc_type` / `_server_messages`
 * (note the double JSON encoding — that is not a bug here, the real server does
 * it too) plus the stable `error` slug and typed fields your app should switch on.
 */
function apiError(res, code, slug, humanMessage, extra = {}) {
	const excType =
		code === 401 ? 'AuthenticationError' : code === 403 ? 'PermissionError' : 'ValidationError';
	send(res, code, {
		exc_type: excType,
		_server_messages: JSON.stringify([JSON.stringify({ message: humanMessage })]),
		error: slug,
		...extra,
	});
}

/** The trigger is the cents portion of the amount. 100.03 -> 3. */
function triggerOf(amount) {
	return Math.round(Number(amount) * 100) % 100;
}

function reference(txnId) {
	return 'STUB-' + txnId.slice(0, 8).toUpperCase();
}

/** Settle any pending record whose resolve time has passed. */
function refresh(rec) {
	if (rec.status === 'pending' && rec.resolveAt && Date.now() >= rec.resolveAt) {
		rec.status = rec.resolvesTo || 'settled';
		log('resolved', rec.transaction_id, '->', rec.status);
	}
	return rec;
}

function readBody(req) {
	return new Promise((resolve, reject) => {
		let raw = '';
		req.on('data', (c) => {
			raw += c;
			if (raw.length > 1e6) req.destroy();
		});
		req.on('end', () => {
			if (!raw.trim()) return resolve({});
			try {
				resolve(JSON.parse(raw));
			} catch {
				reject(new Error('body is not valid JSON'));
			}
		});
		req.on('error', reject);
	});
}

// ----------------------------------------------------------------- pay_bill
async function handlePay(req, res, body) {
	const txnId = body.transaction_id;
	const amount = Number(body.amount);
	const currency = body.currency || 'SSP';

	if (!txnId) {
		return apiError(res, 417, 'transaction_id_required', 'A transaction_id is required.');
	}
	if (!body.customer_id) {
		return apiError(res, 417, 'customer_id_required', 'Enter the customer reference.', {
			field: 'customer_id',
			label: 'Phone number',
		});
	}
	if (!Number.isFinite(amount) || amount <= 0) {
		return apiError(res, 417, 'amount_invalid', 'Amount must be greater than zero.', {
			amount: body.amount,
			currency,
		});
	}

	// ---- idempotency: a replay is answered from the store, never re-vended.
	const prior = store.get(txnId);
	if (prior) {
		refresh(prior);
		log('replay', txnId, 'status =', prior.status);
		return ok(res, replayEnvelope(prior, currency));
	}

	const trigger = triggerOf(amount);
	const rec = {
		transaction_id: txnId,
		biller: body.biller || 'mtn',
		customer_id: body.customer_id,
		amount,
		currency,
		createdAt: Date.now(),
		status: 'settled',
		resolveAt: null,
		resolvesTo: null,
	};

	switch (trigger) {
		// ---------------------------------------------------------------- 00 success
		case 0:
			store.set(txnId, rec);
			log('pay', txnId, amount, '-> success');
			return ok(res, successEnvelope(rec));

		// ------------------------------------------------- 01 slow success (THE test)
		// The server records the payment immediately and answers only after SLOW_MS,
		// i.e. long after the app's 8-second UI budget has expired. If the client
		// cancelled the request when it stopped watching, this payment is orphaned:
		// the money moved and the app never learned the outcome.
		case 1: {
			store.set(txnId, rec);
			log('pay', txnId, amount, `-> slow success (responding in ${SLOW_MS}ms)`);
			const timer = setTimeout(() => {
				if (!res.writableEnded) ok(res, successEnvelope(rec));
			}, SLOW_MS);
			res.on('close', () => clearTimeout(timer));
			return;
		}

		// ------------------------------------------------------- 02 never responds
		// The connection is held open until the client gives up. The server still
		// records the payment and settles it later, so polling the status endpoint
		// — or replaying the same transaction_id — is the only way to find out.
		case 2:
			rec.status = 'pending';
			rec.resolveAt = Date.now() + RESOLVE_MS;
			rec.resolvesTo = 'settled';
			store.set(txnId, rec);
			log('pay', txnId, amount, '-> hanging forever (recorded pending)');
			return; // deliberately no response, ever

		// ------------------------------------------------- 03 accepted, processing
		case 3:
			rec.status = 'pending';
			rec.resolveAt = Date.now() + RESOLVE_MS;
			rec.resolvesTo = 'settled';
			store.set(txnId, rec);
			log('pay', txnId, amount, '-> 202 pending');
			return send(res, 202, {
				message: {
					status: 'pending',
					transaction_id: txnId,
					currency,
					message: 'Bill payment is pending confirmation; the amount is held pending settlement.',
				},
			});

		// ------------------------------------------------------------- 04 declined
		case 4:
			rec.status = 'declined';
			store.set(txnId, rec);
			log('pay', txnId, amount, '-> declined');
			return ok(res, {
				status: 'declined',
				transaction_id: txnId,
				currency,
				code: 90,
				remote_status: 'REJECTED',
				message: 'The biller declined the payment; no funds were deducted.',
			});

		// -------------------------------------------------- 05 insufficient balance
		case 5:
			log('pay', txnId, amount, '-> insufficient_balance');
			return apiError(res, 417, 'insufficient_balance', 'Insufficient wallet balance.', {
				required: Number(amount.toFixed(2)),
				available: 40.0,
				currency,
			});

		// ------------------------------------------------------------ 06 server error
		case 6:
			rec.status = 'pending';
			rec.resolveAt = Date.now() + RESOLVE_MS;
			rec.resolvesTo = 'reversed';
			store.set(txnId, rec);
			log('pay', txnId, amount, '-> 500 (recorded pending, will reverse)');
			return send(res, 500, { exc_type: 'ServerError', error: 'internal_error' });

		// --------------------------------------------- 07 expired session (optional)
		// The FIRST attempt on a given transaction_id is rejected; the retry
		// succeeds. Nothing is charged by the challenge, so the app should refresh
		// its token and replay the SAME id — which is exactly what makes it safe.
		case 7:
			if (!authChallenged.has(txnId)) {
				authChallenged.add(txnId);
				log('pay', txnId, amount, '-> 401 jwt_expired (retry will succeed)');
				return apiError(res, 401, 'jwt_expired', 'Agent token has expired.');
			}
			store.set(txnId, rec);
			log('pay', txnId, amount, '-> success (after token refresh)');
			return ok(res, successEnvelope(rec));

		default:
			store.set(txnId, rec);
			log('pay', txnId, amount, '-> success (no trigger)');
			return ok(res, successEnvelope(rec));
	}
}

function successEnvelope(rec) {
	return {
		status: 'success',
		transaction_id: rec.transaction_id,
		currency: rec.currency,
		journal_entry: 'ACC-JV-STUB-0001',
		provider_reference: reference(rec.transaction_id),
		provider_cost: Number((rec.amount * 0.97).toFixed(2)),
		agent_commission: Number((rec.amount * 0.02).toFixed(2)),
		remaining_balance: 12450.0,
		// The receipt (A15). Always null here: this stub vends airtime only, and airtime
		// produces nothing the customer needs to carry away. A biller whose vend does —
		// prepaid electricity, where the token *is* the product — fills this with ordered,
		// labelled rows to print. Read null as "this biller has no receipt", which is a
		// real answer, not a gap in the stub.
		vend_data: null,
	};
}

/** What a replayed transaction_id returns, per its recorded state. */
function replayEnvelope(rec, currency) {
	switch (rec.status) {
		case 'pending':
			return {
				status: 'pending',
				transaction_id: rec.transaction_id,
				currency,
				message: 'Bill payment is pending confirmation; the amount is held pending settlement.',
			};
		case 'declined':
			return {
				status: 'declined',
				transaction_id: rec.transaction_id,
				currency,
				code: 90,
				remote_status: 'REJECTED',
				message: 'The biller declined the payment; no funds were deducted.',
			};
		case 'reversed':
			return {
				status: 'reversed',
				transaction_id: rec.transaction_id,
				currency,
				message: 'The bill payment did not complete; the held amount was returned.',
			};
		default:
			return {
				status: 'duplicate',
				transaction_id: rec.transaction_id,
				currency,
				journal_entry: 'ACC-JV-STUB-0001',
				message: 'Transaction already processed.',
			};
	}
}

// ------------------------------------------------------- get_transaction_status
function handleStatus(req, res, body) {
	const txnId = body.transaction_id;
	if (!txnId) {
		return apiError(res, 417, 'transaction_id_required', 'A transaction_id is required.');
	}

	const rec = store.get(txnId);
	if (!rec) {
		// NOT proof that nothing happened — the request may simply not have landed yet.
		log('status', txnId, '-> not_found');
		return ok(res, { status: 'not_found', transaction_id: txnId });
	}

	refresh(rec);
	log('status', txnId, '->', rec.status);
	return ok(res, {
		status: rec.status === 'settled' ? 'settled' : rec.status,
		transaction_id: txnId,
		journal_entry: rec.status === 'settled' ? 'ACC-JV-STUB-0001' : null,
		provider_reference: rec.status === 'settled' ? reference(txnId) : null,
		amount: rec.amount,
		currency: rec.currency,
		biller: rec.biller,
		// Same key and same shape as pay_bill's (A15), and null for the same reason. It is
		// here on every status response because on a real biller this is the *only* path to
		// a receipt for a payment whose pay call never came back.
		vend_data: null,
	});
}

// ----------------------------------------------------------------- routing
const server = http.createServer(async (req, res) => {
	const url = new URL(req.url, `http://${req.headers.host}`);
	const path = url.pathname;

	if (path === '/health') {
		return send(res, 200, { status: 'ok', transactions: store.size });
	}

	// Test helper: wipe state between integration-test runs.
	if (path === '/__reset' && req.method === 'POST') {
		const n = store.size;
		store.clear();
		authChallenged.clear();
		log('reset', n, 'transactions cleared');
		return send(res, 200, { status: 'reset', cleared: n });
	}

	// Test helper: inspect what the server believes happened.
	if (path === '/__transactions') {
		return send(res, 200, { transactions: [...store.values()].map(refresh) });
	}

	if (req.method !== 'POST') {
		return apiError(res, 405, 'method_not_allowed', 'Use POST.');
	}

	let body;
	try {
		body = await readBody(req);
	} catch (e) {
		return apiError(res, 400, 'invalid_json', e.message);
	}

	if (path === PAY_PATH) return handlePay(req, res, body);
	if (path === STATUS_PATH) return handleStatus(req, res, body);

	return apiError(res, 404, 'method_not_found', `Unknown method: ${path}`);
});

// Keep hung requests (trigger .02) open indefinitely rather than letting Node
// time them out — the client must be the one that gives up.
server.requestTimeout = 0;
server.headersTimeout = 0;
server.timeout = 0;
server.keepAliveTimeout = 0;

server.listen(PORT, () => {
	console.log(`\n  Paysura exercise stub API`);
	console.log(`  listening on   http://localhost:${PORT}`);
	console.log(`  pay            POST ${PAY_PATH}`);
	console.log(`  status         POST ${STATUS_PATH}`);
	console.log(`  slow response  ${SLOW_MS} ms      (trigger .01)`);
	console.log(`  pending → settled after ${RESOLVE_MS} ms  (triggers .02 .03 .06)\n`);
});
