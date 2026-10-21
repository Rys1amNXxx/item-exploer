"""Deterministic Item Explorer sound sketches. All sources are synthesized.

Run with Python + NumPy + soundfile. No recordings or third-party audio samples.
Outputs mono PCM WAV previews and Ogg Vorbis resources for the mod.
"""

import json
import math
from pathlib import Path
import wave

import numpy as np
import soundfile as sf

SR = 48_000
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'work' / 'audio-preview' / 'v1'
RESOURCES = ROOT / 'src' / 'main' / 'resources' / 'assets' / 'itemexplorer' / 'sounds'
OUT.mkdir(parents=True, exist_ok=True)
RESOURCES.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(2044)


def timeline(seconds):
    return np.arange(round(seconds * SR), dtype=np.float64) / SR


def envelope(t, attack=0.004, decay=0.08, release=0.018):
    ramp = np.sin(np.minimum(t / attack, 1.0) * math.pi / 2) ** 2
    tail = np.minimum((len(t) / SR - t) / release, 1.0).clip(0, 1)
    return ramp * np.exp(-t / decay) * np.sin(tail * math.pi / 2) ** 2


def tone(seconds, frequency, amplitude=1.0, decay=0.08, end=None, warmth=0.14):
    t = timeline(seconds)
    end = frequency if end is None else end
    freq = frequency + (end - frequency) * np.minimum(t / seconds, 1)
    phase = 2 * math.pi * np.cumsum(freq) / SR
    # Slight FM at the beginning supplies an electronic edge without a sharp beep.
    phase += 0.10 * np.sin(phase * 2) * np.exp(-t / 0.018)
    carrier = np.sin(phase) + warmth * np.sin(2 * phase) + 0.035 * np.sin(3 * phase)
    return amplitude * carrier * envelope(t, decay=decay)


def noise(seconds, low=180, high=3200):
    t = timeline(seconds)
    raw = RNG.normal(size=len(t))
    spectrum = np.fft.rfft(raw)
    f = np.fft.rfftfreq(len(raw), 1 / SR)
    band = (1 - np.exp(-(f / low) ** 4)) * np.exp(-(f / high) ** 4)
    shaped = np.fft.irfft(spectrum * band, n=len(raw))
    return shaped / max(np.sqrt(np.mean(shaped * shaped)), 1e-9)


def mechanical_click(seconds=0.11, pitch=430, amplitude=1.0):
    t = timeline(seconds)
    body = sum(weight * np.sin(2 * math.pi * pitch * ratio * t)
               * np.exp(-t / duration)
               for ratio, weight, duration in [(1, 0.65, .024), (2.71, .22, .012), (4.13, .09, .008)])
    transient = .24 * noise(seconds, 420, 4500) * np.exp(-t / .006)
    return amplitude * (body + transient) * envelope(t, attack=.0007, decay=.14, release=.012)


def mix(seconds, *events):
    result = np.zeros(round(seconds * SR), dtype=np.float64)
    for start, sound in events:
        i = round(start * SR)
        count = min(len(sound), len(result) - i)
        if count > 0:
            result[i:i + count] += sound[:count]
    return result


