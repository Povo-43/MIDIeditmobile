#!/usr/bin/env python3
"""Generate the bundled CC0 piano SoundFont used by Piano Canvas.

The samples are synthesised from scratch with an inharmonic additive piano
model (hammer noise, velocity-dependent brightness, two-stage decay and a
seamless low-level sustain loop).  No third-party sample data is used, so the
resulting piano.sf2 can be shipped with the app under CC0.

Usage: python3 tools/make_piano_sf2.py app/src/main/assets/piano.sf2
"""
from __future__ import annotations

import math
import struct
import sys
from pathlib import Path

import numpy as np

SAMPLE_RATE = 32000
DURATION = 3.5
LOOP_START = 2.9
LOOP_LENGTH = 0.5
ROOTS = [21, 29, 37, 45, 53, 61, 69, 77, 85, 93, 101]
# (render velocity, velocity low, velocity high)
LAYERS = [(35, 1, 55), (78, 56, 95), (112, 96, 127)]
PEAK = 0.72
TT = np.arange(int(DURATION * SAMPLE_RATE), dtype=np.float64) / SAMPLE_RATE
LOOP_START_SAMPLE = int(LOOP_START * SAMPLE_RATE)
LOOP_SAMPLES = int(LOOP_LENGTH * SAMPLE_RATE)


def midi_hz(pitch: int) -> float:
    return 440.0 * (2.0 ** ((pitch - 69) / 12.0))


