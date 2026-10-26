#include "audio_time_stretcher.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <limits>
#include <cstdlib>
#include <new>
#if defined(__aarch64__) && defined(__clang__)
#include <arm_neon.h>
#endif

namespace {
constexpr double pi = 3.14159265358979323846;
constexpr size_t inputCapacity = 16384;
constexpr size_t overlapCapacity = 4096;
constexpr int maximumWindow = 2048;
using Complex = std::complex<double>;
double principal(double phase) {
    // The represented divisor has nearest quotient -1, 0 or 1 here.
    // Sterbenz subtraction is exact; preserve tie endpoints and signed zero.
    if (std::abs(phase) <= 2 * pi) {
        if (phase > pi) return phase == 2 * pi ? 0.0 : phase - 2 * pi;
        if (phase < -pi) return phase == -2 * pi ? -0.0 : phase + 2 * pi;
        return phase;
    }
#if defined(__aarch64__) || !defined(__ANDROID__)
    // AArch64 has hardware FMA. Keep software-FMA Android targets on libm.
    // In this bounded range the rounded quotient can miss by at most one.
    // FMA retains the exact residual instead of rounding quotient * divisor
    // before subtraction; correct a boundary miss and retain ties-to-even.
    // Match remainder's return value, not its floating-point exception flags:
    // the quotient division can raise FE_INEXACT.
    if (std::abs(phase) <= 0x1p20) {
        const double quotient = std::nearbyint(phase / (2 * pi));
        double residual = std::fma(-quotient, 2 * pi, phase);
        if (std::abs(residual) > pi ||
            (std::abs(residual) == pi && static_cast<int64_t>(quotient) % 2 != 0))
            residual -= std::copysign(2 * pi, residual);
        return residual == 0 ? std::copysign(0.0, phase) : residual;
    }
#endif
    return std::remainder(phase, 2 * pi);
}
bool quantize(double sample, int16_t& output) {
    if (!std::isfinite(sample)) return false;
    output = static_cast<int16_t>(std::lrint(std::clamp(sample, -32768.0, 32767.0)));
    return true;
}

template<class T, class... Args> T* allocate(Args... args) {
    void* storage = std::malloc(sizeof(T));
    return storage ? ::new (storage) T(args...) : nullptr;
}
template<class T> void release(T* object) {
    if (!object) return;
    object->~T();
    std::free(object);
}

// Duration rounding assumes IEEE binary64 round-to-nearest arithmetic (no
// reassociation/fast-math). Counts <= 2^48 are exact and keep nonzero division
// residuals normal; the phase timeline also retains distinct source boundaries.
constexpr int64_t maximumSourceFrames = int64_t{1} << 48;
static_assert(std::numeric_limits<double>::is_iec559 && std::numeric_limits<double>::digits == 53,
              "Time-stretch duration accounting requires IEEE binary64");
struct DurationSum { double hi, lo; };
DurationSum twoSum(double a, double b) {
    const double hi = a + b;
    const double bPart = hi - a;
    return {hi, (a - (hi - bPart)) + (b - bPart)};
}
double upperSum(double a, double b) {
    if (a == 0) return b;
    if (b == 0) return a;
    return std::nextafter(a + b, std::numeric_limits<double>::infinity());
}
double halfUlp(double value) {
    if (value == 0) return 0;
    const double magnitude = std::abs(value);
    return (std::nextafter(magnitude, std::numeric_limits<double>::infinity()) - magnitude) * 0.5;
}
struct DurationInterval {
    // The exact source-duration sum is enclosed by hi + lo +/- radius.
    double hi = 0, lo = 0, radius = 0;
};
DurationInterval runDuration(int64_t frames, double tempo) {
    const double count = static_cast<double>(frames);
    const double quotient = count / tempo;
    const double residual = std::fma(-quotient, tempo, count);
    if (residual == 0) return {quotient, 0, 0};
    const double correction = residual / tempo;
    const auto sum = twoSum(quotient, correction);
    // The FMA residual and its division are rounded independently. Bound both,
    // rounding the positive bound outwards; an exact zero residual needs no bound.
    const double residualBound =
        std::nextafter(halfUlp(residual) / tempo, std::numeric_limits<double>::infinity());
    return {sum.hi, sum.lo, upperSum(residualBound, halfUlp(correction))};
}
DurationInterval addDuration(const DurationInterval& a, const DurationInterval& b) {
    if (a.hi == 0 && a.lo == 0 && a.radius == 0) return b;
    const auto s = twoSum(a.hi, b.hi);
    const auto t = twoSum(a.lo, b.lo);
    const auto u = twoSum(s.lo, t.hi);
    const auto v = twoSum(s.hi, u.hi);
    const auto w = twoSum(v.lo, u.lo);
    const auto x = twoSum(w.hi, t.lo);
    const auto result = twoSum(v.hi, x.hi);
    // Exact represented input sum = result.hi + result.lo + w.lo + x.lo.
    const double radius = upperSum(upperSum(upperSum(a.radius, b.radius), std::abs(w.lo)),
                                   std::abs(x.lo));
    return {result.hi, result.lo, radius};
}
int64_t roundedDuration(const DurationInterval& duration) {
    if (duration.hi == 0) return 0;
    const double base = std::floor(duration.hi);
    // The subtraction is exact in this count range (Sterbenz); subsequent sums
    // round upwards. A certified below-half interval rounds down. Otherwise,
    // including an interval overlapping the half, the explicit policy is half-up.
    const double upper = upperSum(upperSum(duration.hi - (base + 0.5), duration.lo), duration.radius);
    return static_cast<int64_t>(base) + (upper < 0 ? 0 : 1);
}
}