def finish(samples, peak_db):
    # Remove DC and give each standalone file exact silent boundaries.
    samples = np.asarray(samples, dtype=np.float64).copy()
    samples -= samples.mean()
    fade = min(round(.012 * SR), len(samples) // 4)
    samples[:48] *= np.linspace(0, 1, 48)
    samples[-fade:] *= np.linspace(1, 0, fade) ** 2
    peak = float(np.max(np.abs(samples)))
    samples *= 10 ** (peak_db / 20) / max(peak, 1e-9)
    return np.concatenate([np.zeros(480), samples, np.zeros(1440)])


def save(name, samples):
    path = OUT / (name + '.wav')
    pcm = np.rint(np.clip(samples, -1, 1) * 32767).astype('<i2')
    with wave.open(str(path), 'wb') as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes(pcm.tobytes())
    # Validate the written file rather than just the synthesis buffer.
    with wave.open(str(path), 'rb') as handle:
        decoded = np.frombuffer(handle.readframes(handle.getnframes()), dtype='<i2').astype(float) / 32768
        assert handle.getnchannels() == 1 and handle.getframerate() == SR
    assert np.isfinite(decoded).all() and np.max(np.abs(decoded)) < .9
    assert decoded[0] == 0 and decoded[-1] == 0
    peak = float(np.max(np.abs(decoded)))
    rms = float(np.sqrt(np.mean(decoded ** 2)))
    return {'file': path.name, 'duration_seconds': round(len(decoded) / SR, 3),
            'peak_dbfs': round(20 * math.log10(max(peak, 1e-12)), 2),
            'rms_dbfs': round(20 * math.log10(max(rms, 1e-12)), 2)}


slide_t = timeline(.12)
slide = .14 * noise(.12, 250, 1900) * np.sin(math.pi * slide_t / .12) ** 2
insert = mix(.30, (.0, slide), (.080, mechanical_click(.12, 410, .78)),
             (.135, mechanical_click(.11, 620, .42)))

release_t = timeline(.14)
release = .09 * noise(.14, 160, 1400) * np.sin(math.pi * release_t / .14) ** 2
eject = mix(.33, (.0, mechanical_click(.12, 570, .62)), (.06, release),
            (.115, mechanical_click(.12, 340, .36)))

transfer = mix(.29, (.0, tone(.15, 660, .65, .050, warmth=.10)),
               (.064, tone(.21, 990, .52, .073, warmth=.08)))

denied = mix(.34, (.0, tone(.16, 370, .50, .065, warmth=.20)),
             (.12, tone(.20, 294, .44, .080, warmth=.20)))

boot_t = timeline(.56)
boot_sweep = tone(.56, 140, .16, .38, end=330, warmth=.20)
boot_sweep *= np.sin(math.pi * boot_t / .56) ** 2
startup = mix(1.08, (.0, mechanical_click(.12, 280, .30)), (.02, boot_sweep),
              (.22, tone(.38, 392, .42, .15, warmth=.08)),
              (.36, tone(.42, 588, .32, .18, warmth=.07)),
              (.51, tone(.52, 784, .28, .21, warmth=.06)))

complete = mix(.69, (.0, tone(.27, 392, .48, .10, warmth=.08)),
               (.12, tone(.28, 494, .43, .11, warmth=.07)),
               (.24, tone(.43, 588, .42, .15, warmth=.06)))

specs = [
    ('01_disk_insert', '硬盘安装', insert, -10),
    ('02_disk_eject', '硬盘弹出', eject, -11),
    ('03_transfer_success', '传输成功', transfer, -13),
    ('04_action_denied', '操作失败', denied, -14),
    ('05_station_startup', '基站成型', startup, -11),
    ('06_program_complete', '程序完成', complete, -13),
]

manifest = {'format': '48 kHz, mono, 16-bit PCM WAV',
            'source': 'Procedural synthesis only; no recordings or sampled assets.',
            'sounds': []}
sequence = [np.zeros(round(.4 * SR))]
cursor = .4
for name, label, raw, peak_db in specs:
    sound = finish(raw, peak_db)
    item = save(name, sound)
    resource = RESOURCES / (name[3:] + '.ogg')
    sf.write(resource, sound, SR, format='OGG', subtype='VORBIS')
    decoded, rate = sf.read(resource)
    assert rate == SR and decoded.ndim == 1 and len(decoded) == len(sound)
    assert np.isfinite(decoded).all() and np.max(np.abs(decoded)) < .9
    item['resource'] = resource.relative_to(ROOT).as_posix()
    item.update(label=label, preview_start_seconds=round(cursor, 3))
    manifest['sounds'].append(item)
    sequence.extend([sound, np.zeros(round(.7 * SR))])
    cursor += len(sound) / SR + .7
manifest['sequence'] = save('itemexplorer_sfx_preview', np.concatenate(sequence))
(OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(manifest, ensure_ascii=True, indent=2))