def render_note(pitch: int, velocity: int) -> np.ndarray:
    f0 = midi_hz(pitch)
    rng = np.random.default_rng(1000 + pitch * 17 + velocity)
    hard = (velocity - 20) / 107.0
    n_partials = max(12, min(44, int(14500 / f0)))
    inharm = 0.00018 + 0.00055 * max(0.0, (60 - pitch) / 48.0)
    strike = 0.118
    brightness = 0.115 - 0.065 * hard
    low_boost = 1.0 + 0.25 * max(0.0, (60 - pitch) / 48.0)

    natural = np.zeros_like(TT)
    detunes = [0.0] if pitch > 72 else [-0.00055, 0.00055]
    detune_scale = 1.0 if len(detunes) == 1 else 0.62

    partial_info = []
    for n in range(1, n_partials + 1):
        fn = f0 * n * math.sqrt(1.0 + inharm * n * n)
        if fn > SAMPLE_RATE * 0.46:
            break
        amp = low_boost * (1.0 / (n ** 1.12))
        amp *= math.exp(-brightness * (n - 1))
        amp *= abs(math.sin(math.pi * n * strike)) + 0.075
        if n == 1:
            amp *= 1.15
        tau = (4.4 if pitch < 45 else 3.2 if pitch < 69 else 1.9)
        tau /= (1.0 + 0.11 * (n - 1) + fn / 5200.0)
        decay = np.exp(-TT / tau)
        decay *= 0.82 + 0.18 * np.exp(-TT / 0.07)
        for det in detunes:
            freq = fn * (1.0 + det)
            phase = rng.uniform(0, 2 * math.pi)
            natural += (amp * detune_scale) * decay * np.sin(
                2 * math.pi * freq * TT + phase
            )
            decay_at_loop = math.exp(-LOOP_START / tau) * (
                0.82 + 0.18 * math.exp(-LOOP_START / 0.07)
            )
            partial_info.append((freq, amp * detune_scale * decay_at_loop))

    # Hammer noise: short filtered burst, stronger for loud notes.
    noise = rng.standard_normal(len(TT))
    alpha = 0.22 + 0.2 * hard
    filt = np.zeros_like(noise)
    acc = 0.0
    burst = min(len(noise), int(0.09 * SAMPLE_RATE))
    for i in range(burst):
        acc += alpha * (noise[i] - acc)
        filt[i] = acc
    natural += filt * np.exp(-TT / 0.012) * (0.055 + 0.11 * hard)
    # Low thump from the soundboard.
    natural += 0.055 * (0.4 + hard) * np.exp(-TT / 0.045) * np.sin(
        2 * math.pi * f0 * 0.5 * TT
    )
    natural /= max(1e-9, np.max(np.abs(natural)))
    natural *= 0.78

    # Build a perfectly periodic sustain tail.  All partial frequencies are
    # quantised to an integer number of cycles in the loop, so wrapping from
    # loop end to loop start is click-free.
    t_loop = np.arange(LOOP_SAMPLES, dtype=np.float64) / SAMPLE_RATE
    loop = np.zeros(LOOP_SAMPLES)
    for freq, amp in partial_info:
        if amp <= 1e-7:
            continue
        cycles = max(1, round(freq * LOOP_LENGTH))
        qfreq = cycles / LOOP_LENGTH
        if qfreq >= SAMPLE_RATE * 0.46:
            continue
        loop += amp * np.sin(2 * math.pi * qfreq * t_loop)
    if np.max(np.abs(loop)) < 1e-7:
        loop[:] = 0.0
    else:
        # Match the loop's energy to the natural decay at the loop point so
        # held notes do not swell when the loop is reached.
        window = natural[max(0, LOOP_START_SAMPLE - 4000):LOOP_START_SAMPLE]
        target_rms = float(np.sqrt(np.mean(window * window))) if len(window) else 0.0
        loop_rms = float(np.sqrt(np.mean(loop * loop)))
        if loop_rms > 1e-9 and target_rms > 1e-9:
            loop *= target_rms / loop_rms

    out = natural.copy()
    xf = int(0.30 * SAMPLE_RATE)
    xf_start = max(0, LOOP_START_SAMPLE - xf)
    xf = LOOP_START_SAMPLE - xf_start
    ramp = np.linspace(0.0, 1.0, xf, endpoint=False)
    out[xf_start:LOOP_START_SAMPLE] = (
        out[xf_start:LOOP_START_SAMPLE] * (1.0 - ramp) + loop[:xf] * ramp
    )
    out[LOOP_START_SAMPLE:LOOP_START_SAMPLE + LOOP_SAMPLES] = loop
    # Leave a short silent tail after the loop (some players dislike an
    # end-loop exactly at sample end).
    tail_start = LOOP_START_SAMPLE + LOOP_SAMPLES
    tail_len = len(out) - tail_start
    if tail_len > 0:
        out[tail_start:] = loop[:tail_len] * np.linspace(1.0, 0.0, tail_len)

    fade = int(0.002 * SAMPLE_RATE)
    out[:fade] *= np.linspace(0.0, 1.0, fade)
    peak = np.max(np.abs(out))
    if peak > 0:
        out *= PEAK / peak
    return out