// Independently implemented from the equations cited in the native test README.
// All storage is acquired at construction. receive() performs bounded-window work
// on demand, so a slow consumer cannot create an unbounded synthesized-output queue.
struct AudioTimeStretcher::Impl {
    Engine engine;
    int windowSize, hop, search;
    std::array<std::array<int16_t, 2>, inputCapacity> source{};
    std::array<double, inputCapacity> sourceEndTime{};
    std::array<std::array<double, 2>, overlapCapacity> overlap{};
    std::array<double, overlapCapacity> weight{};
    std::array<double, maximumWindow> window{};
    std::array<std::array<Complex, maximumWindow>, 2> spectrum{};
    std::array<Complex, maximumWindow / 2> roots{};
    std::array<std::array<uint16_t, 2>, maximumWindow / 2> swaps{};
    size_t swapCount = 0;
    using SpectrumHistory = std::array<std::array<Complex, maximumWindow / 2 + 1>, 2>;
    SpectrumHistory* previousSpectrum = nullptr;
    std::array<double, maximumWindow / 2 + 1> previousMagnitude{}, magnitude{}, rotation{}, nextRotation{};
    std::array<int, maximumWindow / 2 + 1> peaks{};
    struct Gradient {
        std::array<std::array<Complex, maximumWindow>, 2> future{};
        std::array<std::array<double, maximumWindow / 2 + 1>, 2> futureArgument{};
        std::array<std::array<double, maximumWindow / 2 + 1>, 2> phase{}, previousPhase{}, time{}, previousTime{}, frequency{};
        std::array<bool, maximumWindow / 2 + 1> pending{};
        struct Entry { double magnitude; int bin; bool current; };
        std::array<Entry, maximumWindow / 2 + 1> heap{};
        std::array<uint16_t, maximumWindow / 2 + 1> positions{};
        bool modified = false;
    };
    Gradient* gradient = nullptr;
    mutable const char* failure = nullptr;
    int64_t begin = 0, end = 0, timeCursor = 0;
    int64_t synthesisCenter = 0, emitted = 0, ready = 0;
    int64_t previousCenter = 0, previousPreviousCenter = 0, adjustedCenter = 0;
    double duration = 0, durationError = 0, lastTempo = 1;
    int64_t tempoRunStart = 0;
    DurationInterval completedDuration{}, acceptedDuration{};
    int64_t durationTarget = 0;
    bool final = false, first = true, endpointAdded = false;
    bool spectralPrimed = false;

    explicit Impl(Engine selected) : engine(selected), windowSize(0), hop(0), search(0) {
        switch (engine) {
            case Engine::Wsola: windowSize = 1920; hop = 480; search = 240; break;
            case Engine::PhaseVocoder: windowSize = 2048; hop = 512; break;
            case Engine::PhaseVocoderShort: windowSize = 1024; hop = 256; break;
            case Engine::PhaseGradient:
                windowSize = 2048; hop = 512; gradient = allocate<Gradient>();
                if (!gradient) { failure = "Out of memory creating time stretcher"; return; }
                break;
            default: failure = "Unknown time stretcher"; return;
        }
        if (!search && !gradient) {
            previousSpectrum = allocate<SpectrumHistory>();
            if (!previousSpectrum) { failure = "Out of memory creating time stretcher"; return; }
        }
        for (int i = 0; i < windowSize; ++i)
            window[i] = 0.5 - 0.5 * std::cos(2 * pi * i / windowSize);
        if (!search) {
            unsigned bits = 0;
            while ((1U << bits) < static_cast<unsigned>(windowSize)) ++bits;
            for (int i = 0; i < windowSize; ++i) {
                unsigned value = static_cast<unsigned>(i), reverse = 0;
                for (unsigned b = 0; b < bits; ++b) { reverse = (reverse << 1) | (value & 1); value >>= 1; }
                if (static_cast<unsigned>(i) < reverse)
                    swaps[swapCount++] = {static_cast<uint16_t>(i), static_cast<uint16_t>(reverse)};
            }
            for (int i = 0; i < windowSize / 2; ++i)
                roots[i] = std::polar(1.0, -2 * pi * i / windowSize);
        }
    }
    ~Impl() {
        release(gradient);
        release(previousSpectrum);
    }

    double sample(int64_t position, int channel) const {
        if (position < 0 || position >= end) return 0;
        if (position < begin) {
            failure = "Time-stretch source history exhausted";
            return std::numeric_limits<double>::quiet_NaN();
        }
        return source[static_cast<size_t>(position) % inputCapacity][channel];
    }
    int64_t target() const { return durationTarget; }

    // Invert the piecewise-linear integral of 1/tempo. Tempo belongs to the source
    // frames accepted by process(), not to whichever block happens to unlock FFT
    // lookahead. The last ratio extends only the zero padding after final source.
    double sourcePosition(double time) {
        if (time >= duration) return end + (time - duration) * lastTempo;
        while (timeCursor < end && sourceEndTime[static_cast<size_t>(timeCursor) % inputCapacity] <= time)
            ++timeCursor;
        const double stop = sourceEndTime[static_cast<size_t>(timeCursor) % inputCapacity];
        const double start = timeCursor == 0 ? 0 : sourceEndTime[static_cast<size_t>(timeCursor - 1) % inputCapacity];
        return timeCursor + (time - start) / (stop - start);
    }

