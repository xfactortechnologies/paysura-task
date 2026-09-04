# Paysura POS - Submission

This repository contains the Android implementation for the Paysura POS application exercise. The app satisfies all the core constraints: it correctly handles intermittent network failures, journals transactions before they leave the device, and keeps the network requests alive even if the UI times out.

## How to test this app

1. **Start the API server**: Run `node server.js` from the project root.
2. **Run the App**: Launch the app on an emulator (ensure the server points to `10.0.2.2:4499`) or physical device.
3. **Execute a payment**:
   - For a **happy path**, enter `100.00` and submit.
   - For a **timeout test**, enter `100.01` and submit.
4. **Observe the timeout**: With `100.01`, the UI will show a loading state and then revert to the main screen after an 8-second budget. The transaction will appear in the list below as `PENDING`. Wait another ~4-5 seconds, and the list will automatically update the status to `SUCCESS` once the background request finally completes.

## Implementation Details

### Keeping the request alive past the UI budget

The network request's lifecycle is detached from the UI's observation budget using a separate CoroutineScope.
- **Where the scope lives:** The `PaymentRepository` takes an `applicationScope` (provided via DI, bound to the Application lifecycle, or a custom scope not tied to the ViewModel).
- **How it works:** When the UI triggers a payment, the `ViewModel` calls the repository. The repository launches the network call inside its `applicationScope` and updates the Room database directly when it finishes. The `ViewModel` merely observes the Room database using a Flow, and uses `withTimeout(8000)` strictly for its own "loading" state. When the 8 seconds expire, the UI loading indicator stops, but the repository's coroutine keeps running until the server responds, subsequently updating the local journal.

### Journal states and transitions

The app's local database (`JournalEntry`) relies on the following states to map the server's vocabulary and local uncertainties:

- **`PENDING`**: The initial state. Written to Room *before* the network request is fired. It transitions to:
  - **`SUCCESS`**: If the server returns `success` or `duplicate`.
  - **`DECLINED`**: If the server returns `declined`.
  - **`REVERSED`**: If the server returns `reversed`.
- **Note on Network Failures / Timeouts**: If a network error or HTTP 5xx occurs, the state remains `PENDING`. The same `transaction_id` is kept, fulfilling the rule to reuse the ID when the outcome is unknown. A startup resolver or manual retry checks the status later.

### What I would do differently with more time

1. **WorkManager for robust retries:** The current implementation uses a startup resolver to clean up dangling `PENDING` transactions. With more time, I would implement a `WorkManager` periodic task to poll the status endpoint in the background. This ensures reconciliation even if the user backgrounds the app but doesn't fully kill and restart it.
2. **Granular Error Handling:** Instead of generic error messages, I would map the specific server error slugs (e.g., `insufficient_balance`) to localized string resources to provide better context to the agent.
3. **Robust UI Testing:** I would add UI integration tests using Compose testing rules to physically assert the 8-second timeout behavior and verify that the `PENDING` state is properly displayed and then updated.

### Disagreements and Ambiguities (Pushback)

One aspect of the API design that I would strongly push back on is the double-JSON-encoded `_server_messages` field returned in error responses. 

While the brief explicitly warns not to parse it and notes that the real server actually behaves this way, returning doubly-encoded JSON strings is a backend anti-pattern. Even if the client ignores it, it complicates network debugging, makes the raw payload harder to read for developers, and adds unnecessary byte overhead. A better approach would be for the backend to return a properly structured JSON array or object natively.
