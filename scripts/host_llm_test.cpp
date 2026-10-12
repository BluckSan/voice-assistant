// Проверка ядра llamajni.cpp на ПК: g++ -DLLAMA_HOST_TEST ... (см. README)
#include "../app/src/main/cpp/llamajni.cpp"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
int main(int argc, char** argv) {
    // argv: model system_prompt_file user1 [user2 ...]
    auto* s = LlmSession::open(argv[1], 4096, 8);
    if (!s) return 1;
    std::ifstream f(argv[2]); std::stringstream ss; ss << f.rdbuf();
    std::vector<std::string> roles{"system"}, contents{ss.str()};
    for (int i = 3; i < argc; i++) {
        roles.push_back("user"); contents.push_back(argv[i]);
        auto t0 = std::chrono::steady_clock::now();
        int n = 0;
        std::string out = s->generate(s->format(roles, contents), 256, 0.3f, [&](const std::string& p) { n++; fputs(p.c_str(), stdout); fflush(stdout); return true; });
        double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        printf("\n   [%d токенов, %.1f с, кэш %zu]\n", n, sec, s->cached.size());
        roles.push_back("assistant"); contents.push_back(out);
    }
    delete s;
}
