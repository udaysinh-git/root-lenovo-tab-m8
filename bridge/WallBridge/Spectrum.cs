using NAudio.Dsp;
using NAudio.Wave;

namespace WallBridge;

/// <summary>
/// Loopback spectrum of whatever the laptop is playing (WASAPI loopback: no microphone involved).
/// Capture runs only while at least one client is subscribed.
/// Output: 32 log-spaced bands (40 Hz .. 16 kHz), one byte each (0..255), ~30 frames/s.
/// </summary>
sealed class Spectrum
{
    public const int Bands = 32;
    const int FftSize = 2048, FftM = 11;
    const float LowHz = 40f, HighHz = 16000f;

    readonly object _lock = new();
    readonly float[] _ring = new float[FftSize];
    int _ringPos;
    int _sampleRate = 48000;
    WasapiLoopbackCapture _capture;
    int _subscribers;
    readonly float[] _agcPeak = { 1e-3f };

    public void Subscribe()
    {
        lock (_lock)
        {
            if (_subscribers++ > 0) return;
            _capture = new WasapiLoopbackCapture();
            _sampleRate = _capture.WaveFormat.SampleRate;
            _capture.DataAvailable += OnData;
            _capture.StartRecording();
        }
    }

    public void Unsubscribe()
    {
        lock (_lock)
        {
            if (--_subscribers > 0 || _capture == null) return;
            try { _capture.StopRecording(); _capture.Dispose(); } catch { }
            _capture = null;
            Array.Clear(_ring);
        }
    }

    void OnData(object sender, WaveInEventArgs e)
    {
        var fmt = ((WasapiLoopbackCapture)sender).WaveFormat;
        int ch = fmt.Channels;
        if (fmt.Encoding != WaveFormatEncoding.IeeeFloat && fmt.Encoding != WaveFormatEncoding.Extensible) return;
        int frames = e.BytesRecorded / (4 * ch);
        lock (_ring)
        {
            for (int i = 0; i < frames; i++)
            {
                float m = 0;
                for (int c = 0; c < ch; c++) m += BitConverter.ToSingle(e.Buffer, (i * ch + c) * 4);
                _ring[_ringPos] = m / ch;
                _ringPos = (_ringPos + 1) % FftSize;
            }
        }
    }

    /// <summary>Compute one frame of band levels from the most recent audio.</summary>
    public byte[] Frame()
    {
        var buf = new Complex[FftSize];
        lock (_ring)
        {
            for (int i = 0; i < FftSize; i++)
            {
                float s = _ring[(_ringPos + i) % FftSize];
                buf[i].X = s * (float)FastFourierTransform.HannWindow(i, FftSize);
                buf[i].Y = 0;
            }
        }
        FastFourierTransform.FFT(true, FftM, buf);

        var bands = new float[Bands];
        float binHz = (float)_sampleRate / FftSize;
        for (int b = 0; b < Bands; b++)
        {
            float lo = LowHz * MathF.Pow(HighHz / LowHz, (float)b / Bands);
            float hi = LowHz * MathF.Pow(HighHz / LowHz, (float)(b + 1) / Bands);
            int i0 = Math.Max(1, (int)(lo / binHz)), i1 = Math.Max(i0 + 1, (int)(hi / binHz));
            float sum = 0;
            for (int i = i0; i < i1 && i < FftSize / 2; i++)
                sum = MathF.Max(sum, MathF.Sqrt(buf[i].X * buf[i].X + buf[i].Y * buf[i].Y));
            // gentle rising tilt: music rolls off steeply toward the top
            bands[b] = sum * (1f + 2.5f * b / Bands);
        }

        // Slow AGC so quiet tracks still move and loud ones don't pin every bar.
        float peak = bands.Max();
        _agcPeak[0] = peak > _agcPeak[0] ? peak : Math.Max(1e-4f, _agcPeak[0] * 0.995f);
        var outp = new byte[Bands];
        for (int b = 0; b < Bands; b++)
        {
            float v = bands[b] / _agcPeak[0];
            v = MathF.Sqrt(Math.Clamp(v, 0f, 1f));       // perceptual lift
            outp[b] = (byte)(v * 255f);
        }
        return outp;
    }
}
