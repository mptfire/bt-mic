#!/usr/bin/env python3
"""Generate the cue PCM files (16 kHz mono PCM16, res/raw).

Pitfall 9: the first fraction of a second of a cue is clipped while the LE
link changes modes, so the GO cue carries 2 s of leading silence. The GO cue
is a short rising two-tone "go ahead"; the FALLBACK cue is a distinct short
falling tone played over the phone mic (recipe 8: tones only on the phone).
"""
import math, struct, pathlib

RATE = 16000

def silence(sec): return [0.0] * int(sec * RATE)

def beep(f0, f1, sec, vol=0.5):
    n = int(sec * RATE)
    return [vol * math.sin(2 * math.pi * (f0 + (f1 - f0) * i / n) * i / RATE)
            for i in range(n)]

def fade(x, ms=10):
    k = int(ms / 1000 * RATE)
    for i in range(k):
        x[i] *= i / k
        x[-1 - i] *= i / k
    return x

out = pathlib.Path(__file__).resolve().parent.parent / \
    "app/src/main/res/raw"
out.mkdir(parents=True, exist_ok=True)

go = silence(2.0) + fade(beep(880, 1320, 0.18)) + silence(0.05) + \
    fade(beep(1320, 1760, 0.18))
fb = fade(beep(1000, 600, 0.30))

for name, samples in (("cue_go.pcm", go), ("cue_fallback.pcm", fb)):
    with open(out / name, "wb") as f:
        f.write(b"".join(struct.pack("<h", max(-32767, min(32767, int(s * 32767))))
                         for s in samples))
    print(name, len(samples), "samples", len(samples) / RATE, "s")
