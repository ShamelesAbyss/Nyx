#include "nyx_session.h"
#include "llama.h"
#include <iostream>
#include <memory>
#include <stdexcept>

int main(int argc, char ** argv) {
    if (argc != 2) { std::cerr << "No real model test executed: pass an authorized instruct GGUF\n"; return 77; }
    try {
        nyx::Session session;
        session.begin();
        if (session.load(argv[1], 513, 4) != 768) throw std::runtime_error("Padded allocation mismatch");
        // Independently tokenize a prompt that fits allocated capacity but exceeds the usable request.
        // This metadata-only reference mmap is host-test owned; no model writes or generated benchmarks.
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.use_extra_bufts = false;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1], mp), llama_model_free);
        if (!model) throw std::runtime_error("Could not open reference tokenizer");
        const char * tmpl = llama_model_chat_template(model.get(), nullptr);
        if (!tmpl) throw std::runtime_error("No reference template");
        std::string user;
        int prompt_tokens = 0;
        for (int i = 0; i < 1024; ++i) {
            user += "hello ";
            llama_chat_message message{"user", user.c_str()};
            const int size = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
            if (size <= 0) throw std::runtime_error("Unsupported reference template");
            std::vector<char> formatted(static_cast<size_t>(size) + 1);
            if (llama_chat_apply_template(tmpl, &message, 1, true, formatted.data(), formatted.size()) != size)
                throw std::runtime_error("Reference template changed size");
            prompt_tokens = -llama_tokenize(llama_model_get_vocab(model.get()), formatted.data(), size, nullptr, 0, true, true);
            if (prompt_tokens >= 513) break;
        }
        if (prompt_tokens < 513 || prompt_tokens >= 768) throw std::runtime_error("Could not qualify padded-only prompt");
        model.reset(); // No second weight mapping while decoding.
        bool rejected = false;
        session.begin();
        try { session.generate({{"user", user}}, 0, 32, 42, [](const std::string &) {}); }
        catch (const std::runtime_error & error) {
            rejected = std::string(error.what()).find("Prompt exceeds native context") != std::string::npos;
        }
        if (!rejected) throw std::runtime_error("Padding incorrectly extended usable generation context");
        std::cout << "REAL_BEDROCK_CPP_USABLE_CONTEXT_OK requested=513 allocated=768 measured_prompt_tokens=" << prompt_tokens << '\n';
        std::string output;
        session.begin();
        const int tokens = session.generate({{"user", "What is the capital of France?"}}, 0, 32, 42,
            [&](const std::string & piece) { output += piece; });
        if (tokens <= 0 || output.empty()) throw std::runtime_error("Rejected prompt prevented subsequent inference");
        std::cout << "REAL_BEDROCK_CPP_POST_REJECTION_OK tokens=" << tokens << '\n';
        session.unload();
        return 0;
    } catch (const std::exception & error) { std::cerr << error.what() << '\n'; return 1; }
}