    // Forward transforms expose only bins [0, N/2]; inverseStereo rebuilds the negative half.
    template<int Size, bool Inverse>
    void fftKernel(std::array<Complex, maximumWindow>& data) const {
        for (size_t i = 0; i < swapCount; ++i)
            std::swap(data[swaps[i][0]], data[swaps[i][1]]);
        // Keep lengths 2, 4 and 8 local to each independent eight-value
        // block. Actual table roots preserve the original rounding graph.
        const Complex root0 = Inverse ? std::conj(roots[0]) : roots[0];
        const Complex root1 =
            Inverse ? std::conj(roots[Size / 8]) : roots[Size / 8];
        const Complex root2 =
            Inverse ? std::conj(roots[Size / 4]) : roots[Size / 4];
        const Complex root3 =
            Inverse ? std::conj(roots[3 * (Size / 8)]) : roots[3 * (Size / 8)];
        const auto butterfly = [](Complex& a, Complex& b, const Complex& root) {
            const Complex even = a, odd = b * root;
            a = even + odd;
            b = even - odd;
        };
        for (int start = 0; start < Size; start += 8) {
            Complex a = data[start], b = data[start + 1];
            Complex c = data[start + 2], d = data[start + 3];
            Complex e = data[start + 4], f = data[start + 5];
            Complex g = data[start + 6], h = data[start + 7];
            butterfly(a, b, root0);
            butterfly(c, d, root0);
            butterfly(e, f, root0);
            butterfly(g, h, root0);
            butterfly(a, c, root0);
            butterfly(b, d, root2);
            butterfly(e, g, root0);
            butterfly(f, h, root2);
            butterfly(a, e, root0);
            butterfly(b, f, root1);
            butterfly(c, g, root2);
            butterfly(d, h, root3);
            data[start] = a;
            data[start + 1] = b;
            data[start + 2] = c;
            data[start + 3] = d;
            data[start + 4] = e;
            data[start + 5] = f;
            data[start + 6] = g;
            data[start + 7] = h;
        }
#if defined(__aarch64__) && defined(__clang__)
        struct ComplexPair { float64x2_t real, imag; };
        // std::complex<double> arrays expose interleaved double real/imag
        // storage. Deinterleaving loads keep two independent j values in lanes.
        const auto loadPair = [](const Complex* source) -> ComplexPair {
            const float64x2x2_t value = vld2q_f64(reinterpret_cast<const double*>(source));
            return {value.val[0], value.val[1]};
        };
        const auto storePair = [](Complex* target, const ComplexPair& value) {
            const float64x2x2_t interleaved = {{value.real, value.imag}};
            vst2q_f64(reinterpret_cast<double*>(target), interleaved);
        };
        const auto loadRoots = [&](int index, int stride) -> ComplexPair {
            const float64x2_t firstRoot =
                vld1q_f64(reinterpret_cast<const double*>(&roots[index]));
            const float64x2_t secondRoot =
                vld1q_f64(reinterpret_cast<const double*>(&roots[index + stride]));
            const float64x2_t real = vuzp1q_f64(firstRoot, secondRoot);
            const float64x2_t imag = vuzp2q_f64(firstRoot, secondRoot);
            return {real, Inverse ? vnegq_f64(imag) : imag};
        };
        const auto butterflyPair = [](ComplexPair& a, ComplexPair& b, const ComplexPair& root) {
#if defined(__clang__)
#pragma clang fp contract(off)
#endif
            // Four separately rounded products, then the original complex
            // sum/difference graph. Valid engine FFT inputs remain finite.
            const float64x2_t ac = vmulq_f64(b.real, root.real);
            const float64x2_t bd = vmulq_f64(b.imag, root.imag);
            const float64x2_t ad = vmulq_f64(b.real, root.imag);
            const float64x2_t bc = vmulq_f64(b.imag, root.real);
            const ComplexPair odd{vsubq_f64(ac, bd), vaddq_f64(ad, bc)};
            const ComplexPair even = a;
            a = {vaddq_f64(even.real, odd.real), vaddq_f64(even.imag, odd.imag)};
            b = {vsubq_f64(even.real, odd.real), vsubq_f64(even.imag, odd.imag)};
        };
#endif
        int length = 16;
        // Pair consecutive stages without altering either butterfly's
        // arithmetic: two length-L blocks feed one length-2L block.
        for (; length <= Size / 2; length *= 4) {
            const int quarter = length / 2;
            const int stride = Size / length;
            const int pairedStride = Size / (2 * length);
            for (int start = 0; start < Size; start += 2 * length)
#if defined(__aarch64__) && defined(__clang__)
                for (int j = 0; j < quarter; j += 2) {
                    const ComplexPair w1 = loadRoots(j * stride, stride);
                    const ComplexPair w2 = loadRoots(j * pairedStride, pairedStride);
                    const ComplexPair w3 = loadRoots((j + quarter) * pairedStride, pairedStride);
                    ComplexPair a = loadPair(&data[start + j]);
                    ComplexPair b = loadPair(&data[start + j + quarter]);
                    ComplexPair c = loadPair(&data[start + j + 2 * quarter]);
                    ComplexPair d = loadPair(&data[start + j + 3 * quarter]);
                    butterflyPair(a, b, w1);
                    butterflyPair(c, d, w1);
                    butterflyPair(a, c, w2);
                    butterflyPair(b, d, w3);
                    storePair(&data[start + j], a);
                    storePair(&data[start + j + quarter], b);
                    if (Inverse || 2 * length != Size) {
                        storePair(&data[start + j + 2 * quarter], c);
                        storePair(&data[start + j + 3 * quarter], d);
                    } else if (j == 0) {
                        // Preserve the computed Nyquist, including signed zeros.
                        data[Size / 2] =
                            Complex(vgetq_lane_f64(c.real, 0), vgetq_lane_f64(c.imag, 0));
                    }
                }
#else
                for (int j = 0; j < quarter; ++j) {
                    const Complex w1 =
                        Inverse ? std::conj(roots[j * stride]) : roots[j * stride];
                    const Complex w2 =
                        Inverse ? std::conj(roots[j * pairedStride]) : roots[j * pairedStride];
                    const Complex w3 = Inverse ? std::conj(roots[(j + quarter) * pairedStride])
                                               : roots[(j + quarter) * pairedStride];
                    Complex a = data[start + j], b = data[start + j + quarter];
                    Complex c = data[start + j + 2 * quarter];
                    Complex d = data[start + j + 3 * quarter];
                    butterfly(a, b, w1);
                    butterfly(c, d, w1);
                    butterfly(a, c, w2);
                    butterfly(b, d, w3);
                    data[start + j] = a;
                    data[start + j + quarter] = b;
                    if (Inverse || 2 * length != Size) {
                        data[start + j + 2 * quarter] = c;
                        data[start + j + 3 * quarter] = d;
                    } else if (j == 0) {
                        data[Size / 2] = c;
                    }
                }
#endif
        }
        // A 1024-point transform has one unpaired final stage.
        for (; length <= Size; length *= 2) {
            const int half = length / 2, stride = Size / length;
            for (int start = 0; start < Size; start += length)
#if defined(__aarch64__) && defined(__clang__)
                for (int j = 0; j < half; j += 2) {
                    const ComplexPair root = loadRoots(j * stride, stride);
                    ComplexPair a = loadPair(&data[start + j]);
                    ComplexPair b = loadPair(&data[start + j + half]);
                    butterflyPair(a, b, root);
                    storePair(&data[start + j], a);
                    if (Inverse || length != Size) {
                        storePair(&data[start + j + half], b);
                    } else if (j == 0) {
                        data[Size / 2] =
                            Complex(vgetq_lane_f64(b.real, 0), vgetq_lane_f64(b.imag, 0));
                    }
                }
#else
                for (int j = 0; j < half; ++j) {
                    const Complex root = Inverse ? std::conj(roots[j * stride]) : roots[j * stride];
                    const Complex even = data[start + j], odd = data[start + j + half] * root;
                    data[start + j] = even + odd;
                    if (Inverse || length != Size || j == 0)
                        data[start + j + half] = even - odd;
                }
#endif
        }
        if (Inverse) for (int i = 0; i < Size; ++i) data[i] /= Size;
    }

