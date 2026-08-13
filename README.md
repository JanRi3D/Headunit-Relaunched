# Headunit Relaunched

An Android Auto **receiver** (head unit) for Android, targeting API 16 and
1GB single-DIN units. USB (AOA) and wireless (Bluetooth + TCP) transports feed
the same protocol session.

Reference implementations used while writing this: [aasdk] / [OpenAuto] for the
protocol and message ids, and the various open-headunit projects for the AOA
identity strings and the wireless Bluetooth handshake.

[aasdk]: https://github.com/f1xpl/aasdk
[OpenAuto]: https://github.com/f1xpl/openauto

## TLS identity

`app/src/main/res/raw/cert` and `res/raw/privkey` are Google's published
head-unit reference identity — the certificate Android Auto requires a head
unit to present:

```
subject: O=Google-Android-Reference, L=Mountain View, ST=CA, C=US
issuer : O=Google Automotive Link, L=Mountain View, ST=California, C=US
RSA 2048, valid until 2044-07-08
```

Taken from [open-headunit] (AGPL-3.0), which is also where the loading approach
comes from. No build step: they are plain PEM, read straight out of `res/raw`.

Two details in [`Ssl.java`](app/src/main/java/me/ri3d/headunit/relaunched/protocol/Ssl.java)
matter and are easy to get wrong:

- the key is **PKCS#8** (`BEGIN PRIVATE KEY`), so it loads via
  `PKCS8EncodedKeySpec` + `KeyFactory("RSA")` — no keystore file, no `openssl`
- the `KeyManager` returns its alias **unconditionally**. A stock KeyManager
  only offers a client certificate when the server's `CertificateRequest` names
  an issuer it recognises; AA's request does not line up, so the default returns
  no alias, no certificate is sent, and the phone drops the connection
  mid-handshake with nothing useful in the log.

[open-headunit]: https://github.com/andreknieriem/open-headunit

## Build

```bash
./gradlew :app:assembleDebug
```

Produces a **2.5 MB release APK**, of which ~2.4 MB is Conscrypt's native
libraries for three ABIs. The Java side is 370 classes; the app's own code is
56 of them. Exactly one runtime dependency (Conscrypt — see below): no AndroidX,
no support library, no protobuf runtime, not even kotlin-stdlib (AGP 9 adds it
by default and `app/build.gradle.kts` excludes it, worth ~1000 classes of dex).
AndroidX would also floor you at API 21.

Once you know your unit's ABI, narrowing `abiFilters` in
[`app/build.gradle.kts`](app/build.gradle.kts) to `armeabi-v7a` alone gets the
APK to ~1.4 MB.

