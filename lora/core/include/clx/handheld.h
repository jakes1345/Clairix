// The standalone handheld: a radio with its own screen and keyboard (the
// LilyGO T-Deck) that someone with no smartphone, or one they cannot trust,
// carries to talk to their contacts. What it keeps and how its messages are
// sent and acknowledged live here, apart from the screen, so the host tests
// can run two of them over a simulated radio.
//
// A message is sent at once and again after 20 s, 1, 3, 10 and 30 minutes
// until the contact's radio acknowledges it (a Lite Ack, sealed like the
// message). Each repeat is sealed afresh: the same bytes again would be dropped
// unheard by every radio that heard the first copy, the contact's included,
// which then could not answer it. The contact drops the copies by message id
// and answers each one. With no acknowledgement after the last try it is marked failed
// and the owner can send it again.
#pragma once

#include <cstdint>
#include <deque>
#include <functional>
#include <string>
#include <vector>

#include "clx/lite.h"

namespace clx {

constexpr size_t MAX_CONTACTS = 32;
constexpr size_t MAX_MESSAGES = 300;
constexpr size_t CONTACT_NAME_MAX = PAIR_NAME_MAX;

enum class MsgState : uint8_t { Sending = 0, Sent = 1, Delivered = 2, Received = 3, Failed = 4 };

struct Contact {
    uint8_t pub[32]{};
    std::string name;
    uint32_t addedMinutes = 0;
    uint16_t unread = 0;
};

struct StoredMessage {
    uint8_t contact = 0;
    bool outgoing = false;
    uint32_t id = 0;
    uint32_t minutes = 0;  // minutes since 1970; 0 when the clock was unknown
    MsgState state = MsgState::Sending;
    LiteKind kind = LiteKind::Text;
    std::string text;
    bool hasPosition = false;
    int32_t lat7 = 0;
    int32_t lon7 = 0;
};

struct HandheldSettings {
    uint8_t preset = 1;
    int8_t txPower = 22;
    uint8_t hopLimit = 5;
    uint8_t networkKey[32]{};
    /** Lets a phone bond with this handheld and use it as its radio too. */
    bool phoneLink = false;
    /** Wipes everything after WIPE_AFTER wrong PINs in a row. */
    bool wipeAfterTries = true;
};

constexpr uint8_t WIPE_AFTER = 10;

struct HandheldState {
    LiteIdentity id{};
    std::string name;
    HandheldSettings settings;
    std::vector<Contact> contacts;
    std::deque<StoredMessage> messages;
};

/** A new handheld: a fresh identity on the public network. */
HandheldState freshState(const std::string& name);
Bytes serializeState(const HandheldState& s);
bool deserializeState(const Bytes& b, HandheldState& s);

class Messenger {
public:
    using SendLite = std::function<bool(const Bytes& body, uint8_t hopLimit)>;
    static constexpr uint32_t RETRY_MS[] = {20000, 60000, 180000, 600000, 1800000};
    static constexpr size_t RETRIES = sizeof RETRY_MS / sizeof RETRY_MS[0];

    Messenger(HandheldState& state, SendLite send) : state_(state), send_(std::move(send)) {}

    /** Index of the new message in state.messages, or -1 (no such contact, too long, nothing to send). */
    int sendText(size_t contact, const std::string& text, uint64_t now, uint32_t wallMinutes);
    int sendSos(size_t contact, bool hasPosition, int32_t lat7, int32_t lon7, const std::string& text,
                uint64_t now, uint32_t wallMinutes);
    /** Sends a failed message again, as a new message. */
    int resend(size_t messageIndex, uint64_t now, uint32_t wallMinutes);

    /** A Lite body off the air. True when it was for this handheld and changed something. */
    bool onLite(const Bytes& body, uint64_t now, uint32_t wallMinutes);
    void tick(uint64_t now);

    bool addContact(const uint8_t pub[32], const std::string& name, uint32_t wallMinutes);
    void removeContact(size_t index);
    int contactIndex(const uint8_t pub[32]) const;
    void markRead(size_t contact);

    /** Set when something worth saving changed; the caller saves and clears it. */
    bool dirty = false;
    /** Called when a message for the owner arrives: the index in state.messages. */
    std::function<void(size_t)> onIncoming;

private:
    struct Pending {
        uint32_t id;
        uint8_t contact;
        Bytes inner;
        uint8_t tries;
        uint64_t next;
    };

    int queue(size_t contact, LiteKind kind, const Bytes& payload, StoredMessage m, uint64_t now, uint32_t wallMinutes);
    void push(StoredMessage m);
    void ack(size_t contact, uint32_t id, uint32_t wallMinutes);
    bool replayed(uint64_t key);

    HandheldState& state_;
    SendLite send_;
    std::vector<Pending> pending_;
    std::deque<uint64_t> replay_;
};

}  // namespace clx