def write_sf2(path: Path) -> None:
    sample_meta = []
    smpl_parts = [np.zeros(46, dtype="<i2")]
    cursor = 46
    sample_index = 0
    for pitch in ROOTS:
        idx = ROOTS.index(pitch)
        key_lo = 21 if idx == 0 else (ROOTS[idx - 1] + pitch) // 2 + 1
        key_hi = 108 if idx == len(ROOTS) - 1 else (pitch + ROOTS[idx + 1]) // 2
        for vel, vel_lo, vel_hi in LAYERS:
            audio = render_note(pitch, vel)
            pcm = np.clip(np.round(audio * 32767.0), -32768, 32767).astype("<i2")
            start = cursor
            cursor += len(pcm)
            loop_start = start + LOOP_START_SAMPLE
            loop_end = loop_start + LOOP_SAMPLES
            smpl_parts.append(pcm)
            sample_meta.append(
                dict(
                    name=f"Piano {pitch} v{vel}",
                    index=sample_index,
                    start=start,
                    end=cursor,
                    loop_start=loop_start,
                    loop_end=loop_end,
                    pitch=pitch,
                    key_lo=key_lo,
                    key_hi=key_hi,
                    vel_lo=vel_lo,
                    vel_hi=vel_hi,
                )
            )
            sample_index += 1

    smpl = np.concatenate(smpl_parts).tobytes()

    # --- shdr ------------------------------------------------------------
    shdr = b""
    for m in sample_meta:
        name = m["name"].encode("ascii")[:19].ljust(20, b"\0")
        shdr += struct.pack(
            "<20sIIIIIBbHH",
            name,
            m["start"],
            m["end"],
            m["loop_start"],
            m["loop_end"],
            SAMPLE_RATE,
            m["pitch"],
            0,
            0,
            1,
        )
    shdr += struct.pack("<20sIIIIIBbHH", b"EOS", 0, 0, 0, 0, 0, 0, 0, 0, 0)

    # --- igen / ibag -----------------------------------------------------
    igen = b""
    ibag = b""
    zone_gen_start = 0
    for m in sample_meta:
        generators = [
            (43, m["key_lo"] | (m["key_hi"] << 8)),
            (44, m["vel_lo"] | (m["vel_hi"] << 8)),
            (58, m["pitch"]),          # overridingRootKey
            (54, 1),                   # sampleModes: loop continuously
            (48, 0),                   # initialAttenuation (cB)
            (17, 0),                   # pan
            (34, -7000),               # attackVolEnv
            (35, -12000),              # holdVolEnv
            (36, -12000),              # decayVolEnv
            (37, 0),                   # sustainVolEnv (full)
            (38, -3600),               # releaseVolEnv
            # sampleID MUST be the last generator of an instrument zone.
            (53, m["index"]),
        ]
        for oper, amount in generators:
            igen += struct.pack("<Hh", oper, amount)
        ibag += struct.pack("<HH", zone_gen_start, 0)
        zone_gen_start += len(generators)
    ibag += struct.pack("<HH", zone_gen_start, 0)  # terminal ibag

    # --- inst ------------------------------------------------------------
    inst = struct.pack("<20sH", b"Piano", 0)
    inst += struct.pack("<20sH", b"EOI", len(sample_meta))

    # --- preset ----------------------------------------------------------
    pgen = struct.pack("<Hh", 41, 0)  # instrument 0
    pbag = struct.pack("<HH", 0, 0) + struct.pack("<HH", 1, 0)  # zone + terminal
    pmod = b"\0" * 10
    phdr = struct.pack("<20sHHHIII", b"Piano Canvas", 0, 0, 0, 0, 0, 0)
    phdr += struct.pack("<20sHHHIII", b"EOP", 0, 0, 1, 0, 0, 0)

    def chunk(cid: bytes, data: bytes) -> bytes:
        out = cid + struct.pack("<I", len(data)) + data
        if len(data) % 2:
            out += b"\0"
        return out

    def list_chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return b"LIST" + struct.pack("<I", len(body)) + body

    info = list_chunk(
        b"INFO",
        chunk(b"ifil", struct.pack("<HH", 2, 4))
        + chunk(b"isng", b"EMU8000\0")
        + chunk(b"INAM", b"Piano Canvas\0")
        + chunk(b"ICOP", b"CC0 generated by Piano Canvas\0"),
    )
    sdta = list_chunk(b"sdta", chunk(b"smpl", smpl))
    pdta = list_chunk(
        b"pdta",
        chunk(b"phdr", phdr)
        + chunk(b"pbag", pbag)
        + chunk(b"pmod", pmod)
        + chunk(b"pgen", pgen)
        + chunk(b"inst", inst)
        + chunk(b"ibag", ibag)
        + chunk(b"imod", b"\0" * 10)
        + chunk(b"igen", igen)
        + chunk(b"shdr", shdr),
    )
    body = b"sfbk" + info + sdta + pdta
    riff = b"RIFF" + struct.pack("<I", len(body)) + body
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(riff)
    print(f"wrote {path} ({len(riff)/1024/1024:.1f} MiB, {len(sample_meta)} samples)")


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/piano.sf2")
    write_sf2(out)