Protocol unit tests (they run on the JVM, no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

## Connecting

**USB** — plug the phone in. The service sees the attach broadcast, flips the
phone into AOA accessory mode and connects on its own.

**Wi-Fi, manual IP (easiest to get working)** — type the phone's IP into the box
and hit Connect. This dials `phoneIp:5277`, Android Auto's *head unit server*,
so on the phone you first need:

> Android Auto → About → tap the header 10× to unlock Developer settings →
> ⋮ menu → **Start head unit server**

Both devices just have to be on the same network. No Bluetooth, no hotspot
credentials, no pairing — this is the same listener the Desktop Head Unit talks
to over an ADB forward, which is why it is the most reliable path to a first
picture. The IP is remembered, and a dropped connection retries every 3s, so
restarting the head unit server on the phone reconnects by itself.

**Wi-Fi, production wireless AA** — the Wi-Fi button. Here the roles are
reversed: we listen on 5288 and the phone dials *us*, but only after the
Bluetooth RFCOMM handshake tells it where to go. Needs `Config.WIFI_SSID` /
`WIFI_PASSWORD` to match your hotspot. This is the least-verified path (see
below).

## Layout

```
MainActivity          SurfaceView + overlay, plain android.app.Activity
HeadUnitService       owns the connection across activity restarts

transport/
  Transport           connect / read / write / close -- the only thing protocol/ knows
  UsbTransport        USB host, phone as AOA accessory
  WifiTransport       dial phone:5277, or listen on 5288
usb/UsbAoa            the AOA control transfers that flip a phone into accessory mode
wifi/WirelessDiscovery  Bluetooth RFCOMM handshake, listen mode only

protocol/
  AndroidAutoSession  reader thread + control channel state machine
  HandshakeManager    version exchange, TLS, auth complete
  MessageParser       frames in: defragment, decrypt, dispatch
  MessageWriter       messages out: fragment, encrypt, one write per frame
  ChannelManager      routes non-control channels
  Ssl                 SSLEngine driven by hand (records ride inside AA messages)
  Proto / Messages    ~250 line protobuf codec + the AA messages we use
  ProtocolConstants   wire constants

video/  VideoChannel, VideoDecoder      MediaCodec -> Surface, no Bitmap anywhere
audio/  AudioChannel, AudioOutput, MicChannel
input/  InputChannel, TouchInput, KeyInput
sensor/ SensorChannel                   driving status + night mode
util/   Logger, Utils
Config                                  every tunable, one file
```

## Threads

Four, and no more:

| thread | what it does | why it exists |
|---|---|---|
| `hu-session` | reads frames, decrypts, dispatches, sends replies | the whole protocol |
| `hu-video` | drains MediaCodec output, renders to the Surface | `dequeueOutputBuffer` blocks |
| `hu-audio-*` | writes PCM to an AudioTrack | `AudioTrack.write` blocks; created only when a stream actually starts |
| `hu-mic` | reads the mic, sends PCM | only while the phone has the mic open |

Nothing polls. Every wait is a blocking call with a timeout, not a spin.

## Allocation behaviour

After connect, the steady state allocates nothing per frame:

- one 48KB receive ring in `MessageParser`, frames sliced out of it in place
- one-frame-in-one-frame messages dispatch straight from the receive ring, no copy
- `MessageWriter.begin()` hands out a reusable `Proto.W` under a lock and
  back-fills the message id in place, so no gather step
- `Ssl` caches its `ByteBuffer` wrappers per backing array
- `AudioOutput` recycles a fixed ring of slots forever

## Tuning for slow hardware

Everything lives in [`Config.java`](app/src/main/java/me/ri3d/headunit/relaunched/Config.java).
The knobs that matter, in order:

- `VIDEO_RESOLUTION` / `VIDEO_WIDTH` / `VIDEO_HEIGHT` — 800x480 is `RES_480P`
- `VIDEO_FPS` — `FPS_30`; 60 roughly doubles decode cost
- `VIDEO_DPI` — how large AA draws its UI
- `Logger.LEVEL` — set to `Log.WARN` on a slow unit; logcat writes are
  synchronous and per-frame logging visibly costs you frames

## Status

Confirmed working against a real phone running Android Auto **1.7**, over the
manual-IP transport: TLS handshake, service discovery, all seven channels,
input binding, sensors, H.264 video decoding and rendering at ~28-30fps, and
audio channel setup/start.

## Known-uncertain parts

- **Wireless Bluetooth handshake** (`wifi/WirelessDiscovery.java`), i.e. the
  Wi-Fi *listen* path only. The RFCOMM message ids and field numbers come from
  the AA-wireless dongle projects and are the least verified code in the repo.
  Also set `Config.WIFI_SSID` / `WIFI_PASSWORD` to match your actual hotspot —
  the app does not bring the AP up for you. **The manual-IP path avoids all of
  this**, so use it first when bringing a new unit up.
## The FRAME_CONTROL bit

Worth knowing, because getting it wrong produces no error at all.

`FRAME_CONTROL` (0x04) marks a message whose id belongs to the *control-channel*
range (1..26) but which is delivered on a **media** channel. In practice that is
exactly one message: `CHANNEL_OPEN_RESPONSE`, which goes out on the channel being
opened rather than on channel 0.

```
CHANNEL_OPEN_RESPONSE on channel 3   -> flags 0x0f   (FIRST|LAST|CONTROL|ENCRYPTED)
SENSOR_EVENT (0x8003) on channel 2   -> flags 0x0b   (no CONTROL)
VIDEO_FOCUS  (0x8008) on channel 3   -> flags 0x0b
anything at all on channel 0         -> flags 0x0b
```

Send the open response without the bit and the phone requests every channel,
silently discards each response, and the session then sits idle forever after
service discovery — no error, no disconnect, no log line. `needsControlFlag()`
in `ProtocolConstants` is the rule.

## TLS: why Conscrypt is not optional

API 16's built-in JSSE has TLS 1.2, but its cipher suites stop at
`ECDHE_RSA_WITH_AES_128_CBC_SHA` — the AES-GCM suites only arrived around
API 20. Current Android Auto will not negotiate anything older, so the
handshake is refused and the phone shows:

> **Communication error 7** — Android Auto can't connect to your car due to a
> security problem with the car display.

That message means TLS, not wiring. [Conscrypt] is BoringSSL packaged as a JSSE
provider; it brings the modern suites to old Androids. `2.5.3` is the last
release that supports minSdk 16 (its manifest declares 9) — 2.6.x needs a
higher floor, so **do not bump it** without testing on a 4.x device.

Three rules in `Ssl`, all of them learned the hard way. Break any of them and
you get a native crash, not an exception:

- **The private key must come from Conscrypt's own `KeyFactory.**
  `KeyFactory.getInstance("RSA")` on API 16 returns a key backed by the
  *platform's* OpenSSL. Conscrypt bundles a separate BoringSSL, so signing the
  client CertificateVerify with that key dereferences a foreign pointer:

  ```
  signal 11 (SIGSEGV), fault addr 00000001
    #00  RSA_sign_pss_mgf1+38
    #02  EVP_PKEY_sign
    #03  EVP_DigestSignFinal
    #08  SSL_do_handshake
  ```

  Passing the provider explicitly fixes it — check the log says
  `private key org.conscrypt.OpenSSLRSAPrivateKey`, not a platform class.
  (open-headunit gets this for free via `Security.insertProviderAt`, which makes
  Conscrypt the default for everything.)
- **Never call `unwrap()` with nothing buffered.** After wrapping the
  ClientHello the status goes straight to `NEED_UNWRAP`, but the peer has
  obviously not replied yet. The platform provider returned `BUFFER_UNDERFLOW`;
  Conscrypt does not.
- **Destination buffers are `allocateDirect`, and handshake wraps pass an empty
  `ByteBuffer[]`, not `ByteBuffer.allocate(0)`.** This matches what
  open-headunit does; heap destinations take a much less exercised JNI path.

`Ssl` logs this on every connection; if the GCM count is 0, Conscrypt did not
load and the handshake will fail:

```
SSL: engine=org.conscrypt.Java8EngineWrapper protocols=TLSv1.2,... suites=N (M GCM)
```

[Conscrypt]: https://github.com/google/conscrypt

## Debugging

```bash
adb logcat -s HU
```

The session logs each state transition (`handshake`, `TLS handshake`,
`authenticated`, `negotiating channels`) so you can see exactly where a
connection dies, plus `rx ch=N msg=0xNNNN` for every non-media message received.

**Set `Logger.LEVEL = Log.WARN` for real use.** Logcat writes are synchronous
and the per-message trace costs frames on a weak CPU.

### Emulator caveats

An API 16 x86 AVD is the worst case for this app and will mislead you:

- `screencap` cannot read back the SurfaceView layer, so screenshots are black
  even while video plays. Check `dumpsys SurfaceFlinger` for
  `Layer (SurfaceView) activeBuffer=[800x480]` instead.
- Hardware GPU emulation often drops the SurfaceView entirely. Switch the AVD
  to *Software - GLES 2.0* if you see nothing.
- Audio output is a stub (`AudioStreamOutGeneric`), so AudioFlinger never drains
  the track and you get endless `obtainBuffer timed out (is the CPU pegged?)`.
  That warning is meaningless on the emulator.
