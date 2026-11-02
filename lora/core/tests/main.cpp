// Host tests for the Clairix LoRa core. No framework: each TEST is a function,
// CHECK records a failure with its line and carries on.
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <functional>
#include <map>
#include <string>
#include <vector>

#include "clx/bridge.h"
#include "clx/bundle.h"
#include "clx/code.h"
#include "clx/crypto.h"
#include "clx/handheld.h"
#include "clx/link.h"
#include "clx/lite.h"
#include "clx/net.h"
#include "clx/nmea.h"
#include "clx/region.h"
#include "clx/router.h"
#include "clx/vault.h"
#include "known_answers.h"

using namespace clx;

static int failures = 0;
static int checks = 0;
static const char* current = "";

#define CHECK(cond) do { checks++; if (!(cond)) { failures++; std::printf("FAIL %s:%d [%s] %s\n", __FILE__, __LINE__, current, #cond); } } while (0)
#define CHECK_EQ(a, b) do { checks++; auto _a = (a); auto _b = (b); if (!(_a == _b)) { failures++; std::printf("FAIL %s:%d [%s] %s == %s\n", __FILE__, __LINE__, current, #a, #b); } } while (0)

static std::vector<std::pair<const char*, std::function<void()>>>& tests() {
    static std::vector<std::pair<const char*, std::function<void()>>> t;
    return t;
}
struct Register { Register(const char* n, std::function<void()> f) { tests().emplace_back(n, std::move(f)); } };
#define TEST(name) static void name(); static Register reg_##name(#name, name); static void name()

static Bytes fromHex(const char* h) { Bytes b; unhex(h, b); return b; }
static Bytes seq(size_t n, uint8_t start = 0) { Bytes b(n); for (size_t i = 0; i < n; i++) b[i] = uint8_t(start + i); return b; }

// ── Primitives against published test vectors ───────────────────────────

