// Include the implementation to check its private reduction without exporting a DSP test API.
#include "../../main/cpp/audio_time_stretcher.cpp"
#include <cstdio>
#include <cstring>

static uint64_t bits(double value) {
    uint64_t result;
    std::memcpy(&result, &value, sizeof(result));
    return result;
}
static void checkPhase(double phase) {
    const double expected = std::remainder(phase, 2 * pi);
    const double actual = principal(phase);
    if ((std::isnan(expected) && std::isnan(actual)) || bits(expected) == bits(actual)) return;
    std::fprintf(stderr, "phase=%a expected=%a actual=%a\n", phase, expected, actual);
    std::exit(1);
}
int main() {
    // Quotient ties and their immediate neighbors catch incorrect cycle selection,
    // which can propagate across the phase-gradient integration heap.
    for (int n = -167000; n <= 167000; ++n) {
        for (double offset : {0.0, 0.5}) {
            const double phase = (n + offset) * (2 * pi);
            checkPhase(phase);
            checkPhase(std::nextafter(phase, -INFINITY));
            checkPhase(std::nextafter(phase, INFINITY));
        }
    }
    for (double phase : {0.0, -0.0, pi, -pi, 0x1p20, -0x1p20,
                         std::numeric_limits<double>::denorm_min(),
                         -std::numeric_limits<double>::denorm_min(),
                         std::numeric_limits<double>::max(), INFINITY * 1.0, NAN * 1.0}) {
        checkPhase(phase);
        checkPhase(std::nextafter(phase, -INFINITY));
        checkPhase(std::nextafter(phase, INFINITY));
    }
    uint64_t state = 0x589965cc75374cc3ULL;
    for (int n = 0; n < 2000000; ++n) {
        state ^= state << 13; state ^= state >> 7; state ^= state << 17;
        // Dense hot-range coverage plus arbitrary binary64 exponents for fallback.
        checkPhase((static_cast<double>(state >> 11) * 0x1p-53 - 0.5) * 0x1p21);
        double phase;
        std::memcpy(&phase, &state, sizeof(phase));
        checkPhase(phase);
    }
    std::puts("PASS phase reduction matches libm bit-for-bit, including ties and signed zero");
}
