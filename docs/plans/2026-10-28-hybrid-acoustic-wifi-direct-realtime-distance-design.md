# Hybrid Acoustic + Wi-Fi Direct Realtime Distance Design

**Date:** 2026-10-28
**Status:** Validated with the user
**Scope:** Direct physical ranging between the Server laptop and Client laptops

## 1. Objective

Extend the LAN device-management system with realtime Server-to-Client distance ranging that combines:

- two-way near-ultrasonic acoustic time-of-flight as the primary physical measurement;
- Wi-Fi Direct as the control and result transport channel;
- Wi-Fi RSSI fingerprinting as a range-only fallback and plausibility check.

The target acceptance criterion is at least 90% of held-out physical measurements within 5 cm of ground truth in the validated operating range. A displayed confidence score is not by itself evidence that this accuracy target has been met.

## 2. Non-goals and Truthfulness Rules

- TCP/ICMP RTT must never be converted into physical metres.
- RSSI must not be presented as centimetre-accurate distance.
- Acoustic and RSSI estimates must not be averaged with fixed weights.
- The system must not claim inaudible operation until both laptops pass the audio capability and audible-leakage checks.
- If the hardware cannot reliably transmit and capture above 20 kHz, acoustic ranging is disabled and the UI falls back to a Wi-Fi distance range.
- This feature is a ranging aid for the network-management project, not a safety-critical or medical measurement system.

## 3. Architecture

```text
                            Wi-Fi Direct / TCP
        Server  <-------------------------------------->  Client Agent
          |       session control, capabilities, results       |
          |                                                     |
          +---------- two-way 20-22 kHz acoustic chirps --------+
                                |
                                v
                    Confidence-gated fusion service
                         /                  \
             acoustic distance         Wi-Fi range fallback
                         \                  /
                                v
                    Dashboard + history/audit
```

Wi-Fi Direct remains continuously connected and identifies the intended Client. Acoustic ranging measures the physical path. The Server schedules Clients so that their chirps never overlap.

## 4. Approaches Considered

### 4.1 Fixed weighted fusion

Rejected. A noisy RSSI estimate can pull an otherwise valid acoustic estimate away from the physical distance.

### 4.2 Acoustic-only

Rejected as the sole mode. Commodity laptop speakers and microphones may filter the 20-22 kHz band, and noise or multipath can temporarily invalidate a measurement.

### 4.3 Confidence-gated cascade — selected

Use acoustic distance only when its confidence is at least 90. Retry uncertain acoustic sessions, then fall back to a Wi-Fi fingerprint range. RSSI is used for fallback and plausibility checking, not as a precise correction term.

## 5. Realtime Scheduling

The target UI cadence is at least one accepted update per second for a single Client. Each acoustic session uses a short 100-250 ms recording buffer. With multiple Clients, the Server assigns non-overlapping slots:

```text
0-500 ms       Client B
500-1000 ms    Client N
1000-1500 ms   Client T
```

The actual slot length is derived from the configured maximum distance, audio-device startup latency, and retry policy. Scheduling must not enqueue unbounded work when a Client is slow or offline.

## 6. Protocol

Add four control-envelope message types:

- `RANGING_CAPABILITIES`: supported playback/capture devices, sample rates, channels, tested frequency band, and acoustic capability state;
- `RANGING_START`: session ID, Client ID, slot, waveform ID, sample rate, recording duration, and Server nonce;
- `RANGING_RESULT`: detected sample indices, SNR, peak metrics, quality flags, and measurement timestamp;
- `RANGING_ABORT`: session ID and a typed failure reason.

Illustrative result payload:

```json
{
  "type": "RANGING_RESULT",
  "sessionId": "range-2048",
  "selfPeakSample": 12480,
  "peerPeakSample": 19342,
  "sampleRate": 96000,
  "snrDb": 18.6,
  "peakRatio": 4.2,
  "audibleLeakageDb": -52.0,
  "qualityFlags": []
}
```

Network timestamps are not used as acoustic time-of-flight. Each side records both its own transmission and the peer transmission, then the Server derives two-way time-of-flight from the detected sample positions. This removes network jitter and avoids requiring synchronized wall clocks.

## 7. Acoustic Signal and Hardware Gate

Use a tapered chirp within the highest mutually supported band above 20 kHz. Prefer 96 kHz PCM when both devices support it; allow 48 kHz only after validation because it leaves a narrow band below the 24 kHz Nyquist limit.

At startup and whenever the audio device changes:

1. enumerate capture and playback devices;
2. reject low-rate devices such as a 16 kHz microphone path;
3. play a low-level test waveform and measure loopback SNR;
4. measure energy leaking into the audible band and detect onset/offset clicks;
5. record the supported band and hardware profile;
6. expose `AUDIO_UNSUPPORTED` or `AUDIBLE_LEAKAGE` instead of silently degrading.

Use Hann or an equivalent taper at waveform boundaries. Raw audio remains in memory only for the short analysis window and is discarded after feature extraction.

## 8. Signal Processing

For each recording:

