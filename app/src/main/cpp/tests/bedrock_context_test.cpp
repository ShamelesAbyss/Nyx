#include "nyx_context.h"
#include <iostream>
#include <limits>
#include <stdexcept>

static void require(bool value) { if (!value) throw std::runtime_error("context policy regression"); }
int main() {
    for (const auto & edge : {std::pair<int, int>{512, 512}, {513, 768}, {767, 768}, {768, 768}, {32767, 32768}, {32768, 32768}})
        require(nyx::allocation_context(edge.first) == edge.second);
    for (int bad : {std::numeric_limits<int>::min(), 0, 511, 32769, std::numeric_limits<int>::max()}) {
        bool rejected = false;
        try { nyx::allocation_context(bad); } catch (const std::invalid_argument &) { rejected = true; }
        require(rejected);
    }
    require(nyx::generation_limit(513, 512, 4096) == 1);
    require(nyx::generation_limit(513, 500, 4096) == 13);
    require(nyx::generation_limit(513, 500, 4) == 4);
    for (int prompt : {513, 600, 767, 768}) {
        bool rejected = false;
        try { nyx::generation_limit(513, prompt, 32); } catch (const std::runtime_error &) { rejected = true; }
        require(rejected);
    }
    std::cout << "BEDROCK_CPP_CONTEXT_POLICY_OK (pure host policy, no model)\n";
}
