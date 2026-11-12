#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>
struct llama_model;
struct llama_context;
namespace nyx {
struct Cancelled final : std::exception {
    const char * what() const noexcept override { return "Native inference cancelled"; }
};
// All operations except cancel/begin are serialized by the JNI mutex or host owner.
// No raw native handles cross JNI, eliminating stale-handle/use-after-free races.
class Session final {
public:
    Session();
    ~Session();
    void begin() noexcept;
    void cancel() noexcept;
    void check_cancelled() const;
    int load(const std::string & path, int context, int threads);
    void unload() noexcept;
    int generate(const std::vector<std::pair<std::string, std::string>> & messages,
                 float temperature, int max_tokens, uint32_t seed,
                 const std::function<void(const std::string &)> & emit);
private:
    static bool abort_cb(void * data);
    static bool progress_cb(float, void * data);
    std::atomic<bool> cancelled_{false};
    llama_model * model_ = nullptr;
    llama_context * context_ = nullptr;
    std::string path_;
    int context_requested_ = 0;
    int threads_ = 0;
};
}