1. remove DC and apply the validated band-pass filter;
2. apply matched filtering/cross-correlation with the known chirp;
3. identify the first physically plausible direct-path peak;
4. reject ambiguous peaks and late multipath reflections;
5. derive two-way time-of-flight from both devices' sample positions;
6. convert time-of-flight using the configured ambient temperature, never CPU temperature;
7. apply the per-device-pair calibration offset;
8. reject impossible jumps;
9. use a rolling median of the five most recent accepted measurements.

## 9. Wi-Fi Fingerprint Fallback

Fingerprinting must use fresh, Server-observed samples from a real Wi-Fi Direct link where possible. Cached NetworkManager scan percentages are insufficient for centimetre claims.

The fingerprint model produces ranges such as `0.5-1 m`, `1-2 m`, `2-3 m`, or `>3 m`. Features may include rolling median RSSI, median absolute deviation, percentiles, signal trend, band/channel, jitter, and packet loss. RTT and packet statistics may affect confidence but are not converted into metres.

The fingerprint database is scoped by device pair and environment. Training data must cover multiple laptop orientations and multiple collection sessions.

## 10. Confidence and Fusion Rules

The per-session acoustic confidence score is composed of:

- chirp SNR: 30%;
- correlation-peak sharpness and ambiguity: 25%;
- two-way agreement: 20%;
- rolling consistency across five accepted samples: 15%;
- hardware and audible-leakage status: 10%.

Fusion behavior:

- acoustic confidence `>= 90`: publish centimetre distance;
- acoustic confidence `< 90`: retry and temporarily retain only a recent accepted result;
- repeated acoustic failure: publish the Wi-Fi fingerprint range;
- strong acoustic/Wi-Fi disagreement: publish `VERIFYING`, not a fabricated distance;
- stale measurements: publish `UNAVAILABLE`.

The dashboard displays distance, confidence, primary source, Wi-Fi fallback range, sample age, and link state separately.

## 11. Errors and Privacy

Typed states:

- `ACOUSTIC_READY`
- `AUDIO_UNSUPPORTED`
- `LOW_SNR`
- `MULTIPATH_AMBIGUOUS`
- `AUDIBLE_LEAKAGE`
- `RANGING_TIMEOUT`
- `WIFI_FALLBACK`
- `VERIFYING`
- `UNAVAILABLE`

Raw microphone audio is not transmitted or persisted. Only detected indices and quality statistics cross the network. Audio buffers are short-lived and cleared after processing. The UI must disclose microphone use and allow acoustic ranging to be disabled.

## 12. Runtime Configuration (No Hardcoded Ranging Parameters)

All tunable ranging values come from one validated `acoustic-ranging.properties` configuration. This includes enablement, audio-device selectors, sample rate, chirp start/end frequencies, chirp and capture durations, response delay, measurement interval, retry count, confidence/SNR/peak thresholds, audible-leakage limit, rolling-window size, speed-of-sound inputs, maximum distance, and calibration offsets. A packaged properties template provides documented starting values; deployments may override it with a file selected by `ltm.acoustic.config` without recompiling either application.

The configuration loader rejects missing, malformed, and inconsistent values before audio starts. Cross-field validation includes `chirpStartHz > audibleCeilingHz`, `chirpEndHz < sampleRateHz / 2`, increasing chirp bounds, confidence within `[0, 100]`, positive timeouts and distances, and a capture window large enough for the configured maximum distance. Algorithms receive this immutable configuration through constructors; they do not contain duplicated numeric thresholds or timing constants.

## 13. Calibration and Validation

Calibration is performed per Server/Client audio-device pair and includes fixed offset plus an ambient-temperature setting. Validation data must be collected separately from calibration data.

Ground-truth points:

- 0.5 m
- 1 m
- 2 m
- 3 m
- 5 m, only if the hardware capability test supports this range

Collect multiple orientations and sessions at each point. Split datasets by recording session or date, not by randomly splitting adjacent samples, to prevent leakage.

Acceptance requires all of the following:

- at least 90% of held-out acoustic measurements have absolute error `<= 5 cm`;
- no accepted result has confidence below 90;
- no audible-band leakage exceeds the configured threshold;
- at least one valid dashboard update per second for one Client;
- multiple Clients never emit overlapping chirps;
- unsupported hardware reliably falls back to Wi-Fi ranges;
- disconnects, timeouts, stale samples, and ambiguous multipath never produce a precise distance.

If the physical laptops fail any hardware or accuracy gate, the product must report the failure honestly. Achieving the target may require an external ultrasonic transducer even though the preferred implementation first attempts to use built-in hardware.

## 14. Test Strategy

### Unit tests

- waveform generation and tapering;
- cross-correlation peak detection;
- synthetic delay-to-distance conversion;
- noise, echo, clipping, and ambiguous-peak rejection;
- confidence scoring boundaries;
- fusion and stale-data state transitions;
- protocol payload validation.

### Integration tests

- Server/Agent ranging session over loopback TCP;
- virtual audio devices with injected delays;
- timeout, retry, disconnect, and scheduling behavior;
- multiple Client slot isolation;
- proof that TCP delay does not change the acoustic result;
- proof that raw audio is neither transmitted nor persisted.

### Hardware acceptance

- frequency-response and audible-leakage sweep on both laptops;
- ground-truth measurements at the declared operating distances;
- orientation, noise, movement, and multipath cases;
- held-out accuracy report containing pass rate, MAE, RMSE, and percentile errors.