TEST(sha512_matches_fips) {
    uint8_t h[64];
    sha512(h, reinterpret_cast<const uint8_t*>("abc"), 3);
    CHECK_EQ(hex(h, 64), std::string("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                                     "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"));
}

TEST(hmac_sha512_matches_rfc4231) {
    Bytes key(20, 0x0b);
    uint8_t h[64];
    hmacSha512(h, key.data(), key.size(), reinterpret_cast<const uint8_t*>("Hi There"), 8);
    CHECK_EQ(hex(h, 64), std::string("87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde"
                                     "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854"));
}

TEST(x25519_matches_rfc7748) {
    Bytes a = fromHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    Bytes b = fromHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
    uint8_t A[32], B[32], s1[32], s2[32];
    x25519Public(A, a.data());
    x25519Public(B, b.data());
    CHECK_EQ(hex(A, 32), std::string("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"));
    CHECK(x25519(s1, a.data(), B));
    CHECK(x25519(s2, b.data(), A));
    CHECK_EQ(hex(s1, 32), std::string("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"));
    CHECK_EQ(hex(s1, 32), hex(s2, 32));
    uint8_t zero[32] = {0};
    CHECK(!x25519(s1, a.data(), zero));
}

TEST(chacha20_poly1305_matches_rfc8439) {
    Bytes key = fromHex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
    Bytes nonce = fromHex("070000004041424344454647");
    Bytes ad = fromHex("50515253c0c1c2c3c4c5c6c7");
    const char* text = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    Bytes pt(text, text + std::strlen(text));
    Bytes ct(pt.size());
    uint8_t tag[16];
    aeadSeal(ct.data(), tag, key.data(), nonce.data(), ad.data(), ad.size(), pt.data(), pt.size());
    CHECK_EQ(hex(tag, 16), std::string("1ae10b594f09e26a7e902ecbd0600691"));
    CHECK_EQ(hex(ct).substr(0, 32), std::string("d31a8d34648e60db7b86afbc53ef7ec2"));
    Bytes back(ct.size());
    CHECK(aeadOpen(back.data(), tag, key.data(), nonce.data(), ad.data(), ad.size(), ct.data(), ct.size()));
    CHECK(back == pt);
    ct[3] ^= 1;
    CHECK(!aeadOpen(back.data(), tag, key.data(), nonce.data(), ad.data(), ad.size(), ct.data(), ct.size()));
}

TEST(hkdf_sha512_matches_rfc5869_construction) {
    // RFC 5869 has no SHA-512 vector; this is its test case 1 run with SHA-512,
    // as published by the Wycheproof project and reproduced by RustCrypto's hkdf.
    Bytes ikm(22, 0x0b);
    Bytes salt = fromHex("000102030405060708090a0b0c");
    Bytes info = fromHex("f0f1f2f3f4f5f6f7f8f9");
    uint8_t okm[42];
    hkdfSha512(okm, 42, ikm.data(), ikm.size(), salt.data(), salt.size(), info.data(), info.size());
    CHECK_EQ(hex(okm, 42), std::string("832390086cda71fb47625bb5ceb168e4c8e26a1a16ed34d9fc7fe92c1481579338da362cb8d9f925d7cb"));
}

// ── Region ───────────────────────────────────────────────────────────────

TEST(airtime_matches_semtech_calculator) {
    // LoRaWAN's well-known figures: 51 bytes at SF12/125 kHz (2465.8 ms) and SF7/125 kHz (102.7 ms), 8-symbol preamble.
    Preset sf12{"t", 12, 125000, 5}, sf7{"t", 7, 125000, 5};
    CHECK_EQ(airtimeUs(sf12, 51, 8), 2465792u);
    CHECK_EQ(airtimeUs(sf7, 51, 8), 102656u);
    // Every Clairix preset keeps a full frame under two seconds.
    for (uint8_t i = 0; i < PRESET_COUNT; i++) {
        const Preset& p = preset(PresetId(i));
        CHECK_EQ(p.bwHz, 500000u);
        CHECK(airtimeUs(p, FRAME_MAX) < 2000000u);
    }
    CHECK(airtimeUs(preset(PresetId::Balanced), FRAME_MAX) < 600000u);
}

TEST(channels_stay_between_lorawan_uplinks_and_downlinks) {
    for (uint8_t ch = 0; ch < CHANNEL_COUNT; ch++) {
        double f = channelMHz(ch);
        CHECK(f - 0.25 >= 915.0);
        CHECK(f + 0.25 <= 923.0);
    }
    CHECK(std::fabs(channelMHz(0) - 915.5) < 1e-9);
    CHECK(std::fabs(channelMHz(14) - 922.5) < 1e-9);
}

// ── Link layer ───────────────────────────────────────────────────────────

TEST(frames_round_trip_and_reject_tampering) {
    uint8_t key[32];
    publicNetworkKey(key);
    NetKeys k;
    deriveNet(key, k);
    Packet p;
    p.type = PacketType::Lite;
    p.hops = 2;
    p.hopLimit = 3;
    p.body = seq(100);
    Bytes plain = encodePacket(p), frame, back;
    uint8_t nonce[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    CHECK(sealFrame(k, plain.data(), plain.size(), nonce, frame));
    CHECK_EQ(frame.size(), plain.size() + FRAME_OVERHEAD);
    CHECK(openFrame(k, frame.data(), frame.size(), back));
    CHECK(back == plain);
    Packet q;
    CHECK(decodePacket(back.data(), back.size(), q));
    CHECK(q.type == PacketType::Lite && q.hops == 2 && q.hopLimit == 3 && q.body == p.body);

    for (size_t i = 0; i < frame.size(); i++) {
        Bytes bad = frame;
        bad[i] ^= 0x40;
        CHECK(!openFrame(k, bad.data(), bad.size(), back));
    }
    uint8_t other[32];
    randomBytes(other, 32);
    NetKeys k2;
    deriveNet(other, k2);
    CHECK(!openFrame(k2, frame.data(), frame.size(), back));
    Bytes big(PLAIN_MAX + 1, 1);
    CHECK(!sealFrame(k, big.data(), big.size(), nonce, frame));
}

TEST(packet_id_ignores_hops) {
    Packet a, b;
    a.type = b.type = PacketType::Frag;
    a.body = b.body = seq(50);
    a.hops = 0; a.hopLimit = 5;
    b.hops = 3; b.hopLimit = 2;
    CHECK_EQ(packetId(a), packetId(b));
    b.type = PacketType::Lite;
    CHECK(packetId(a) != packetId(b));
}

// ── Codes ────────────────────────────────────────────────────────────────

TEST(network_codes_round_trip_and_catch_typos) {
    uint8_t key[32], back[32];
    randomBytes(key, 32);
    std::string code = networkCode(key);
    CHECK_EQ(code.size(), size_t(55 + 10));
    CHECK(parseNetworkCode(code, back));
    CHECK(std::memcmp(key, back, 32) == 0);
    std::string lower = code;
    for (char& c : lower) c = char(std::tolower(c));
    CHECK(parseNetworkCode(lower, back));
    std::string typo = code;
    typo[7] = typo[7] == 'A' ? 'B' : 'A';
    CHECK(!parseNetworkCode(typo, back));
    CHECK(!parseNetworkCode(code.substr(0, 40), back));
    std::string fp = fingerprint(key);
    CHECK_EQ(fp.size(), size_t(9));
    CHECK_EQ(fp[4], '-');
}

// ── Lite ─────────────────────────────────────────────────────────────────

TEST(lite_seal_open) {
    LiteIdentity a = LiteIdentity::generate(), b = LiteIdentity::generate(), c = LiteIdentity::generate();
    LiteMessage m;
    m.kind = LiteKind::Text;
    m.id = 0x01020300;
    m.sentMinutes = 29000000;
    std::string text = "meet at the north gate";
    m.payload.assign(text.begin(), text.end());
    Bytes inner, body;
    CHECK(encodeLiteInner(m, inner));
    CHECK_EQ(inner.size(), size_t(32));
    CHECK(liteSeal(a, b.pub, inner, body));
    CHECK_EQ(body.size(), inner.size() + LITE_OVERHEAD);

    auto knowsA = [&](const uint8_t p[32]) { return std::memcmp(p, a.pub, 32) == 0; };
    uint8_t sender[32];
    Bytes got;
    CHECK(liteOpen(b, body.data(), body.size(), knowsA, sender, got));
    CHECK(std::memcmp(sender, a.pub, 32) == 0);
    LiteMessage back;
    CHECK(decodeLiteInner(got.data(), got.size(), back));
    CHECK(back.id == m.id && back.sentMinutes == m.sentMinutes && back.payload == m.payload);

    // Not for c; not from a contact of b; not after a flipped bit.
    auto anyone = [](const uint8_t*) { return true; };
    CHECK(!liteOpen(c, body.data(), body.size(), anyone, sender, got));
    auto nobody = [](const uint8_t*) { return false; };
    CHECK(!liteOpen(b, body.data(), body.size(), nobody, sender, got));
    for (size_t i = 0; i < body.size(); i += 7) {
        Bytes bad = body;
        bad[i] ^= 1;
        CHECK(!liteOpen(b, bad.data(), bad.size(), knowsA, sender, got));
    }
    // Two seals of one message differ (fresh ephemeral key).
    Bytes body2;
    CHECK(liteSeal(a, b.pub, inner, body2));
    CHECK(body != body2);
}

TEST(lite_inner_limits_and_padding) {
    LiteMessage m;
    m.payload = Bytes(LITE_TEXT_MAX, 'x');
    Bytes inner;
    CHECK(encodeLiteInner(m, inner));
    CHECK_EQ(inner.size(), LITE_INNER_MAX);
    m.payload.push_back('x');
    CHECK(!encodeLiteInner(m, inner));
    // A payload ending in zero bytes survives the padding.
    m.kind = LiteKind::Ack;
    m.payload = {0, 1, 0, 0};
    CHECK(encodeLiteInner(m, inner));
    LiteMessage back;
    CHECK(decodeLiteInner(inner.data(), inner.size(), back));
    CHECK(back.payload == m.payload);
    inner[inner.size() - 1] = 1;
    CHECK(!decodeLiteInner(inner.data(), inner.size(), back));
    Bytes sos = sosPayload(true, 418781000, -876298000, "hurt, need help");
    bool has; int32_t lat, lon; std::string t;
    CHECK(parseSos(sos, has, lat, lon, t));
    CHECK(has && lat == 418781000 && lon == -876298000 && t == "hurt, need help");
}

// ── Pairing ──────────────────────────────────────────────────────────────

struct PairBench {
    std::vector<Bytes> toA, toB;
    Pairing a{[this](const Bytes& b) { toB.push_back(b); }};
    Pairing b{[this](const Bytes& m) { toA.push_back(m); }};
    void run(uint64_t until, uint64_t step = 100) {
        for (uint64_t t = 0; t <= until; t += step) {
            a.tick(t); b.tick(t);
            auto ta = toA; toA.clear();
            auto tb = toB; toB.clear();
            for (auto& m : ta) a.onBody(m.data(), m.size(), t);
            for (auto& m : tb) b.onBody(m.data(), m.size(), t);
        }
    }
};

TEST(pairing_shows_the_same_digits_on_both_sides) {
    LiteIdentity ia = LiteIdentity::generate(), ib = LiteIdentity::generate();
    PairBench bench;
    bench.a.start(ia, "Ana", 0, 500);
    bench.b.start(ib, "Bo", 0, 2500);
    bench.run(6000);
    CHECK(bench.a.state() == Pairing::State::Matched);
    CHECK(bench.b.state() == Pairing::State::Matched);
    CHECK_EQ(bench.a.digits(), bench.b.digits());
    CHECK(std::memcmp(bench.a.peer(), ib.pub, 32) == 0);
    CHECK(std::memcmp(bench.b.peer(), ia.pub, 32) == 0);
    CHECK_EQ(bench.a.peerName(), std::string("Bo"));
    CHECK_EQ(bench.b.peerName(), std::string("Ana"));
}

TEST(pairing_settles_when_both_commit_at_once) {
    LiteIdentity ia = LiteIdentity::generate(), ib = LiteIdentity::generate();
    PairBench bench;
    bench.a.start(ia, "Ana", 0, 0);
    bench.b.start(ib, "Bo", 0, 0);
    bench.run(10000);
    CHECK(bench.a.state() == Pairing::State::Matched);
    CHECK(bench.b.state() == Pairing::State::Matched);
    CHECK_EQ(bench.a.digits(), bench.b.digits());
}

TEST(pairing_fails_when_the_reveal_does_not_match_the_commitment) {
    LiteIdentity ia = LiteIdentity::generate(), ib = LiteIdentity::generate(), im = LiteIdentity::generate();
    std::vector<Bytes> toB;
    Pairing b([](const Bytes&) {});
    b.start(ib, "Bo", 0, 60000);
    // A commits for its own key...
    uint8_t nA[16];
    randomBytes(nA, 16);
    uint8_t c[32];
    pairCommitment(ia.pub, nA, c);
    Bytes commit;
    commit.push_back(1);
    append32(commit, 77);
    append(commit, c, 32);
    b.onBody(commit.data(), commit.size(), 10);
    CHECK(b.state() == Pairing::State::Answered);
    // ...and someone else reveals a different key.
    Bytes reveal;
    reveal.push_back(3);
    append32(reveal, 77);
    append(reveal, im.pub, 32);
    append(reveal, nA, 16);
    reveal.push_back(0);
    b.onBody(reveal.data(), reveal.size(), 20);
    CHECK(b.state() == Pairing::State::Failed);
}

TEST(pairing_times_out) {
    Pairing p([](const Bytes&) {});
    p.start(LiteIdentity::generate(), "x", 0, 100);
    p.tick(PAIR_TIMEOUT_MS + 1);
    CHECK(p.state() == Pairing::State::Failed);
}

// ── Bundles ──────────────────────────────────────────────────────────────

static Bundle testBundle(size_t envelope) {
    Bundle b;
    for (int i = 0; i < 16; i++) b.tag[i] = uint8_t(0xA0 + i);
    b.hops = 2;
    b.createdMs = 1793000000000ull / 3600000 * 3600000;
    b.envelope = seq(envelope, 7);
    return b;
}

TEST(bundles_fragment_and_reassemble_in_any_order) {
    Bundle b = testBundle(1100);
    std::vector<Bytes> frags = fragment(b);
    CHECK_EQ(frags.size(), size_t(5));
    for (auto& f : frags) CHECK(f.size() <= BODY_MAX);
    Reassembler r;
    Bundle out;
    const size_t order[] = {3, 0, 4, 1, 2};
    for (size_t i = 0; i < 5; i++) {
        Frag f;
        CHECK(parseFrag(frags[order[i]], f));
        auto res = r.add(f, 1000 + i, out);
        CHECK(res == (i == 4 ? Reassembler::Result::Complete : Reassembler::Result::Partial));
    }
    CHECK(out.envelope == b.envelope);
    CHECK(std::memcmp(out.tag, b.tag, 16) == 0);
    CHECK_EQ(out.hops, b.hops);
    CHECK_EQ(out.createdMs, b.createdMs);
    CHECK_EQ(r.partials(), size_t(0));
    CHECK(fragment(testBundle(MAX_LORA_ENVELOPE + 1)).empty());
    CHECK_EQ(fragment(testBundle(MAX_LORA_ENVELOPE)).size(), MAX_FRAGS);
}

TEST(a_forged_fragment_spoils_nothing_but_its_bundle) {
    Bundle b = testBundle(500);
    std::vector<Bytes> frags = fragment(b);
    frags[1][20] ^= 0xFF;
    Reassembler r;
    Bundle out;
    Reassembler::Result last = Reassembler::Result::Partial;
    for (auto& fb : frags) { Frag f; CHECK(parseFrag(fb, f)); last = r.add(f, 5, out); }
    CHECK(last == Reassembler::Result::Bad);
}

TEST(stalled_bundles_ask_for_what_they_miss) {
    Bundle b = testBundle(700);
    std::vector<Bytes> frags = fragment(b);
    Reassembler r;
    Bundle out;
    Frag f;
    parseFrag(frags[0], f); r.add(f, 0, out);
    parseFrag(frags[2], f); r.add(f, 0, out);
    CHECK(r.dueWants(1000).empty());
    auto w = r.dueWants(Reassembler::WANT_AFTER_MS + 1);
    CHECK_EQ(w.size(), size_t(1));
    CHECK_EQ(w[0].second, uint16_t(0b1010));
    CHECK(r.dueWants(Reassembler::WANT_AFTER_MS + 2).empty());
    auto have = r.fragmentsFor(w[0].first, 0b0101);
    CHECK_EQ(have.size(), size_t(2));
    uint64_t bid; uint16_t missing;
    Bytes want = encodeWant(w[0].first, 0b1010);
    CHECK(parseWant(want, bid, missing));
    CHECK(bid == w[0].first && missing == 0b1010);
}

TEST(store_keeps_bundles_for_the_phone_and_resends_its_own) {
    BundleStore s(3);
    Bundle mine = testBundle(300), heard = testBundle(301);
    CHECK(s.add(mine, true, 0));
    CHECK(!s.add(mine, true, 5));
    CHECK(s.add(heard, false, 0));
    auto forPhone = s.forPhone();
    CHECK_EQ(forPhone.size(), size_t(1));
    s.phoneHas(forPhone[0]->bid);
    CHECK(s.forPhone().empty());
    CHECK(s.dueRetries(60000).empty());
    CHECK_EQ(s.dueRetries(2 * 60000).size(), size_t(1));
    CHECK(s.dueRetries(2 * 60000 + 1).empty());
    CHECK_EQ(s.dueRetries(15 * 60000).size(), size_t(1));
    s.expire(15 * 60000);
    CHECK_EQ(s.size(), size_t(1));  // the heard one is done with; ours has retries left
}

// ── Router: a simulated line of radios ───────────────────────────────────

struct Node : Router::Listener {
    Router router{*this};
    BundleStore store{32};
    std::vector<Bundle> bundles;
    std::vector<Bytes> lites;
    Node() { router.setLookup([this](uint64_t bid) -> const Bundle* { auto e = store.get(bid); return e ? &e->bundle : nullptr; }); }
    void onBundle(const Bundle& b, uint64_t) override { bundles.push_back(b); store.add(b, false, 0); }
    void onLite(const Bytes& body, const RxInfo&) override { lites.push_back(body); }
    void onPair(const Bytes&, const RxInfo&) override {}
};

/** Gives every node the same true clock, as radios with phones or GPS have. */
static void syncClocks(std::vector<Node>& nodes) {
    for (auto& n : nodes) n.router.setClock(1793000000000ull, CLOCK_TRUE, 0);
}

/** Radios hear only those `inRange` says. A frame occupies the air for its airtime; a receiver
 *  loses a frame when it was itself transmitting or another in-range frame overlapped it. */
struct Air {
    std::vector<Node*> nodes;
    std::function<bool(size_t, size_t)> inRange;
    struct Flight { size_t from; Bytes frame; uint64_t start, end; bool delivered; int channel; bool longPreamble; };
    std::vector<Flight> flights;
    double loss = 0;
    uint32_t lossSeed = 1;
    bool lost() { lossSeed = lossSeed * 1103515245u + 12345u; return double((lossSeed >> 8) & 0xFFFF) / 65536.0 < loss; }
    bool scanCatch() { lossSeed = lossSeed * 1103515245u + 12345u; return ((lossSeed >> 8) % 5) < 2; }
    static bool overlap(const Flight& a, const Flight& b) { return a.start < b.end && b.start < a.end; }

    uint64_t clock = 0;
    /** Runs the air until [until] ms, carrying on from where the last run stopped. */
    void run(uint64_t until, uint64_t step = 5) {
        for (uint64_t& t = clock; t <= until; t += step) {
            for (auto& f : flights) {
                if (f.delivered || f.end > t) continue;
                f.delivered = true;
                nodes[f.from]->router.txDone(uint32_t((f.end - f.start) * 1000), t);
                for (size_t j = 0; j < nodes.size(); j++) {
                    if (j == f.from || !inRange(f.from, j)) continue;
                    bool lostHere = false;
                    for (auto& o : flights) {
                        if (&o == &f || !overlap(o, f)) continue;
                        if (o.from == j || inRange(o.from, j)) lostHere = true;
                    }
                    // On another channel it is not heard; a radio still scanning for the network
                    // sweeps all channels by activity detection and catches about two frames in five.
                    const int ch = nodes[j]->router.channel(f.start);
                    if (ch >= 0 && ch != f.channel) continue;
                    if (ch < 0 && !f.longPreamble && !scanCatch()) continue;
                    if (lostHere || lost()) continue;
                    nodes[j]->router.onFrame(f.frame.data(), f.frame.size(), RxInfo{-90, float(int(j % 7) - 5)}, t);
                }
            }
            // Forget flights that can no longer overlap anything new.
            flights.erase(std::remove_if(flights.begin(), flights.end(), [&](const Flight& f) { return f.delivered && f.end + 5000 < t; }), flights.end());
            for (size_t i = 0; i < nodes.size(); i++) {
                Router& r = nodes[i]->router;
                if (t % 1000 == 0) r.tick(t);
                Bytes frame;
                if (!r.popTx(t, frame)) continue;
                // Channel activity detection: busy if anything in range is on the air now.
                bool busy = false;
                for (auto& f : flights) if (f.start <= t && t < f.end && (f.from == i || inRange(f.from, i))) busy = true;
                if (busy) { r.busy(t); continue; }
                uint32_t air = airtimeUs(preset(r.config().preset), frame.size(), r.preambleSymbols());
                flights.push_back({i, frame, t, t + air / 1000 + 1, false, r.channel(t), r.preambleSymbols() > PREAMBLE_SYMBOLS});
            }
        }
    }
};

TEST(a_bundle_crosses_five_radios_in_a_line) {
    std::vector<Node> nodes(6);
    Air air;
    for (auto& n : nodes) air.nodes.push_back(&n);
    air.inRange = [](size_t a, size_t b) { return (a > b ? a - b : b - a) == 1; };
    syncClocks(nodes);
    Bundle b = testBundle(1100);
    CHECK(nodes[0].router.sendBundle(b, 0));
    // Usually done in seconds; hidden-terminal collisions can make the far end ask for gaps.
    air.run(300000);
    CHECK_EQ(nodes[5].bundles.size(), size_t(1));
    if (!nodes[5].bundles.empty()) CHECK(nodes[5].bundles[0].envelope == b.envelope);
    for (size_t i = 1; i < 5; i++) CHECK_EQ(nodes[i].bundles.size(), size_t(1));
    CHECK(nodes[0].bundles.empty());  // its own, not delivered back to it
    if (getenv("CLX_DEBUG")) for (auto& n : nodes) { auto& s = n.router.stats(); std::printf("heard %u dup %u sent %u relayed %u supp %u dropped %u bundles %u partial %zu\n", s.heard, s.duplicates, s.sent, s.relayed, s.suppressed, s.dropped, s.bundles, n.router.partialBundles()); }
}

TEST(hop_limit_stops_a_flood) {
    std::vector<Node> nodes(5);
    Air air;
    for (auto& n : nodes) air.nodes.push_back(&n);
    air.inRange = [](size_t a, size_t b) { return (a > b ? a - b : b - a) == 1; };
    syncClocks(nodes);
    Bytes lite = seq(120);
    CHECK(nodes[0].router.sendLite(lite, 2, 0));
    air.run(30000);
    CHECK_EQ(nodes[1].lites.size(), size_t(1));
    CHECK_EQ(nodes[2].lites.size(), size_t(1));
    CHECK_EQ(nodes[3].lites.size(), size_t(1));
    CHECK(nodes[4].lites.empty());
}

TEST(a_crowd_suppresses_redundant_relays) {
    // Ten radios all in range of each other: one sender, and far fewer than nine relays.
    std::vector<Node> nodes(10);
    Air air;
    for (auto& n : nodes) air.nodes.push_back(&n);
    air.inRange = [](size_t, size_t) { return true; };
    syncClocks(nodes);
    CHECK(nodes[0].router.sendLite(seq(60), 5, 0));
    air.run(30000);
    uint32_t relayed = 0, suppressed = 0;
    for (auto& n : nodes) { relayed += n.router.stats().relayed; suppressed += n.router.stats().suppressed; CHECK(&n == &nodes[0] || n.lites.size() == 1); }
    CHECK(relayed < 9);
    CHECK(suppressed > 0);
}

TEST(lost_fragments_are_asked_for_and_filled_in) {
    std::vector<Node> nodes(2);
    Air air;
    for (auto& n : nodes) air.nodes.push_back(&n);
    air.inRange = [](size_t, size_t) { return true; };
    syncClocks(nodes);
    air.loss = 0.35;
    Bundle b = testBundle(1100);
    nodes[0].store.add(b, true, 0);
    CHECK(nodes[0].router.sendBundle(b, 0));
    // Usually done in seconds; hidden-terminal collisions can make the far end ask for gaps.
    air.run(300000);
    CHECK_EQ(nodes[1].bundles.size(), size_t(1));
}

TEST(airtime_budget_holds_back_own_packets) {
    Node n;
    Router::Config c;
    c.budgetPermille = 10;  // 1 % of ten minutes: 6 s
    c.preset = PresetId::Long;
    n.router.setConfig(c);
    n.router.setClock(1793000000000ull + 100000, CLOCK_TRUE, 0);  // clear of a channel change
    for (int i = 0; i < 8; i++) CHECK(n.router.sendLite(seq(200, uint8_t(i)), 3, 0));
    uint64_t t = 0;
    int sent = 0;
    Bytes frame;
    while (t < 60000) {
        if (n.router.popTx(t, frame)) { n.router.txDone(airtimeUs(preset(c.preset), frame.size()), t); sent++; }
        t += 100;
    }
    CHECK(sent >= 3 && sent <= 4);  // about 1.7 s each at SF12
    CHECK(n.router.airtimeInWindow(t) <= 6000000u);
}

TEST(other_networks_are_not_heard) {
    Node a, b;
    uint8_t key[32];
    randomBytes(key, 32);
    a.router.setNetwork(key);
    a.router.sendLite(seq(40), 3, 0);
    Bytes frame;
    CHECK(a.router.popTx(1000, frame));
    b.router.onFrame(frame.data(), frame.size(), RxInfo{}, 1020);
    CHECK(b.lites.empty());
    CHECK_EQ(b.router.stats().foreign, 1u);
}


// ── Hard to link, hard to follow ─────────────────────────────────────────

TEST(frames_share_nothing_in_the_clear) {
    uint8_t key[32];
    publicNetworkKey(key);
    NetKeys k;
    deriveNet(key, k);
    Bytes plain = seq(40), a, b;
    std::map<uint8_t, int> hints;
    for (int i = 0; i < 400; i++) {
        uint8_t nonce[8];
        makeNonce(1000, CLOCK_TRUE, nonce);
        CHECK(sealFrame(k, plain.data(), plain.size(), nonce, a));
        hints[a[0]]++;
        Bytes back;
        CHECK(openFrame(k, a.data(), a.size(), back));
    }
    CHECK(hints.size() > 150);  // 400 draws over 256 values: a fixed hint would give 1
    // The same packet sealed twice: only the 4-byte clock agrees.
    uint8_t n1[8], n2[8];
    makeNonce(1000, CLOCK_TRUE, n1);
    makeNonce(1000, CLOCK_TRUE, n2);
    sealFrame(k, plain.data(), plain.size(), n1, a);
    sealFrame(k, plain.data(), plain.size(), n2, b);
    int same = 0;
    for (size_t i = 9; i < a.size(); i++) same += a[i] == b[i];
    CHECK(same < 10);
    // The clock is under the tag: a forged time does not open.
    a[2] ^= 1;
    Bytes back;
    CHECK(!openFrame(k, a.data(), a.size(), back));
}

TEST(networks_hop_channels_on_a_schedule) {
    uint8_t key[32];
    publicNetworkKey(key);
    NetKeys k;
    deriveNet(key, k);
    std::map<uint8_t, int> used;
    for (uint32_t slot = 0; slot < 3000; slot++) used[hopChannel(k, slot)]++;
    CHECK_EQ(used.size(), size_t(CHANNEL_COUNT));
    for (auto& u : used) CHECK(u.second > 120 && u.second < 280);
    uint8_t other[32];
    randomBytes(other, 32);
    NetKeys k2;
    deriveNet(other, k2);
    int agree = 0;
    for (uint32_t slot = 0; slot < 300; slot++) agree += hopChannel(k, slot) == hopChannel(k2, slot);
    CHECK(agree < 50);

    Node a;
    const uint64_t unixMs = (NET_EPOCH_UNIX_S + 600ull * 1000 + 100) * 1000;  // 100 s into slot 1000
    a.router.setClock(unixMs, CLOCK_TRUE, 0);
    CHECK_EQ(a.router.netSeconds(0), 600000u + 100u);
    CHECK_EQ(a.router.channel(0), int(hopChannel(a.router.net(), 1000)));
    CHECK_EQ(a.router.channel(500000), int(hopChannel(a.router.net(), 1001)));
    CHECK(!a.router.inGuard(0));
    CHECK(a.router.inGuard(490000));   // 590 s into the slot
    CHECK(a.router.inGuard(505000));   // 5 s into the next
    CHECK(!a.router.inGuard(530000));
    a.router.sendLite(seq(30), 3, 490000);
    Bytes frame;
    CHECK(!a.router.popTx(495000, frame));
    CHECK(a.router.popTx(525000, frame));
}

TEST(a_radio_without_a_clock_takes_one_from_what_it_hears) {
    Node a, b, c;
    const uint64_t unixMs = 1793000000000ull;
    a.router.setClock(unixMs, CLOCK_TRUE, 0);
    CHECK_EQ(b.router.channel(0), -1);
    a.router.sendLite(seq(30), 3, 0);
    Bytes frame;
    CHECK(a.router.popTx(1000, frame));
    a.router.txDone(100000, 1100);
    b.router.onFrame(frame.data(), frame.size(), RxInfo{}, 1500);
    CHECK_EQ(int(b.router.clockQuality()), int(CLOCK_HEARD));
    const int64_t skew = int64_t(b.router.netSeconds(1500)) - int64_t(a.router.netSeconds(1500));
    CHECK(skew >= -1 && skew <= 2);
    CHECK_EQ(b.router.channel(1500), a.router.channel(1500));
    // A made-up clock gives way to a heard one, and a heard one never to a made-up one.
    c.router.sendLite(seq(31), 3, 0);
    CHECK(c.router.popTx(1000, frame));
    c.router.txDone(100000, 1100);
    CHECK_EQ(int(c.router.clockQuality()), int(CLOCK_GUESS));
    Bytes guessed = frame;
    b.router.onFrame(guessed.data(), guessed.size(), RxInfo{}, 2000);
    CHECK_EQ(int(b.router.clockQuality()), int(CLOCK_HEARD));
    CHECK_EQ(b.router.channel(2000), a.router.channel(2000));
    a.router.sendLite(seq(32), 3, 3000);
    CHECK(a.router.popTx(4000, frame));
    c.router.onFrame(frame.data(), frame.size(), RxInfo{}, 4500);
    CHECK_EQ(int(c.router.clockQuality()), int(CLOCK_HEARD));
    CHECK_EQ(c.router.channel(4500), a.router.channel(4500));
}

TEST(clockless_radios_find_each_other_and_follow_the_hops) {
    // No phone, no GPS anywhere. The first to send makes up a time and, its clock being a guess,
    // sends long preambles; the others, sweeping all channels, catch the first frame, take its
    // time, and from then on all of them hop together.
    std::vector<Node> nodes(4);
    Air air;
    for (auto& n : nodes) air.nodes.push_back(&n);
    air.inRange = [](size_t a, size_t b) { return (a > b ? a - b : b - a) == 1; };
    CHECK(nodes[0].router.sendLite(seq(50), 5, 0));
    air.run(20000);
    for (size_t i = 1; i < 4; i++) {
        CHECK_EQ(nodes[i].lites.size(), size_t(1));
        CHECK(nodes[i].router.clockQuality() == CLOCK_GUESS);
        CHECK_EQ(nodes[i].router.channel(air.clock), nodes[0].router.channel(air.clock));
    }
    // Through two channel changes, every message gets across first time.
    for (int round = 0; round < 4; round++) {
        size_t before = nodes[0].lites.size();
        CHECK(nodes[3].router.sendLite(seq(60, uint8_t(round)), 5, air.clock));
        air.run(air.clock + 300000);
        CHECK_EQ(nodes[0].lites.size(), before + 1);
    }
}

// ── Phone link ───────────────────────────────────────────────────────────

TEST(link_chunks_match_the_phone_mesh_format) {
    Bytes frame = seq(600);
    auto chunks = chunk(frame, 182);
    CHECK_EQ(chunks.size(), size_t(4));
    CHECK_EQ(chunks[0][0], 1);
    CHECK_EQ(get32(&chunks[0][1]), 600u);
    CHECK_EQ(chunks[0].size(), size_t(182));
    Dechunker d;
    Bytes out;
    Dechunker::Result r = Dechunker::Result::More;
    for (auto& c : chunks) r = d.add(c.data(), c.size(), out);
    CHECK(r == Dechunker::Result::Frame);
    CHECK(out == frame);
    uint8_t stray[] = {0, 1, 2};
    CHECK(d.add(stray, 3, out) == Dechunker::Result::Bad);
}

TEST(link_frames_round_trip) {
    RadioInfo i;
    i.model = BoardModel::TDeck;
    i.firmware = "1.0.0";
    i.txPower = 20;
    i.channel = 7;
    i.batteryMv = 3900;
    i.heard = 12345;
    i.standalone = true;
    i.publicNetwork = false;
    Bytes e = encodeInfo(i);
    RadioInfo back;
    CHECK(decodeInfo(e.data(), e.size(), back));
    CHECK(back.model == BoardModel::TDeck && back.firmware == "1.0.0" && back.txPower == 20 && back.channel == 7);
    CHECK(back.batteryMv == 3900 && back.heard == 12345 && back.standalone && !back.publicNetwork);

    RadioConfig c;
    c.preset = 2; c.txPower = 14; c.hopLimit = 4; c.nowMs = 1793000000123ull;
    randomBytes(c.networkKey, 32);
    Bytes ce = encodeConfig(c);
    RadioConfig cb;
    CHECK(decodeConfig(ce.data(), ce.size(), cb));
    CHECK(cb.preset == 2 && cb.txPower == 14 && cb.hopLimit == 4 && cb.nowMs == c.nowMs);
    CHECK(std::memcmp(cb.networkKey, c.networkKey, 32) == 0);

    Bundle b = testBundle(900);
    Bytes out = encodeBundleOut(b, BUNDLE_OWN);
    Reader r(out.data() + 2, out.size() - 2);
    Bundle got;
    CHECK(readLinkBundle(r, got));
    CHECK(got.envelope == b.envelope && got.createdMs == b.createdMs && got.hops == b.hops);
}

// ── Vault and handheld ───────────────────────────────────────────────────

TEST(vault_opens_with_the_pin_only) {
    std::vector<uint8_t> work(64 * 1024);
    Bytes plain = seq(3000), sealed, back;
    VaultCost cost{64, 2};
    CHECK(vaultSeal("4821", plain, cost, work.data(), sealed));
    CHECK(vaultOpen("4821", sealed, work.data(), back));
    CHECK(back == plain);
    CHECK(!vaultOpen("4822", sealed, work.data(), back));
    sealed[12] ^= 1;  // the salt
    CHECK(!vaultOpen("4821", sealed, work.data(), back));
    sealed[12] ^= 1;

    // Unlocked once, saved many times with the kept key; the PIN still opens each save.
    VaultKey key;
    CHECK(vaultOpenKey("4821", sealed, work.data(), key, back));
    Bytes later = seq(500, 7), resealed, again;
    vaultSealKey(key, later, resealed);
    Bytes resealed2;
    vaultSealKey(key, later, resealed2);
    CHECK(resealed != resealed2);  // a fresh nonce each time
    CHECK(vaultOpen("4821", resealed, work.data(), again) && again == later);
    CHECK(!vaultOpenKey("0000", resealed, work.data(), key, again));
}

TEST(handheld_state_round_trips) {
    HandheldState s = freshState("Ana");
    Messenger m(s, [](const Bytes&, uint8_t) { return true; });
    LiteIdentity other = LiteIdentity::generate();
    CHECK(m.addContact(other.pub, "Bo", 100));
    CHECK(m.sendText(0, "hello", 0, 101) == 0);
    CHECK(m.sendSos(0, true, 1, -2, "help", 0, 102) == 1);
    Bytes ser = serializeState(s);
    HandheldState t;
    CHECK(deserializeState(ser, t));
    CHECK(std::memcmp(t.id.pub, s.id.pub, 32) == 0);
    CHECK(t.name == "Ana" && t.contacts.size() == 1 && t.contacts[0].name == "Bo");
    CHECK(t.messages.size() == 2 && t.messages[1].kind == LiteKind::Sos && t.messages[1].lat7 == 1 && t.messages[1].lon7 == -2);
    ser.push_back(0);
    CHECK(!deserializeState(ser, t));
}

struct Handset : Router::Listener {
    HandheldState state;
    Router router{*this};
    Messenger messenger;
    uint64_t now = 0;
    explicit Handset(const std::string& name)
        : state(freshState(name)), messenger(state, [this](const Bytes& b, uint8_t hl) { return router.sendLite(b, hl, now); }) {}
    void onBundle(const Bundle&, uint64_t) override {}
    void onLite(const Bytes& body, const RxInfo&) override { messenger.onLite(body, now, 0); }
    void onPair(const Bytes&, const RxInfo&) override {}
};

TEST(two_handhelds_message_each_other_through_a_relay) {
    Handset a("Ana"), b("Bo");
    Node relay;
    a.messenger.addContact(b.state.id.pub, "Bo", 0);
    b.messenger.addContact(a.state.id.pub, "Ana", 0);
    // a -- relay -- b: the two handhelds are out of each other's range.
    struct Station { Router* r; Handset* h; };
    std::vector<Station> st = {{&a.router, &a}, {&relay.router, nullptr}, {&b.router, &b}};
    auto inRange = [](size_t x, size_t y) { return (x > y ? x - y : y - x) == 1; };
    CHECK(a.messenger.sendText(0, "on my way", 0, 0) == 0);
    for (uint64_t t = 0; t < 120000; t += 10) {
        a.now = b.now = t;
        for (size_t i = 0; i < st.size(); i++) {
            st[i].r->tick(t);
            if (st[i].h) st[i].h->messenger.tick(t);
            Bytes frame;
            if (!st[i].r->popTx(t, frame)) continue;
            st[i].r->txDone(airtimeUs(preset(PresetId::Balanced), frame.size()), t);
            for (size_t j = 0; j < st.size(); j++) if (j != i && inRange(i, j)) st[j].r->onFrame(frame.data(), frame.size(), RxInfo{}, t);
        }
    }
    CHECK_EQ(b.state.messages.size(), size_t(1));
    if (!b.state.messages.empty()) {
        CHECK_EQ(b.state.messages[0].text, std::string("on my way"));
        CHECK(!b.state.messages[0].outgoing);
    }
    CHECK(a.state.messages[0].state == MsgState::Delivered);
    CHECK_EQ(b.state.contacts[0].unread, 1);
}

TEST(an_unacknowledged_message_fails_after_its_last_try) {
    HandheldState s = freshState("Ana");
    int sends = 0;
    Messenger m(s, [&](const Bytes&, uint8_t) { sends++; return true; });
    LiteIdentity other = LiteIdentity::generate();
    m.addContact(other.pub, "Bo", 0);
    m.sendText(0, "anyone?", 0, 0);
    for (uint64_t t = 0; t < 3ull * 3600 * 1000; t += 1000) m.tick(t);
    CHECK_EQ(sends, 6);
    CHECK(s.messages[0].state == MsgState::Failed);
    CHECK(m.resend(0, 0, 0) == 1);
}


// ── The board's bridge between the air and its phone ─────────────────────

struct Phone {
    std::vector<Bytes> frames;
    template <class T> std::vector<Bytes> of(LinkType t) const {
        std::vector<Bytes> out;
        for (auto& f : frames) if (!f.empty() && f[0] == uint8_t(t)) out.push_back(f);
        return out;
    }
};

/** Two boards in range of each other, run until [until]. */
static void runPair(Bridge& a, Bridge& b, uint64_t from, uint64_t until) {
    Bridge* side[2] = {&a, &b};
    for (uint64_t t = from; t < until; t += 5) {
        for (int i = 0; i < 2; i++) {
            if (t % 1000 == 0) side[i]->tick(t);
            Bytes frame;
            if (!side[i]->router().popTx(t, frame)) continue;
            side[i]->router().txDone(airtimeUs(preset(side[i]->router().config().preset), frame.size(), side[i]->router().preambleSymbols()), t);
            side[1 - i]->onRadioFrame(frame.data(), frame.size(), RxInfo{-80, 6.25f}, t);
        }
    }
}

TEST(a_board_holds_what_it_hears_until_its_phone_says_hello) {
    Bridge a, b;
    Phone pa, pb;
    a.toPhone = [&](const Bytes& f) { pa.frames.push_back(f); };
    b.toPhone = [&](const Bytes& f) { pb.frames.push_back(f); };
    RadioConfig saved;
    int saves = 0;
    a.onConfig = [&](const RadioConfig& c) { saved = c; saves++; };

    // A's phone connects, sets the time and the public network, and sends a bundle and a Lite body.
    a.phoneConnected();
    a.onPhoneFrame(encodeHello().data(), 2, 0);
    CHECK_EQ(pa.of<int>(LinkType::Info).size(), size_t(1));
    RadioConfig c;
    c.preset = 1; c.txPower = 30; c.hopLimit = 9; c.nowMs = 1793000000000ull;
    publicNetworkKey(c.networkKey);
    Bytes cf = encodeConfig(c);
    a.onPhoneFrame(cf.data(), cf.size(), 0);
    CHECK_EQ(saves, 1);
    CHECK_EQ(int(saved.txPower), int(MAX_TX_DBM));  // clamped to what the radio may do
    CHECK_EQ(int(saved.hopLimit), int(MAX_HOP_LIMIT));
    CHECK_EQ(int(a.router().clockQuality()), int(CLOCK_TRUE));
    b.router().setClock(1793000000000ull, CLOCK_TRUE, 0);

    Bundle bundle = testBundle(700);
    Bytes out = encodeBundleOut(bundle, BUNDLE_OWN);
    a.onPhoneFrame(out.data(), out.size(), 0);
    a.onPhoneFrame(out.data(), out.size(), 0);  // a repeat is not sent twice
    Bytes lite = seq(120, 9);
    Bytes lo = encodeLiteOut(lite, 3);
    a.onPhoneFrame(lo.data(), lo.size(), 0);
    runPair(a, b, 0, 60000);
    CHECK(a.store().forPhone().empty());  // its own phone wrote it
    CHECK(pb.frames.empty());             // B's phone is not connected

    // B's phone connects: nothing until Hello, then Info, the bundle and the Lite body.
    b.phoneConnected();
    CHECK(pb.frames.empty());
    b.onPhoneFrame(encodeHello().data(), 2, 60000);
    auto info = pb.of<int>(LinkType::Info);
    auto bundles = pb.of<int>(LinkType::BundleIn);
    auto lites = pb.of<int>(LinkType::LiteIn);
    CHECK_EQ(info.size(), size_t(1));
    CHECK_EQ(bundles.size(), size_t(1));
    CHECK_EQ(lites.size(), size_t(1));
    RadioInfo bi;
    CHECK(!info.empty() && decodeInfo(info[0].data(), info[0].size(), bi));
    CHECK(bi.publicNetwork && bi.heard > 0 && bi.channel < CHANNEL_COUNT);
    uint64_t bid = 0;
    Bundle got;
    CHECK(!bundles.empty() && decodeBundleIn(bundles[0].data(), bundles[0].size(), bid, got));
    CHECK(got.envelope == bundle.envelope && bid == bundleId(bundle));
    Bytes body;
    int16_t rssi = 0;
    float snr = 0;
    CHECK(!lites.empty() && decodeHeard(LinkType::LiteIn, lites[0].data(), lites[0].size(), body, rssi, snr));
    CHECK(body == lite && rssi == -80 && snr == 6.25f);

    // Got: the bundle is not offered again after the next Hello.
    Bytes g = encodeGot(bid);
    b.onPhoneFrame(g.data(), g.size(), 60000);
    pb.frames.clear();
    b.phoneDisconnected();
    b.phoneConnected();
    b.onPhoneFrame(encodeHello().data(), 2, 61000);
    CHECK(pb.of<int>(LinkType::BundleIn).empty());
    CHECK(pb.of<int>(LinkType::LiteIn).empty());

    // A disconnected phone's frames are not acted on.
    a.phoneDisconnected();
    size_t queued = a.router().queued();
    a.onPhoneFrame(lo.data(), lo.size(), 61000);
    CHECK_EQ(a.router().queued(), queued);
}

TEST(a_board_on_another_network_hears_nothing) {
    Bridge a, b;
    Phone pb;
    b.toPhone = [&](const Bytes& f) { pb.frames.push_back(f); };
    uint8_t key[32];
    randomBytes(key, 32);
    a.setNetwork(key);
    a.router().setClock(1793000000000ull, CLOCK_TRUE, 0);
    b.router().setClock(1793000000000ull, CLOCK_TRUE, 0);
    b.phoneConnected();
    b.onPhoneFrame(encodeHello().data(), 2, 0);
    pb.frames.clear();
    a.phoneConnected();
    Bytes lo = encodeLiteOut(seq(60), 2);
    a.onPhoneFrame(lo.data(), lo.size(), 0);
    a.onPhoneFrame(encodeHello().data(), 2, 0);
    a.onPhoneFrame(lo.data(), lo.size(), 0);
    runPair(a, b, 0, 20000);
    CHECK(pb.frames.empty());
    CHECK(b.router().stats().heard == 0);
}

TEST(link_frames_for_lite_pair_and_hello_round_trip) {
    Bytes body = seq(155);
    uint8_t hops = 0;
    Bytes back;
    Bytes lo = encodeLiteOut(body, 4);
    CHECK(decodeLiteOut(lo.data(), lo.size(), hops, back) && hops == 4 && back == body);
    Bytes po = encodePairOut(body);
    CHECK(decodePairOut(po.data(), po.size(), back) && back == body);
    Bytes big = encodeLiteOut(seq(BODY_MAX + 1), 4);
    CHECK(!decodeLiteOut(big.data(), big.size(), hops, back));
    uint8_t v = 0;
    CHECK(decodeHello(encodeHello().data(), 2, v) && v == LINK_VERSION);
    Bytes pi = encodePairIn(body, -120, -7.5f);
    int16_t rssi;
    float snr;
    CHECK(decodeHeard(LinkType::PairIn, pi.data(), pi.size(), back, rssi, snr) && back == body && rssi == -120 && snr == -7.5f);
    CHECK(!decodeHeard(LinkType::LiteIn, pi.data(), pi.size(), back, rssi, snr));
    uint64_t bid = 0;
    CHECK(decodeGot(encodeGot(0x0102030405060708ull).data(), 9, bid) && bid == 0x0102030405060708ull);
}

// ── GPS ──────────────────────────────────────────────────────────────────

static bool feedAll(NmeaReader& r, const char* s, GpsFix& f) {
    bool got = false;
    for (const char* p = s; *p; p++) got = r.feed(*p, f) || got;
    return got;
}

TEST(gps_time_and_position_come_from_valid_rmc_sentences) {
    NmeaReader r;
    GpsFix f;
    CHECK(feedAll(r, "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A\r\n", f));
    CHECK_EQ(f.unixMs, 764426119000ull);
    CHECK_EQ(f.lat7, 481173000);
    CHECK_EQ(f.lon7, 115166667);
    CHECK(feedAll(r, "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n$GNRMC,021530.00,A,4038.12345,N,07402.54321,W,0.02,,021126,,,A*43\r\n", f));
    CHECK_EQ(f.unixMs, 1793585730000ull);
    CHECK_EQ(f.lat7, 406353908);
    CHECK_EQ(f.lon7, -740423868);
    // No fix yet, a bad checksum, a cut-off line: nothing.
    CHECK(!feedAll(r, "$GPRMC,123519,V,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*7D\r\n", f));
    CHECK(!feedAll(r, "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6B\r\n", f));
    CHECK(!feedAll(r, "$GPRMC,123519,A,4807.0$GPRMC\r\n", f));
    CHECK_EQ(daysFromCivil(1970, 1, 1), int64_t(0));
    CHECK_EQ(daysFromCivil(2000, 3, 1), int64_t(11017));
}

// ── Known answers, shared with comms-crypto's tests (src/lora.rs) ────────

TEST(known_answers_for_the_android_side) {
    uint8_t pub[32];
    publicNetworkKey(pub);
    CHECK_EQ(hex(pub, 32), std::string(KA_PUBLIC_KEY));
    NetKeys k;
    deriveNet(pub, k);
    CHECK_EQ(hex(k.id, 8), std::string(KA_PUBLIC_ID));
    CHECK_EQ(int(hopChannel(k, 0)), KA_PUBLIC_CHANNEL);
    uint8_t n[8] = {0, 0, 0x30, 0x39, 0xC1, 2, 3, 4};
    CHECK_EQ(int(frameHint(k, n)), KA_PUBLIC_HINT);

    Bytes sa = seq(32, 1), sb = seq(32, 101), se = seq(32, 201);
    LiteIdentity a = LiteIdentity::fromSecret(sa.data()), b = LiteIdentity::fromSecret(sb.data());
    LiteMessage m;
    m.kind = LiteKind::Text;
    m.id = 0x11223344;
    m.sentMinutes = 29883000;
    std::string text = "Clairix Lite";
    m.payload.assign(text.begin(), text.end());
    Bytes inner, body;
    CHECK(encodeLiteInner(m, inner));
    CHECK(liteSeal(a, b.pub, inner, body, se.data()));
    CHECK_EQ(hex(body), std::string(KA_LITE_BODY));
    CHECK_EQ(fingerprint(a.pub), std::string(KA_FINGERPRINT));
    Bytes nA = seq(16, 50), nB = seq(16, 70);
    CHECK_EQ(pairDigits(a.pub, b.pub, nA.data(), nB.data()), uint32_t(KA_PAIR_DIGITS));
    uint8_t c[32];
    pairCommitment(a.pub, nA.data(), c);
    CHECK_EQ(hex(c, 32), std::string(KA_PAIR_COMMIT));
    CHECK_EQ(networkCode(pub), std::string(KA_PUBLIC_CODE));
}

TEST(link_frames_match_the_known_answers_the_phone_checks) {
    RadioInfo i;
    i.model = BoardModel::TDeck;
    i.firmware = "1.0.0";
    i.preset = 2;
    i.txPower = 20;
    i.hopLimit = 4;
    i.channel = CHANNEL_SCANNING;
    for (int k = 0; k < 8; k++) i.networkId[k] = uint8_t(0xA0 + k);
    i.publicNetwork = false;
    i.standalone = true;
    i.batteryMv = 3987;
    i.stored = 7;
    i.heard = 123456;
    i.sent = 42;
    i.relayed = 99;
    i.airtimePermille = 13;
    CHECK_EQ(hex(encodeInfo(i)), std::string(KA_LINK_INFO));
    RadioConfig c;
    c.preset = 0;
    c.txPower = 14;
    c.hopLimit = 3;
    for (int k = 0; k < 32; k++) c.networkKey[k] = uint8_t(k);
    c.nowMs = 1793577600123ull;
    CHECK_EQ(hex(encodeConfig(c)), std::string(KA_LINK_CONFIG));
    Bundle b;
    for (int k = 0; k < 16; k++) b.tag[k] = uint8_t(0x10 + k);
    b.hops = 2;
    b.createdMs = 1793574000000ull;
    b.envelope = {0xde, 0xad, 0xbe, 0xef, 0x01};
    CHECK_EQ(hex(encodeBundleOut(b, BUNDLE_OWN)), std::string(KA_LINK_BUNDLE_OUT));
    CHECK_EQ(hex(encodeBundleIn(0x0102030405060708ull, b)), std::string(KA_LINK_BUNDLE_IN));
    CHECK_EQ(hex(encodeGot(0x0102030405060708ull)), std::string(KA_LINK_GOT));
    CHECK_EQ(hex(encodeLiteOut(Bytes{1, 2, 3}, 5)), std::string(KA_LINK_LITE_OUT));
    CHECK_EQ(hex(encodeLiteIn(Bytes{9, 8, 7}, -117, -7.25f)), std::string(KA_LINK_LITE_IN));
    CHECK_EQ(hex(encodePairOut(Bytes{4, 5})), std::string(KA_LINK_PAIR_OUT));
    CHECK_EQ(hex(encodePairIn(Bytes{6}, -40, 9.5f)), std::string(KA_LINK_PAIR_IN));
    CHECK_EQ(hex(encodeHello()), std::string(KA_LINK_HELLO));
}

int main(int argc, char** argv) {
    const char* only = argc > 1 ? argv[1] : nullptr;
    for (auto& t : tests()) {
        if (only && std::strcmp(only, t.first) != 0) continue;
        current = t.first;
        int before = failures;
        t.second();
        std::printf("%s %s\n", failures == before ? "ok  " : "FAIL", t.first);
    }
    std::printf("%d checks, %d failed\n", checks, failures);
    return failures ? 1 : 0;
}
