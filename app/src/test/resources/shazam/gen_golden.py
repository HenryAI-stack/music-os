import sys, numpy as np
sys.path.insert(0, sys.argv[1])
from shz.algorithm import SignatureGenerator

sr = 16000; dur = 6.0; n = int(sr*dur)
rng = np.random.default_rng(42)
t = np.arange(n)/sr
sig = np.zeros(n)
step = int(0.2*sr)
scale = [220.0*2**(s/12) for s in [0,2,4,5,7,9,11,12,14,16,17,19,21,24,26,28]]
for start in range(0, n, step):
    end = min(n, start+step); tt = t[start:end]-t[start]
    env = np.exp(-tt*6)
    for voice in range(3):
        f = scale[rng.integers(len(scale))] * (1, 2, 4)[voice]
        for h, a in ((1, 1.0), (2, 0.5), (3, 0.25)):
            sig[start:end] += a*env*np.sin(2*np.pi*f*h*tt + rng.random()*6.28)
sig += rng.normal(0, 0.05, n)
pcm = np.clip(sig/np.max(np.abs(sig))*20000, -32768, 32767).astype('<i2')
pcm.tofile(sys.argv[2])

g = SignatureGenerator(); g.MAX_TIME_SECONDS = 12
g.feed_input(pcm.tolist())
m = g.get_next_signature()
open(sys.argv[3], 'wb').write(m.encode_to_binary())
print("samples", m.number_samples, {int(k): len(v) for k, v in m.frequency_band_to_sound_peaks.items()}, "bytes", len(m.encode_to_binary()))
