#include "nyx_session.h"
#include "nyx_context.h"
#include "llama.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <mutex>
#include <stdexcept>

namespace nyx {
namespace {
constexpr int MAX_PROMPT_BYTES = 1024 * 1024;
constexpr size_t MAX_OUTPUT_BYTES = 1024 * 1024;
std::once_flag backend_once;
struct Batch {
    llama_batch value;
    explicit Batch(int size) : value(llama_batch_init(size, 0, 1)) {
        if (!value.token || !value.pos || !value.n_seq_id || !value.seq_id || !value.logits) {
            llama_batch_free(value);
            throw std::bad_alloc();
        }
    }
    ~Batch() { llama_batch_free(value); }
    void set(const llama_token * tokens, int count, int position, bool output) {
        value.n_tokens = count;
        for (int i = 0; i < count; ++i) {
            value.token[i] = tokens[i];
            value.pos[i] = position + i;
            value.n_seq_id[i] = 1;
            value.seq_id[i][0] = 0;
            value.logits[i] = output && i == count - 1;
        }
    }
};
using ModelPtr = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using ContextPtr = std::unique_ptr<llama_context, decltype(&llama_free)>;
using SamplerPtr = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
struct MemoryReset {
    llama_context * ctx;
    std::vector<int32_t> * cache;
    bool keep = false;
    ~MemoryReset() {
        if (keep) return;
        llama_synchronize(ctx);
        // Aborted ubatches may have changed the memory; never reuse partial state.
        llama_memory_clear(llama_get_memory(ctx), true);
        if (cache) cache->clear();
    }
};
}
Session::Session() = default;
Session::~Session() { unload(); }
void Session::begin() noexcept { cancelled_.store(false, std::memory_order_release); }
void Session::cancel() noexcept { cancelled_.store(true, std::memory_order_release); }
void Session::check_cancelled() const {
    if (cancelled_.load(std::memory_order_acquire)) throw Cancelled();
}
bool Session::abort_cb(void * data) {
    return static_cast<Session *>(data)->cancelled_.load(std::memory_order_acquire);
}
bool Session::progress_cb(float, void * data) { return !abort_cb(data); }
void Session::unload() noexcept {
    if (context_) { llama_synchronize(context_); llama_free(context_); context_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
    path_.clear(); context_requested_ = 0; threads_ = 0; cached_tokens_.clear();
}
int Session::load(const std::string & path, int context, int threads) {
    if (path.empty() || path[0] != '/' || path.find('\0') != std::string::npos)
        throw std::invalid_argument("Model path must be an absolute filesystem path");
    if (context < 512 || context > 32768 || threads < 1 || threads > 32)
        throw std::invalid_argument("Context must be 512..32768 and threads 1..32");
    const int allocated_context = allocation_context(context);
    check_cancelled();
    std::call_once(backend_once, [] { llama_backend_init(); });
    if (model_ && path_ == path && context_requested_ == context && threads_ == threads)
        return static_cast<int>(llama_n_ctx(context_));
    // Free before loading to avoid transient double-weight memory use.
    unload();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.use_extra_bufts = false; // no duplicate/repacked weight allocation
    mp.check_tensors = true;
    mp.progress_callback = progress_cb;
    mp.progress_callback_user_data = this;
    ModelPtr model(llama_model_load_from_file(path.c_str(), mp), llama_model_free);
    check_cancelled();
    if (!model) throw std::runtime_error("llama.cpp could not load GGUF weights; inspect device logs");
    if (llama_model_has_encoder(model.get()) || !llama_model_has_decoder(model.get()) ||
        llama_model_is_recurrent(model.get()) || llama_model_is_hybrid(model.get()) ||
        llama_model_is_diffusion(model.get()))
        throw std::runtime_error("Only decoder-only transformer text models are supported");
    if (context > llama_model_n_ctx_train(model.get()))
        throw std::runtime_error("Requested context exceeds model training context");
    const char * tmpl = llama_model_chat_template(model.get(), nullptr);
    if (!tmpl || !*tmpl) throw std::runtime_error("GGUF has no default tokenizer.chat_template; no guessed fallback is used");
    llama_chat_message probe{"user", "Hello"};
    if (llama_chat_apply_template(tmpl, &probe, 1, true, nullptr, 0) < 0)
        throw std::runtime_error("Model chat template is unsupported by llama_chat_apply_template");
    auto cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(allocated_context);
    cp.n_batch = 256; cp.n_ubatch = 128; cp.n_seq_max = 1;
    cp.n_threads = threads; cp.n_threads_batch = threads;
    cp.type_k = GGML_TYPE_F16; cp.type_v = GGML_TYPE_F16;
    cp.offload_kqv = false; cp.op_offload = false;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.abort_callback = abort_cb; cp.abort_callback_data = this;
    ContextPtr ctx(llama_init_from_model(model.get(), cp), llama_free);
    check_cancelled();
    if (!ctx) throw std::runtime_error("llama.cpp context allocation failed");
    if (llama_n_ctx(ctx.get()) != static_cast<uint32_t>(allocated_context))
        throw std::runtime_error("Native allocation context disagrees with admission policy");
    path_ = path; context_requested_ = context; threads_ = threads;
    model_ = model.release(); context_ = ctx.release();
    return static_cast<int>(llama_n_ctx(context_));
}
int Session::generate(const std::vector<std::pair<std::string, std::string>> & messages,
                      float temperature, int max_tokens, uint32_t seed,
                      const std::function<void(const std::string &)> & emit) {
    check_cancelled();
    if (!context_ || !model_) throw std::runtime_error("No native model loaded");
    if (messages.empty() || messages.size() > 256 || max_tokens < 1 || max_tokens > 4096 ||
        !std::isfinite(temperature) || temperature < 0 || temperature > 2)
        throw std::invalid_argument("Invalid generation limits");
    std::vector<llama_chat_message> chat;
    size_t input_bytes = 0;
    for (const auto & item : messages) {
        if (item.first != "system" && item.first != "user" && item.first != "assistant")
            throw std::invalid_argument("Only system/user/assistant roles are supported");
        input_bytes += item.first.size() + item.second.size();
        if (input_bytes > MAX_PROMPT_BYTES || item.second.find('\0') != std::string::npos)
            throw std::invalid_argument("Chat content is too large or contains NUL");
        chat.push_back({item.first.c_str(), item.second.c_str()});
    }
    const char * tmpl = llama_model_chat_template(model_, nullptr);
    const int needed = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, nullptr, 0);
    if (needed <= 0 || needed > MAX_PROMPT_BYTES)
        throw std::runtime_error("Unsupported template or formatted prompt exceeds 1 MiB");
    std::vector<char> formatted(static_cast<size_t>(needed) + 1);
    const int formatted_len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true,
                                                       formatted.data(), static_cast<int>(formatted.size()));
    if (formatted_len != needed) throw std::runtime_error("Chat template formatting failed");
    check_cancelled();
    const llama_vocab * vocab = llama_model_get_vocab(model_);
    const int required = llama_tokenize(vocab, formatted.data(), needed, nullptr, 0, true, true);
    if (required == std::numeric_limits<int32_t>::min() || required >= 0)
        throw std::runtime_error("Prompt tokenization failed");
    const int n_prompt = -required;
    // No silent history truncation or context sliding; reserve at least one output token.
    const int limit = generation_limit(context_requested_, n_prompt, max_tokens);
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(vocab, formatted.data(), needed, tokens.data(), n_prompt, true, true) != n_prompt)
        throw std::runtime_error("Prompt tokenization failed");
    // Conservative prefix reuse: only an exact token-for-token match is trusted. At least
    // one prompt token is always decoded so logits exist for the first sample.
    llama_memory_t memory = llama_get_memory(context_);
    int reuse = 0;
    if (!cached_tokens_.empty()) {
        const int maximum = std::min(static_cast<int>(cached_tokens_.size()), n_prompt);
        while (reuse < maximum && cached_tokens_[static_cast<size_t>(reuse)] == tokens[static_cast<size_t>(reuse)]) ++reuse;
        reuse = std::min(reuse, n_prompt - 1);
    }
    if (reuse > 0) {
        if (!llama_memory_seq_rm(memory, 0, reuse, -1)) {
            llama_memory_clear(memory, true);
            reuse = 0;
        }
    } else {
        llama_memory_clear(memory, true);
    }
    MemoryReset reset{context_, &cached_tokens_};
    SamplerPtr sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    if (!sampler) throw std::bad_alloc();
    if (temperature == 0) {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed));
    }
    Batch batch(static_cast<int>(llama_n_batch(context_)));
    auto decode = [&](const llama_token * data, int count, int pos, bool logits) {
        check_cancelled();
        batch.set(data, count, pos, logits);
        const int rc = llama_decode(context_, batch.value);
        check_cancelled();
        if (rc == 2) throw Cancelled();
        if (rc != 0) throw std::runtime_error("llama_decode failed with code " + std::to_string(rc));
    };
    for (int pos = reuse; pos < n_prompt;) {
        const int count = std::min(static_cast<int>(llama_n_batch(context_)), n_prompt - pos);
        decode(tokens.data() + pos, count, pos, pos + count == n_prompt);
        pos += count;
    }
    int generated = 0;
    std::vector<llama_token> decoded_generated;
    decoded_generated.reserve(static_cast<size_t>(limit));
    size_t output_bytes = 0;
    for (int i = 0; i < limit; ++i) {
        check_cancelled();
        const llama_token token = llama_sampler_sample(sampler.get(), context_, -1);
        // llama_sampler_sample already accepts the selected token into the sampler.
        if (llama_vocab_is_eog(vocab, token)) break;
        char small[256];
        int n = llama_token_to_piece(vocab, token, small, sizeof(small), 0, false);
        std::string piece;
        if (n < 0) {
            if (n == std::numeric_limits<int32_t>::min() || -n > 65536)
                throw std::runtime_error("Token piece exceeds safe size");
            piece.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (n < 0) throw std::runtime_error("Token piece conversion failed");
            piece.resize(static_cast<size_t>(n));
        } else { piece.assign(small, static_cast<size_t>(n)); }
        if (output_bytes + piece.size() > MAX_OUTPUT_BYTES) break;
        output_bytes += piece.size();
        if (!piece.empty()) emit(piece);
        ++generated;
        if (i + 1 < limit) {
            decode(&token, 1, n_prompt + i, true);
            decoded_generated.push_back(token);
        }
    }
    check_cancelled();
    cached_tokens_.assign(tokens.begin(), tokens.end());
    cached_tokens_.insert(cached_tokens_.end(), decoded_generated.begin(), decoded_generated.end());
    reset.keep = true;
    return generated;
}
}
