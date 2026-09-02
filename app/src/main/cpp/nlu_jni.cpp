#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"

namespace {
using Clock = std::chrono::steady_clock;
long ms(Clock::time_point t) { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-t).count(); }
struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> cancelled{false};
    std::vector<llama_token> previous_prompt;
    Clock::time_point deadline;
    ~Engine() { if(ctx) llama_free(ctx); if(model) llama_model_free(model); }
};
std::string bytes(JNIEnv *env, jbyteArray a) {
    std::string s(env->GetArrayLength(a), '\0');
    env->GetByteArrayRegion(a, 0, s.size(), reinterpret_cast<jbyte *>(s.data())); return s;
}
void fail(JNIEnv *env, const std::exception &e) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what()); }
bool abort_decode(void *p) { auto *e = static_cast<Engine *>(p); return e->cancelled || Clock::now() > e->deadline; }
void log_callback(ggml_log_level level, const char *text, void *) {
    if(level >= GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, "PhysicalNlu", text);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_local_physicalmemory_nlu_LlamaNluRuntime_nativeLoad(JNIEnv *env, jobject, jbyteArray path, jint threads) {
    try {
        static std::once_flag once;
        std::call_once(once, [] { llama_log_set(log_callback, nullptr); llama_backend_init(); });
        auto e = std::make_unique<Engine>();
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0; mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        e->model = llama_model_load_from_file(bytes(env,path).c_str(), mp);
        if(!e->model) throw std::runtime_error("Cannot load local Qwen3 model");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 512; cp.n_ubatch = 128;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.no_perf = false; cp.offload_kqv = false; cp.op_offload = false;
        cp.abort_callback = abort_decode; cp.abort_callback_data = e.get();
        e->deadline = Clock::time_point::max();
        e->ctx = llama_init_from_model(e->model, cp);
        if(!e->ctx) throw std::runtime_error("Cannot allocate local NLU context");
        return reinterpret_cast<jlong>(e.release());
    } catch(const std::exception &e) { fail(env,e); return 0; }
}

extern "C" JNIEXPORT jobject JNICALL Java_dev_local_physicalmemory_nlu_LlamaNluRuntime_nativeGenerate(JNIEnv *env, jobject, jlong handle, jbyteArray prompt, jbyteArray grammar, jboolean thinking) {
    try {
        auto &e = *reinterpret_cast<Engine *>(handle);
        const auto start = Clock::now(); e.cancelled = false; e.deadline = start + std::chrono::seconds(thinking ? 90 : 60);
        llama_perf_context_reset(e.ctx);
        auto *vocab = llama_model_get_vocab(e.model);
        auto p = bytes(env,prompt); auto g = bytes(env,grammar);
        int n = -llama_tokenize(vocab,p.data(),p.size(),nullptr,0,true,true);
        if(n <= 0 || n > 1400) throw std::runtime_error("NLU prompt exceeds context budget");
        std::vector<llama_token> tokens(n);
        llama_tokenize(vocab,p.data(),p.size(),tokens.data(),n,true,true);
        using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        llama_sampler *constraint;
        if(thinking) {
            const char *patterns[] = {"[\\s\\S]*?</think>\\s*(\\{)"};
            constraint = llama_sampler_init_grammar_lazy_patterns(vocab,g.c_str(),"root",patterns,1,nullptr,0);
        } else constraint = llama_sampler_init_grammar(vocab,g.c_str(),"root");
        if(!constraint) throw std::runtime_error("Invalid NLU grammar");
        llama_sampler_chain_add(sampler.get(),constraint);
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_p(thinking ? .95f : .8f,1));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_temp(thinking ? .6f : .7f));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_dist(42));
        // Reuse only an identical prompt prefix. Remove all prior answer/user suffix tokens.
        int prefix=0;
        while(prefix < n-1 && prefix < static_cast<int>(e.previous_prompt.size()) && tokens[prefix]==e.previous_prompt[prefix]) ++prefix;
        e.previous_prompt.clear(); // an error/abort must invalidate the cache
        if(prefix==0 || !llama_memory_seq_rm(llama_get_memory(e.ctx),0,prefix,-1)) {
            llama_memory_clear(llama_get_memory(e.ctx),true); prefix=0;
        }
        for(int i=prefix; i<n; i+=512) {
            auto batch = llama_batch_get_one(tokens.data()+i, std::min(512,n-i));
            if(llama_decode(e.ctx,batch) != 0) throw std::runtime_error("NLU prefill cancelled or failed");
        }
        long prefill = ms(start), ttft = 0; int generated = 0;
        std::string output; bool complete = false;
        const auto decoding = Clock::now();
        const int max_tokens = std::min(thinking ? 512 : 384, 2048-n);
        for(int i=0; i<max_tokens; ++i) {
            if(abort_decode(&e)) break;
            auto token = llama_sampler_sample(sampler.get(),e.ctx,-1);
            if(i==0) ttft = ms(start);
            if(llama_vocab_is_eog(vocab,token)) { complete = true; break; }
            char buf[512]; int size = llama_token_to_piece(vocab,token,buf,sizeof(buf),0,true);
            if(size < 0) throw std::runtime_error("NLU token exceeds buffer");
            output.append(buf,size); generated++;
            auto batch = llama_batch_get_one(&token,1);
            if(llama_decode(e.ctx,batch) != 0) throw std::runtime_error("NLU decode cancelled or failed");
        }
        if(complete) e.previous_prompt=tokens;
        jlong timing[] = {n,prefill,ttft,ms(decoding),generated,prefix};
        auto data = env->NewByteArray(output.size());
        env->SetByteArrayRegion(data,0,output.size(),reinterpret_cast<const jbyte *>(output.data()));
        auto times = env->NewLongArray(6); env->SetLongArrayRegion(times,0,6,timing);
        auto type = env->FindClass("dev/local/physicalmemory/nlu/NativeOutput");
        return env->NewObject(type,env->GetMethodID(type,"<init>","([B[JZ)V"),data,times,complete);
    } catch(const std::exception &e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_local_physicalmemory_nlu_LlamaNluRuntime_nativeCancel(JNIEnv *, jobject, jlong handle) { reinterpret_cast<Engine *>(handle)->cancelled = true; }
extern "C" JNIEXPORT void JNICALL Java_dev_local_physicalmemory_nlu_LlamaNluRuntime_nativeFree(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<Engine *>(handle); }