    void fft(std::array<Complex, maximumWindow>& data, bool inverse) const {
        if (windowSize == 1024) {
            if (inverse) fftKernel<1024, true>(data);
            else fftKernel<1024, false>(data);
        } else {
            if (inverse) fftKernel<2048, true>(data);
            else fftKernel<2048, false>(data);
        }
    }

    int64_t alignWaveform(int64_t nominal) const {
        if (first) return nominal;
        const int64_t natural = adjustedCenter + hop;
        const int64_t low = std::max<int64_t>(0, nominal - search);
        const int64_t high = nominal + search;
        // Matching natural progression exactly is preferable to any periodic alias.
        // The absolute nominal bounds prevent local similarity from accumulating
        // unlimited timing drift.
        if (natural >= low && natural <= high) return natural;
        const int length = windowSize - hop;
        const int64_t reference = natural - windowSize / 2;
        double referenceEnergy = 0;
        for (int i = 0; i < length; i += 4) for (int ch = 0; ch < 2; ++ch) {
            const double x = sample(reference + i, ch);
            referenceEnergy += x * x;
        }
        if (referenceEnergy == 0) return nominal;
        auto score = [&](int64_t center) {
            double dot = 0, b = 0;
            for (int i = 0; i < length; i += 4) for (int ch = 0; ch < 2; ++ch) {
                const double x = sample(reference + i, ch);
                const double y = sample(center - windowSize / 2 + i, ch);
                dot += x * y; b += y * y;
            }
            // Joint-channel energy, never L+R: antiphase material must not cancel.
            return b > 0 ? dot / std::sqrt(referenceEnergy * b) : -1.0;
        };
        int64_t best = nominal;
        double bestScore = score(best);
        for (int64_t candidate = low; candidate <= high; candidate += 4) {
            const double candidateScore = score(candidate);
            if (candidateScore > bestScore + 1e-12) { bestScore = candidateScore; best = candidate; }
        }
        const int64_t coarse = best;
        for (int64_t candidate = std::max(low, coarse - 3); candidate <= std::min(high, coarse + 3); ++candidate) {
            const double candidateScore = score(candidate);
            if (candidateScore > bestScore + 1e-12) { bestScore = candidateScore; best = candidate; }
        }
        return best;
    }

