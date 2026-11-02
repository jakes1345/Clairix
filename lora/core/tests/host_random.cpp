// The host's randomBytes, for the tests: the kernel's CSPRNG.
#include <cstdio>
#include <cstdlib>

#include "clx/crypto.h"

namespace clx {

void randomBytes(uint8_t* out, size_t n) {
    static FILE* f = std::fopen("/dev/urandom", "rb");
    if (!f || std::fread(out, 1, n, f) != n) std::abort();
}

}  // namespace clx
