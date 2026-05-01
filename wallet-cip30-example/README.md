# Yano CIP-30 Wallet Example

This is a browser dApp demo for Yano Wallet's local CIP-30 bridge. It can also
connect to normal browser extension wallets such as Eternl, Lace, or other
CIP-30-compatible wallets when they are installed.

## What It Demonstrates

- Loading the local Yano bridge shim from `http://127.0.0.1:47000/yano-cip30.js`.
- Discovering all `window.cardano` wallets.
- Connecting to one selected wallet through the standard CIP-30 flow.
- Reading network id, balance, UTXOs, change address, and reward addresses.
- Building a multi-recipient ADA/native-asset transaction with Mesh.
- Adding optional CIP-20 message metadata.
- Requesting wallet approval for `signTx`.
- Submitting the signed transaction through the selected wallet.

## Run

Start Yano Wallet, unlock a wallet, and start the Local Bridge from the
Developer page. The wallet bridge uses a reserved deterministic loopback
endpoint:

```text
http://127.0.0.1:47000/cip30
http://127.0.0.1:47000/yano-cip30.js
```

Then run this example:

```bash
npm install
npm run dev
```

Open `http://127.0.0.1:5174`. The page includes the fixed Yano shim URL in
`index.html`, so Yano Wallet appears automatically when the bridge is already
running. If the bridge is started after the page loads, click **Load Yano Shim**
and then **Discover Wallets**. The Yano wallet should appear beside any
extension wallets already injected into the browser.

You can also prefill the bridge URL:

```text
http://127.0.0.1:5174/?yanoBridge=http://127.0.0.1:47000/cip30
```

For another dApp, the standard local script include is:

```html
<script src="http://127.0.0.1:47000/yano-cip30.js"></script>
```

## Security Model

This demo never asks for or stores mnemonics, private keys, wallet passwords, or
vault material. All signing remains inside the connected wallet.

The app only loads `yano-cip30.js` from `http://127.0.0.1` or
`http://localhost`. Do not change that rule for demos: a dApp should not load a
wallet bridge shim from a remote host.

The fixed port makes local dApp development deterministic; it is not a security
boundary. A malicious local process could bind the same port before Yano Wallet
starts and serve a fake shim, so production dApps should treat wallet responses
as untrusted until the real wallet approval prompt appears and should prefer a
browser extension/native-messaging flow for arbitrary websites.

Any web page can ask a CIP-30 wallet to connect or sign. Yano Wallet must treat
the browser app as untrusted, show the request origin, decode the transaction
where possible, and require explicit approval before signing or submitting.

## Build And Test

```bash
npm test
npm audit
npm run build
```

The build intentionally uses the narrow Mesh packages needed by this example
instead of the full `@meshsdk/core` umbrella package, keeping the production
audit surface smaller for a browser demo.