    void inverseStereo() {
        const int nyquist = windowSize / 2;
        spectrum[0][0] = Complex(spectrum[0][0].real(), spectrum[1][0].real());
        spectrum[0][nyquist] = Complex(spectrum[0][nyquist].real(), spectrum[1][nyquist].real());
        // IFFT(L + iR) = left + i*right. Preserve both Hermitian spectra;
        // this combines transform work, not the channels or their phase analysis.
        for (int k = 1; k < nyquist; ++k) {
            const Complex left = spectrum[0][k], right = spectrum[1][k];
            spectrum[0][k] = Complex(left.real() - right.imag(), left.imag() + right.real());
            spectrum[0][windowSize - k] = Complex(left.real() + right.imag(), right.real() - left.imag());
        }
        fft(spectrum[0], true);
    }

    void phaseVocoder(int64_t center, bool initial, int64_t priorAnalysisCenter) {
        auto& history = *previousSpectrum;
        const int bins = windowSize / 2 + 1;
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < windowSize; ++i)
                spectrum[ch][i] = sample(center - windowSize / 2 + i, ch) * window[i];
            fft(spectrum[ch], false);
        }
        double flux = 0, previousTotal = 0;
        for (int k = 0; k < bins; ++k) {
            magnitude[k] = std::sqrt(std::norm(spectrum[0][k]) + std::norm(spectrum[1][k]));
            flux += std::max(0.0, magnitude[k] - previousMagnitude[k]);
            previousTotal += previousMagnitude[k];
        }
        // A shared positive spectral-flux onset resets vertical phase coherence.
        // This conservative heuristic is not harmonic/percussive separation.
        const bool transient = initial || (flux > 0.35 * previousTotal && flux > 1.0);
        int peakCount = 0;
        for (int k = 1; k < bins - 1; ++k)
            if (magnitude[k] > magnitude[k - 1] && magnitude[k] >= magnitude[k + 1]) peaks[peakCount++] = k;
        if (!peakCount) peaks[peakCount++] = static_cast<int>(std::max_element(magnitude.begin(), magnitude.begin() + bins) - magnitude.begin());
        int regionStart = 0;
        const double analysisHop = static_cast<double>(center - priorAnalysisCenter);
        for (int p = 0; p < peakCount; ++p) {
            const int peak = peaks[p];
            const int regionEnd = p + 1 < peakCount ? (peak + peaks[p + 1]) / 2 + 1 : bins;
            const int referenceChannel = std::norm(spectrum[0][peak]) >= std::norm(spectrum[1][peak]) ? 0 : 1;
            double angle = 0;
            if (!transient) {
                const double phase = std::arg(spectrum[referenceChannel][peak]);
                const double advance = 2 * pi * peak * analysisHop / windowSize;
                const double unwrapped = advance + principal(phase - std::arg(history[referenceChannel][peak]) - advance);
                angle = principal(rotation[peak] + unwrapped * (hop / analysisHop - 1));
            }
            // Laroche/Dolson identity locking: a common complex rotation for the
            // peak and its region. Sharing it across L/R preserves interchannel
            // phase differences, silence, and exact antiphase within each frame.
            const Complex phasor = std::polar(1.0, angle);
            for (int k = regionStart; k < regionEnd; ++k) {
                nextRotation[k] = angle;
                for (int ch = 0; ch < 2; ++ch) {
                    history[ch][k] = spectrum[ch][k];
                    spectrum[ch][k] *= phasor;
                }
            }
            regionStart = regionEnd;
        }
        for (int k = 0; k < bins; ++k) { rotation[k] = nextRotation[k]; previousMagnitude[k] = magnitude[k]; }
    }

    void phaseGradient(int64_t center, int64_t futureCenter, bool initial,
                       int64_t priorAnalysisCenter, int64_t outputCenter) {
        auto& g = *gradient;
        const int bins = windowSize / 2 + 1;
        for (int ch = 0; ch < 2; ++ch) {
            if (initial) {
                for (int i = 0; i < windowSize; ++i)
                    spectrum[ch][i] = sample(center - windowSize / 2 + i, ch) * window[i];
                fft(spectrum[ch], false);
            } else {
                // Negative-frequency bins are reconstructed by inverseStereo().
                std::copy_n(g.future[ch].begin(), bins, spectrum[ch].begin());
            }
            for (int i = 0; i < windowSize; ++i)
                g.future[ch][i] = sample(futureCenter - windowSize / 2 + i, ch) * window[i];
            fft(g.future[ch], false);
            for (int k = 0; k < bins; ++k) {
                // Convert the FFT's left-edge origin to the paper's centered STFT.
                const double argument = initial ? std::arg(spectrum[ch][k]) : g.futureArgument[ch][k];
                g.phase[ch][k] = principal(argument + pi * k);
                g.futureArgument[ch][k] = std::arg(g.future[ch][k]);
                const double omega = 2 * pi * k / windowSize;
                const double forwardHop = static_cast<double>(futureCenter - center);
                const double forward = omega + principal(g.futureArgument[ch][k] + pi * k -
                    g.phase[ch][k] - omega * forwardHop) / forwardHop;
                const double backwardHop = static_cast<double>(center - priorAnalysisCenter);
                const double backward = initial ? forward : omega + principal(g.phase[ch][k] -
                    g.previousPhase[ch][k] - omega * backwardHop) / backwardHop;
                g.time[ch][k] = (forward + backward) * 0.5;
            }
            double backward = principal(g.phase[ch][1] - g.phase[ch][0]);
            for (int k = 0; k < bins; ++k) {
                // Each adjacent-bin difference serves both neighboring gradients.
                const double forward = k && k + 1 < bins ?
                    principal(g.phase[ch][k + 1] - g.phase[ch][k]) : backward;
                g.frequency[ch][k] = (backward + forward) * 0.5;
                backward = forward;
            }
        }
        double maximum = 0;
        for (int k = 0; k < bins; ++k) {
            magnitude[k] = std::sqrt(std::norm(spectrum[0][k]) + std::norm(spectrum[1][k]));
            maximum = std::max({maximum, magnitude[k], previousMagnitude[k]});
            nextRotation[k] = 0; // Deterministic original phase below the relative noise threshold.
        }
        g.modified = g.modified || center != outputCenter || futureCenter != outputCenter + hop;
        if (!initial && g.modified) {
            size_t heapSize = 0;
            constexpr uint16_t noHeapPosition = std::numeric_limits<uint16_t>::max();
            auto less = [](const Gradient::Entry& a, const Gradient::Entry& b) {
                if (a.magnitude != b.magnitude) return a.magnitude < b.magnitude;
                if (a.bin != b.bin) return a.bin > b.bin;
                return a.current < b.current;
            };
            auto siftDown = [&](size_t hole, Gradient::Entry entry) {
                const size_t firstLeaf = heapSize / 2;
                while (hole < firstLeaf) {
                    size_t child = hole * 2 + 1;
                    if (child + 1 < heapSize && less(g.heap[child], g.heap[child + 1])) ++child;
                    if (!less(entry, g.heap[child])) break;
                    g.heap[hole] = g.heap[child];
                    g.positions[g.heap[hole].bin] = static_cast<uint16_t>(hole);
                    hole = child;
                }
                g.heap[hole] = entry;
                g.positions[entry.bin] = static_cast<uint16_t>(hole);
            };
            auto siftUp = [&](size_t hole, Gradient::Entry entry) {
                while (hole) {
                    const size_t parent = (hole - 1) / 2;
                    if (!less(g.heap[parent], entry)) break;
                    g.heap[hole] = g.heap[parent];
                    g.positions[g.heap[hole].bin] = static_cast<uint16_t>(hole);
                    hole = parent;
                }
                g.heap[hole] = entry;
                g.positions[entry.bin] = static_cast<uint16_t>(hole);
            };
            auto replace = [&](size_t hole, Gradient::Entry entry) {
                if (hole && less(g.heap[(hole - 1) / 2], entry)) siftUp(hole, entry);
                else siftDown(hole, entry);
            };
            auto remove = [&](size_t hole) {
                g.positions[g.heap[hole].bin] = noHeapPosition;
                --heapSize;
                if (hole != heapSize) replace(hole, g.heap[heapSize]);
            };
            auto updateCurrent = [&](int bin) {
                const size_t position = g.positions[bin];
                // The bin was pending, so its previous entry is still present.
                // Pending only decreases: an inert current entry can never help.
                if ((bin > 0 && g.pending[bin - 1]) ||
                    (bin + 1 < bins && g.pending[bin + 1]))
                    replace(position, {magnitude[bin], bin, true});
                else remove(position);
            };
            for (int k = 0; k < bins; ++k) {
                g.pending[k] = magnitude[k] > maximum * 1e-6;
                g.positions[k] = noHeapPosition;
                if (g.pending[k]) {
                    g.positions[k] = static_cast<uint16_t>(heapSize);
                    g.heap[heapSize++] = {previousMagnitude[k], k, false};
                }
            }
            // One entry per eligible bin; bottom-up construction is linear.
            for (size_t parent = heapSize / 2; parent; --parent)
                siftDown(parent - 1, g.heap[parent - 1]);
            size_t pending = heapSize;
            const double alpha = 2.0 * hop / (futureCenter - priorAnalysisCenter);
            while (heapSize && pending) {
                const auto entry = g.heap[0];
                const int k = entry.bin;
                if (!entry.current) {
                    const int ch = std::norm(spectrum[0][k]) >= std::norm(spectrum[1][k]) ? 0 : 1;
                    nextRotation[k] = principal(g.previousPhase[ch][k] + rotation[k] +
                        hop * 0.5 * (g.previousTime[ch][k] + g.time[ch][k]) - g.phase[ch][k]);
                    g.pending[k] = false;
                    --pending;
                    updateCurrent(k);
                } else {
                    remove(0);
                    for (int direction : {-1, 1}) {
                        const int neighbor = k + direction;
                        if (neighbor < 0 || neighbor >= bins || !g.pending[neighbor]) continue;
                        const int ch = std::norm(spectrum[0][k]) + std::norm(spectrum[0][neighbor]) >=
                            std::norm(spectrum[1][k]) + std::norm(spectrum[1][neighbor]) ? 0 : 1;
                        nextRotation[neighbor] = principal(g.phase[ch][k] + nextRotation[k] +
                            direction * alpha * 0.5 * (g.frequency[ch][k] + g.frequency[ch][neighbor]) -
                            g.phase[ch][neighbor]);
                        g.pending[neighbor] = false;
                        --pending;
                        updateCurrent(neighbor);
                    }
                }
            }
        }
        for (int k = 0; k < bins; ++k) {
            const Complex phasor = std::polar(1.0, nextRotation[k]);
            for (int ch = 0; ch < 2; ++ch) {
                spectrum[ch][k] *= phasor;
                g.previousPhase[ch][k] = g.phase[ch][k];
                g.previousTime[ch][k] = g.time[ch][k];
            }
            rotation[k] = nextRotation[k];
            previousMagnitude[k] = magnitude[k];
        }
    }

    void addEndpointFrame() {
        const int64_t outputCenter = target() - 1;
        const int64_t inputCenter = end - 1;
        // Finite-stream boundary condition: an identity-phase analysis frame
        // centered on the final source sample is centered on the final output
        // sample. Hop-grid phase propagation alone can place an onset just beyond
        // the rounded output boundary. This regular, signal-independent OLA term
        // keeps that boundary represented without extra output or resampling.
        for (int i = 0; i < windowSize; ++i) {
            const int64_t position = outputCenter - windowSize / 2 + i;
            if (position < emitted || position > outputCenter) continue;
            const size_t slot = static_cast<size_t>(position) % overlapCapacity;
            const double w = search ? window[i] : window[i] * window[i];
            weight[slot] += w;
            for (int ch = 0; ch < 2; ++ch)
                overlap[slot][ch] += sample(inputCenter - windowSize / 2 + i, ch) * w;
        }
        endpointAdded = true;
    }

    bool synthesize() {
        if (!end || (final && emitted >= target())) return false;
        if (final && !endpointAdded && synthesisCenter >= target() - 1) {
            addEndpointFrame();
            if (failure) return false;
            ready = std::min(target(), std::max(emitted, synthesisCenter - windowSize / 2));
            return true;
        }
        if (!final && synthesisCenter >= duration) return false;
        const int64_t center = static_cast<int64_t>(std::llround(sourcePosition(static_cast<double>(synthesisCenter))));
        int64_t futureCenter = center;
        if (gradient) {
            if (!final && synthesisCenter + hop >= duration) return false;
            const auto savedCursor = timeCursor;
            futureCenter = static_cast<int64_t>(std::llround(sourcePosition(static_cast<double>(synthesisCenter + hop))));
            timeCursor = savedCursor;
        }
        if (!final && futureCenter + windowSize / 2 + search > end) return false;
        const int64_t selected = search ? alignWaveform(center) : center;
        if (failure) return false;
        // Only the initial identity run may bypass spectral processing. Once
        // primed, keep all phase history even if the source clock returns to unity.
        bool identityGrain = !search && !spectralPrimed && center == synthesisCenter &&
            (!gradient || futureCenter == synthesisCenter + hop);
        if (identityGrain && !gradient) {
            // Center zero also matches a non-unity start. Require the next
            // mapped center without changing the existing output-readiness gate.
            const auto nextCenter = synthesisCenter + hop;
            if (nextCenter > end ||
                sourceEndTime[static_cast<size_t>(nextCenter - 1) % inputCapacity] !=
                    static_cast<double>(nextCenter)) {
                const auto savedCursor = timeCursor;
                identityGrain = std::llround(sourcePosition(static_cast<double>(nextCenter))) == nextCenter;
                timeCursor = savedCursor;
            }
        }
        if (identityGrain && gradient) {
            const auto secondCenter = synthesisCenter + 2 * hop;
            const auto lookaheadEnd = futureCenter + windowSize / 2;
            if (lookaheadEnd <= end &&
                sourceEndTime[static_cast<size_t>(lookaheadEnd - 1) % inputCapacity] !=
                    static_cast<double>(lookaheadEnd) &&
                sourceEndTime[static_cast<size_t>(secondCenter - 1) % inputCapacity] ==
                    static_cast<double>(secondCenter)) {
                // The next frame is also identity. Warm raw phase now; that
                // frame restores the centered derivative before correction.
                phaseGradient(center, futureCenter, true, previousCenter, synthesisCenter);
                spectralPrimed = true;
            }
        }
        if (!search && !identityGrain) {
            if (!spectralPrimed && !first) {
                if (gradient) {
                    if (previousCenter != 0) {
                        // The first replay restores phase; the second restores
                        // the centered time derivative of the previous frame.
                        phaseGradient(previousPreviousCenter, previousCenter, true,
                                      previousPreviousCenter, synthesisCenter - 2 * hop);
                        phaseGradient(previousCenter, center, false,
                                      previousPreviousCenter, synthesisCenter - hop);
                    } else {
                        phaseGradient(previousCenter, center, true,
                                      previousCenter, synthesisCenter - hop);
                    }
                } else {
                    phaseVocoder(previousCenter, true, previousPreviousCenter);
                }
            }
            if (gradient) phaseGradient(center, futureCenter, first, previousCenter, synthesisCenter);
            else phaseVocoder(center, first, previousCenter);
            // Replayed frames restore analysis history only: no inverse or OLA.
            inverseStereo();
            spectralPrimed = true;
        }
        if (failure) return false;
        const auto finalLength = final ? target() : std::numeric_limits<int64_t>::max();
        for (int i = 0; i < windowSize; ++i) {
            const int64_t position = synthesisCenter - windowSize / 2 + i;
            if (position < emitted || position >= finalLength) continue;
            const size_t slot = static_cast<size_t>(position) % overlapCapacity;
            const double w = window[i];
            weight[slot] += search ? w : w * w;
            if (search) {
                for (int ch = 0; ch < 2; ++ch)
                    overlap[slot][ch] += sample(selected - windowSize / 2 + i, ch) * w;
            } else if (identityGrain) {
                for (int ch = 0; ch < 2; ++ch)
                    overlap[slot][ch] += (sample(center - windowSize / 2 + i, ch) * w) * w;
            } else {
                // Consume the packed inverse directly; no right-channel copy pass.
                overlap[slot][0] += spectrum[0][i].real() * w;
                overlap[slot][1] += spectrum[0][i].imag() * w;
            }
        }
        if (failure) return false;
        previousPreviousCenter = previousCenter;
        previousCenter = center;
        adjustedCenter = selected;
        first = false;
        synthesisCenter += hop;
        const int64_t nextCenter = final && !endpointAdded ?
            std::min(synthesisCenter, target() - 1) : synthesisCenter;
        ready = std::max<int64_t>(emitted, nextCenter - windowSize / 2);
        if (final) ready = std::min(ready, target());
        // Retain the previous natural-progression overlap, the next nominal search
        // range, and one source-clock boundary for inversion. No memmove is needed.
        // Until gradient priming, retain both preceding unity analysis windows.
        const int historyHop = gradient && !spectralPrimed ? hop : 0;
        const int64_t retain = std::min({center - windowSize / 2 - search - historyHop - 2,
                                        selected + hop - windowSize / 2,
                                        timeCursor - 1});
        begin = std::max(begin, std::max<int64_t>(0, retain));
        return true;
    }
};

