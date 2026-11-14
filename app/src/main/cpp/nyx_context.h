#pragma once
#include <stdexcept>

namespace nyx {
// Pinned llama.cpp pads the single-sequence KV allocation to 256 positions.
// Allocation padding is not authority to extend the user's usable context.
inline int allocation_context(int requested) {
    if (requested < 512 || requested > 32768)
        throw std::invalid_argument("Context must be 512..32768");
    return ((requested + 255) / 256) * 256;
}
inline int generation_limit(int requested, int prompt, int max_tokens) {
    allocation_context(requested);
    if (prompt < 1 || prompt >= requested)
        throw std::runtime_error("Prompt exceeds native context; reduce history");
    if (max_tokens < 1 || max_tokens > 4096)
        throw std::invalid_argument("Invalid generation limits");
    return max_tokens < requested - prompt ? max_tokens : requested - prompt;
}
}
