#include "nyx_session.h"
#include <iostream>
#include <stdexcept>
int main(int argc, char ** argv) {
    if (argc != 2) { std::cerr << "No real inference executed: pass an external instruct GGUF path\n"; return 77; }
    try {
        nyx::Session session;
        session.begin();
        session.load(argv[1], 512, 4);
        const std::vector<std::pair<std::string, std::string>> chat = {
            {"system", "You are a helpful assistant."}, {"user", "What is the capital of France?"}};
        std::string text;
        session.begin();
        const int count = session.generate(chat, 0, 32, 42, [&](const std::string & piece) { text += piece; std::cout << piece << std::flush; });
        if (count == 0 || text.empty()) throw std::runtime_error("Real model returned no tokens");
        std::cout << "\nREAL_CPP_OK tokens=" << count << "\n";
        session.unload();
        return 0;
    } catch (const std::exception & e) { std::cerr << e.what() << '\n'; return 1; }
}