AudioTimeStretcher::AudioTimeStretcher(Engine engine) : impl(allocate<Impl>(engine)) {}
AudioTimeStretcher::~AudioTimeStretcher() { release(impl); }
const char* AudioTimeStretcher::error() const {
    if (!impl) return "Out of memory creating time stretcher";
    return impl->failure ? impl->failure : operationError;
}
bool AudioTimeStretcher::beginOperation() {
    operationError = nullptr;
    return impl && !impl->failure;
}
bool AudioTimeStretcher::process(const int16_t* pcm, size_t frames, double tempo) {
    if (!beginOperation()) return false;
    auto& p = *impl;
    if (!pcm || p.final || frames == 0 || frames > 480 || !std::isfinite(tempo) || tempo < 0.5 || tempo > 2.0) {
        operationError = "Invalid PCM time-stretch input";
        return false;
    }
    if (frames > inputCapacity - static_cast<size_t>(p.end - p.begin)) {
        operationError = "Time-stretch input full; receive output before retrying";
        return false;
    }
    if (frames > static_cast<uint64_t>(maximumSourceFrames - p.end)) {
        operationError = "Time-stretch stream too long";
        return false;
    }
    const bool changedTempo = tempo != p.lastTempo;
    const auto& anchor = changedTempo ? p.acceptedDuration : p.completedDuration;
    const int64_t runFrames = (changedTempo ? 0 : p.end - p.tempoRunStart) + static_cast<int64_t>(frames);
    const auto nextDuration = addDuration(anchor, runDuration(runFrames, tempo));
    if (!(nextDuration.radius < 0.25)) {
        operationError = "Time-stretch duration precision exhausted";
        return false;
    }
    const int64_t nextTarget = roundedDuration(nextDuration);
    // Preview once per accepted block against frozen completed runs. Only actual
    // tempo changes commit an anchor; rejected blocks and packet splits cannot.
    if (changedTempo) {
        p.completedDuration = p.acceptedDuration;
        p.tempoRunStart = p.end;
        p.lastTempo = tempo;
    }
    // Preserve the original per-frame phase clock and already-published analysis
    // positions. Higher-precision final-duration rounding is separate, so fixing
    // a Stop boundary does not retime the user's existing spectral processing.
    const double step = 1.0 / tempo;
    for (size_t i = 0; i < frames; ++i) {
        const size_t slot = static_cast<size_t>(p.end++) % inputCapacity;
        p.source[slot] = {pcm[i * 2], pcm[i * 2 + 1]};
        const double increment = step - p.durationError;
        const double next = p.duration + increment;
        p.durationError = (next - p.duration) - increment;
        p.duration = next;
        p.sourceEndTime[slot] = p.duration;
    }
    p.acceptedDuration = nextDuration;
    p.durationTarget = nextTarget;
    return true;
}
bool AudioTimeStretcher::finish() {
    if (!beginOperation()) return false;
    impl->final = true;
    return true;
}
size_t AudioTimeStretcher::receive(int16_t* pcm, size_t capacityFrames) {
    if (!beginOperation()) return 0;
    if (!pcm && capacityFrames) {
        operationError = "Invalid PCM output";
        return 0;
    }
    auto& p = *impl;
    size_t written = 0;
    while (written < capacityFrames) {
        if (p.emitted == p.ready) { if (!p.synthesize()) break; else continue; }
        const size_t slot = static_cast<size_t>(p.emitted) % overlapCapacity;
        int16_t frame[2];
        for (int ch = 0; ch < 2; ++ch) {
            if (!quantize(p.weight[slot] > 0 ? p.overlap[slot][ch] / p.weight[slot] : 0, frame[ch])) {
                p.failure = "Non-finite time-stretch output";
                return 0;
            }
        }
        for (int ch = 0; ch < 2; ++ch) {
            pcm[written * 2 + ch] = frame[ch];
            p.overlap[slot][ch] = 0;
        }
        p.weight[slot] = 0;
        ++p.emitted; ++written;
    }
    return p.failure ? 0 : written;
}
bool AudioTimeStretcher::finished() const {
    return impl && !impl->failure && impl->final && impl->emitted == impl->target();
}
