# Maze Crypto for Android

The phone version of [Maze Crypto](https://github.com/berk-kucuk/Maze-Crypto): a
read-only crypto market, wallet, portfolio and buying-plan tracker — and, above
all, **home-screen widgets** that show your favorite coins without opening the app.

It is the same app as on Maze Linux: the same price sources and fallbacks, the
same portfolio arithmetic, buying plan, alert rules and assistant tips (ported
from the desktop's `core/` and checked against its tests), the same English and
Turkish texts, and the same OLED-black glass look with one champagne accent.

> **Not investment advice.** Maze Crypto is an information tool. Nothing it
> shows is a recommendation to buy or sell, and the data can be delayed or wrong.
> It never trades and never asks for exchange API keys, private keys or seed phrases.

## Installing the APK

Maze Crypto is not on Google Play and is installed by hand (Android 8.0 or newer).

1. Download `maze-crypto-<version>.apk` and `maze-crypto-<version>.apk.sha256`.
2. Check it is the file that was published:

   ```bash
   sha256sum -c maze-crypto-<version>.apk.sha256
   ```

3. Open it. Android asks permission to install from this source the first time.

Every release is signed with the same key. Its certificate SHA-256 fingerprint:

```
ff:26:25:aa:48:fe:2a:1c:56:87:a0:bb:71:18:6e:e5:b6:e3:ff:e4:71:be:8d:19:9f:59:b3:30:ae:df:15:5a
```

If an update refuses to install with a signature error, the file did not come
from this project — do not force it by uninstalling first.

## Ledger, accounts and Monero

* **Ledger** (Portfolio → Wallets → Ledger): over USB-C/OTG or Bluetooth (Nano X,
  Stax, Flex). Tick every coin you want — BTC, ETH and its networks (incl. BNB
  Chain, Avalanche), SOL, TRX, XMR, LTC — and one connection reads them all; the
  app opens each coin's app on the device, you only confirm. Public keys only.
* **HD accounts**: paste an xpub / ypub / zpub / Ltub and the whole account is
  followed (every receive and change address).
* **Monero view-only**: primary address + private view key (or exported from a
  Ledger), scanned on the phone. **Only over Tor**: install Orbot, start it, and
  check Settings → Tor (default `127.0.0.1 : 9050`). Blocks come from community
  onion nodes; the view key is sealed by the Android Keystore and never leaves
  the phone. No start date is needed: recent payments show up first, then the
  history is read backwards (Wi‑Fi only unless allowed in Settings) and can be
  stopped once the balance is complete. Spends are recognised by the change they return.

## Widgets

Star coins in **Prices** (or during setup) and they appear on the widgets.

| Widget | Placed at | What it shows |
| --- | --- | --- |
| **Favorites** | 4×2 | One dense row per starred coin: ticker, 7-day line, price, change. Up to 8 rows. |
| **Tiles** | 4×2 | Starred coins as tiles — ticker, price, change — from 1×1 up to 4×4. |
| **Coin** | 2×1 | One coin: price and change; its 7-day line when wider or taller; at 4×2 a large chart with the 24h range, 7d and 30d. |
| **Top movers** | 4×2 | The watchlist's biggest risers and fallers side by side (stablecoins left out). |
| **Portfolio** | 2×1 | Value and today's change; profit/loss and the allocation bar when there is room. |
| **Holdings** | 4×2 | Each holding: share of the portfolio as a bar, value, profit/loss. |
| **Buying plan** | 2×1 | This period's amount and state; when it is due, progress and what to buy when there is room. |

All widgets are solid OLED black — fully opaque, the wallpaper never shows
through — with a hairline rim.

Every widget can be resized freely. Each one carries several layouts measured
for a range of sizes and shows the one that fits — a Favorites widget shrunk to
one row turns into a strip of 3–4 coins, two columns wide it becomes a narrow
two-line list. Nothing is clipped and no card is left half empty.

* **Refresh:** every 15 minutes by default in the background (Settings → Widgets:
  15 / 30 / 60 / 120 minutes or off — Android allows nothing more frequent),
  whenever the app is open (every 60 s), and immediately when you tap the
  refresh icon on a widget. The platform's 30-minute widget timer is a
  fallback for phones that hold background jobs back; Settings → Widgets
  shows when the last background refresh ran and links to the battery
  setting when Android is restricting the app.
* **Honest age:** the update time is always on the widget; it turns orange when
  prices are more than an hour old or the last refresh was offline.
* **Tap** a coin to open its chart, anything else to open the app.
* **Change shown:** 1h, 24h or 7d (Settings → Widgets).
* **Private mode:** hide money amounts on the portfolio widget, percentages only.

The widgets draw from a snapshot the app writes after each refresh, so they
never fetch anything themselves and work offline with the last known prices.

## What else is in the app

Basic mode by default, expert mode in Settings — as on the desktop.

- **Home** — portfolio value, today, buying plan and alerts at a glance; the
  assistant's tips about your own data; your favorites.
- **Prices** — your watchlist with 7-day lines; 1h / 24h / 7d / 30d change;
  star coins for the widgets; hold a coin to reorder or remove it.
- **Chart** — candles on 1H / 4H / 1D / 1W with averages and volume, your
  average buy price as a gold line, your own levels, touch to read a candle,
  pinch to zoom, and the chart explained in sentences. Expert: RSI and MACD.
- **Portfolio** — positions, trades and target allocation with the amount
  needed to rebalance and a calculator for new money. Expert: import/export in
  the desktop's CSV/JSON formats (move trades between computer and phone) and
  the yearly realised profit/loss CSV.
- **Wallets** (Portfolio → Wallets, and "Your wallets" on a coin's chart) —
  watch any number of public addresses per coin. Bitcoin, Litecoin, Ethereum
  (+ Base, Arbitrum, Optimism, Polygon), Hyperliquid (spot, perp margin, staked
  HYPE and HyperEVM), Solana and TRON are read from the blockchain — balances,
  known tokens (USDT, USDC, …) and recent transfers; Monero and anything else
  (an exchange account, say) take a balance you enter. Wallet amounts become
  the portfolio's amounts; recorded trades still give the average cost and
  profit/loss, and a coin without recorded buys shows "cost unknown" rather
  than a made-up profit. Each chain has several public providers, tried in turn
  until one answers; when none does, the last balance is shown, marked stale.
  Only public addresses: recovery phrases, private keys and xprv are recognised
  and refused (and wiped from the clipboard), every address is
  checksum-verified, and airdropped spam and zero-value "poisoning" transfers
  are left out.
- **Plan** — a weekly, two-weekly or monthly buying plan, "this period" card,
  check-offs that can record the purchases as trades.
- **Alerts** — price level, price vs. your average cost and fast moves
  (expert: allocation drift, RSI, SMA crosses), notified once per event with a
  cooldown, checked in the background with the widgets.
- **Insights** (expert) — each coin's daily chart summed up in words, a
  correlation heatmap and portfolio concentration.

The desktop's optional local AI (Ollama) is not part of the phone app.

## Privacy

No telemetry, no analytics, no accounts, no ads. The HTTP layer refuses any
host that is not on the allow-list (Settings → About shows it) and only speaks
HTTPS. Expert mode adds a SOCKS5/HTTP proxy — `socks5h://127.0.0.1:9050` with
Orbot sends DNS through Tor too. Trades, settings and watched addresses stay on
the phone (`files/profile.json`, `files/settings.json`, `files/wallets.json`);
the network cache is excluded from backups, and `wallets.json` from cloud
backups (addresses reveal holdings and history to whoever reads the backup).

Watching a wallet sends its address to the public explorer for that chain
(Blockstream / mempool.space, litecoinspace, Blockscout, public RPC nodes —
publicnode, 1rpc, drpc and the networks' own — Solana RPC + Jupiter, TronGrid,
Hyperliquid's API; each tried in turn until one answers). An explorer can link
the address to your IP: use the proxy to keep it out.

Permissions: Internet, notifications (asked for when you turn alerts on) and
"run at startup", so the periodic widget refresh survives a reboot.

## Build

Needs JDK 17+ and the Android SDK (platform 37). Inside the Maze Linux source
tree the shared toolchain provides both (JDK, SDK and Gradle cache all in
`../android-toolchain`):

```bash
source ../android-toolchain/env.sh
```

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

A release build is signed when `keystore.properties` (not in git) points at the
keystore in `keystore/` (not in git either); `./gradlew assembleRelease` then
writes `dist/maze-crypto-<version>.apk` and its `.sha256`. Raise `versionCode`
in `app/build.gradle.kts` for every published release.

Widget layouts are generated — edit `tools/gen_widget_layouts.py`, not the
`res/layout/widget_*.xml` files, and keep the measured sizes in
`widget/Widgets.kt` in step (the provider files in `res/xml/widget_*_info.xml`
are edited by hand). Icons come from Phosphor via `tools/phosphor_to_vector.py`. Interface text
is the desktop's `i18n/en.json` and `tr.json` (copied into `assets/i18n`) plus
`mobile.en.json` / `mobile.tr.json` for phone-only wording.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). Phosphor icons: MIT. Inter: SIL OFL 1.1.
