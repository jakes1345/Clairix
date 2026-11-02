#include "clx/handheld.h"

#include <algorithm>
#include <cstring>

#include "clx/crypto.h"

namespace clx {

constexpr uint32_t Messenger::RETRY_MS[];

static constexpr uint8_t STATE_VERSION = 1;
static constexpr size_t REPLAY_MAX = 512;

HandheldState freshState(const std::string& name) {
    HandheldState s;
    s.id = LiteIdentity::generate();
    s.name = name.substr(0, NAME_MAX);
    publicNetworkKey(s.settings.networkKey);
    return s;
}

static void putString(Bytes& b, const std::string& s, size_t max) {
    const std::string t = s.substr(0, max);
    append16(b, uint16_t(t.size()));
    append(b, reinterpret_cast<const uint8_t*>(t.data()), t.size());
}

static bool getString(Reader& r, std::string& s, size_t max) {
    uint16_t n = r.u16();
    if (!r.ok() || n > max || r.remaining() < n) return false;
    s.assign(reinterpret_cast<const char*>(r.here()), n);
    return r.skip(n);
}

Bytes serializeState(const HandheldState& s) {
    Bytes b;
    b.push_back(STATE_VERSION);
    append(b, s.id.secret, 32);
    putString(b, s.name, NAME_MAX);
    b.push_back(s.settings.preset);
    b.push_back(uint8_t(s.settings.txPower));
    b.push_back(s.settings.hopLimit);
    append(b, s.settings.networkKey, 32);
    b.push_back(uint8_t((s.settings.phoneLink ? 1 : 0) | (s.settings.wipeAfterTries ? 2 : 0)));
    b.push_back(uint8_t(s.contacts.size()));
    for (const Contact& c : s.contacts) {
        append(b, c.pub, 32);
        putString(b, c.name, NAME_MAX);
        append32(b, c.addedMinutes);
        append16(b, c.unread);
    }
    append16(b, uint16_t(s.messages.size()));
    for (const StoredMessage& m : s.messages) {
        b.push_back(m.contact);
        b.push_back(uint8_t((m.outgoing ? 1 : 0) | (m.hasPosition ? 2 : 0)));
        append32(b, m.id);
        append32(b, m.minutes);
        b.push_back(uint8_t(m.state));
        b.push_back(uint8_t(m.kind));
        if (m.hasPosition) { append32(b, uint32_t(m.lat7)); append32(b, uint32_t(m.lon7)); }
        putString(b, m.text, LITE_TEXT_MAX);
    }
    return b;
}

bool deserializeState(const Bytes& b, HandheldState& s) {
    Reader r(b.data(), b.size());
    if (r.u8() != STATE_VERSION) return false;
    uint8_t secret[32];
    if (!r.take(secret, 32)) return false;
    s.id = LiteIdentity::fromSecret(secret);
    wipe(secret, 32);
    if (!getString(r, s.name, NAME_MAX)) return false;
    s.settings.preset = r.u8();
    s.settings.txPower = int8_t(r.u8());
    s.settings.hopLimit = r.u8();
    r.take(s.settings.networkKey, 32);
    uint8_t flags = r.u8();
    s.settings.phoneLink = flags & 1;
    s.settings.wipeAfterTries = flags & 2;
    uint8_t nc = r.u8();
    if (!r.ok() || nc > MAX_CONTACTS) return false;
    s.contacts.clear();
    for (uint8_t i = 0; i < nc; i++) {
        Contact c;
        r.take(c.pub, 32);
        if (!getString(r, c.name, NAME_MAX)) return false;
        c.addedMinutes = r.u32();
        c.unread = r.u16();
        s.contacts.push_back(std::move(c));
    }
    uint16_t nm = r.u16();
    if (!r.ok() || nm > MAX_MESSAGES) return false;
    s.messages.clear();
    for (uint16_t i = 0; i < nm; i++) {
        StoredMessage m;
        m.contact = r.u8();
        uint8_t f = r.u8();
        m.outgoing = f & 1;
        m.hasPosition = f & 2;
        m.id = r.u32();
        m.minutes = r.u32();
        uint8_t st = r.u8();
        uint8_t kind = r.u8();
        if (m.hasPosition) { m.lat7 = int32_t(r.u32()); m.lon7 = int32_t(r.u32()); }
        if (!getString(r, m.text, LITE_TEXT_MAX)) return false;
        if (!r.ok() || m.contact >= s.contacts.size() || st > uint8_t(MsgState::Failed) ||
            kind < uint8_t(LiteKind::Text) || kind > uint8_t(LiteKind::Sos)) return false;
        m.state = MsgState(st);
        m.kind = LiteKind(kind);
        // A message still sending when the radio went off is not sending any more.
        if (m.state == MsgState::Sending) m.state = MsgState::Failed;
        s.messages.push_back(std::move(m));
    }
    return r.ok() && r.remaining() == 0;
}

int Messenger::contactIndex(const uint8_t pub[32]) const {
    for (size_t i = 0; i < state_.contacts.size(); i++) {
        if (equalCt(state_.contacts[i].pub, pub, 32)) return int(i);
    }
    return -1;
}

bool Messenger::addContact(const uint8_t pub[32], const std::string& name, uint32_t wallMinutes) {
    if (equalCt(pub, state_.id.pub, 32)) return false;
    int at = contactIndex(pub);
    if (at >= 0) {
        // Paired again: the name may have changed.
        state_.contacts[size_t(at)].name = name.substr(0, NAME_MAX);
        dirty = true;
        return true;
    }
    if (state_.contacts.size() >= MAX_CONTACTS) return false;
    Contact c;
    std::memcpy(c.pub, pub, 32);
    c.name = name.substr(0, NAME_MAX);
    c.addedMinutes = wallMinutes;
    state_.contacts.push_back(std::move(c));
    dirty = true;
    return true;
}

void Messenger::removeContact(size_t index) {
    if (index >= state_.contacts.size()) return;
    state_.contacts.erase(state_.contacts.begin() + long(index));
    auto& ms = state_.messages;
    ms.erase(std::remove_if(ms.begin(), ms.end(), [index](const StoredMessage& m) { return m.contact == index; }), ms.end());
    for (StoredMessage& m : ms) if (m.contact > index) m.contact--;
    pending_.erase(std::remove_if(pending_.begin(), pending_.end(), [index](const Pending& p) { return p.contact == index; }), pending_.end());
    for (Pending& p : pending_) if (p.contact > index) p.contact--;
    dirty = true;
}

void Messenger::markRead(size_t contact) {
    if (contact < state_.contacts.size() && state_.contacts[contact].unread) {
        state_.contacts[contact].unread = 0;
        dirty = true;
    }
}

void Messenger::push(StoredMessage m) {
    state_.messages.push_back(std::move(m));
    while (state_.messages.size() > MAX_MESSAGES) state_.messages.pop_front();
    dirty = true;
}

int Messenger::queue(size_t contact, LiteKind kind, const Bytes& payload, StoredMessage m, uint64_t now, uint32_t wallMinutes) {
    if (contact >= state_.contacts.size()) return -1;
    LiteMessage lm;
    lm.kind = kind;
    uint8_t idb[4];
    randomBytes(idb, 4);
    lm.id = get32(idb);
    lm.sentMinutes = wallMinutes;
    lm.payload = payload;
    Bytes inner, body;
    if (!encodeLiteInner(lm, inner) || !liteSeal(state_.id, state_.contacts[contact].pub, inner, body)) return -1;
    m.contact = uint8_t(contact);
    m.outgoing = true;
    m.id = lm.id;
    m.minutes = wallMinutes;
    m.kind = kind;
    m.state = send_(body, state_.settings.hopLimit) ? MsgState::Sent : MsgState::Sending;
    pending_.push_back({lm.id, uint8_t(contact), inner, 0, now + RETRY_MS[0]});
    push(std::move(m));
    return int(state_.messages.size() - 1);
}

int Messenger::sendText(size_t contact, const std::string& text, uint64_t now, uint32_t wallMinutes) {
    if (text.empty() || text.size() > LITE_TEXT_MAX) return -1;
    StoredMessage m;
    m.text = text;
    return queue(contact, LiteKind::Text, Bytes(text.begin(), text.end()), m, now, wallMinutes);
}

int Messenger::sendSos(size_t contact, bool hasPosition, int32_t lat7, int32_t lon7, const std::string& text,
                       uint64_t now, uint32_t wallMinutes) {
    const size_t room = LITE_TEXT_MAX - (hasPosition ? 9 : 1);
    StoredMessage m;
    m.text = text.substr(0, room);
    m.hasPosition = hasPosition;
    m.lat7 = lat7;
    m.lon7 = lon7;
    return queue(contact, LiteKind::Sos, sosPayload(hasPosition, lat7, lon7, m.text), m, now, wallMinutes);
}

int Messenger::resend(size_t messageIndex, uint64_t now, uint32_t wallMinutes) {
    if (messageIndex >= state_.messages.size()) return -1;
    const StoredMessage old = state_.messages[messageIndex];
    if (!old.outgoing || old.state != MsgState::Failed) return -1;
    if (old.kind == LiteKind::Sos) return sendSos(old.contact, old.hasPosition, old.lat7, old.lon7, old.text, now, wallMinutes);
    return sendText(old.contact, old.text, now, wallMinutes);
}

void Messenger::ack(size_t contact, uint32_t id, uint32_t wallMinutes) {
    LiteMessage lm;
    lm.kind = LiteKind::Ack;
    uint8_t idb[4];
    randomBytes(idb, 4);
    lm.id = get32(idb);
    lm.sentMinutes = wallMinutes;
    append32(lm.payload, id);
    Bytes inner, body;
    if (encodeLiteInner(lm, inner) && liteSeal(state_.id, state_.contacts[contact].pub, inner, body)) {
        send_(body, state_.settings.hopLimit);
    }
}

bool Messenger::replayed(uint64_t key) {
    if (std::find(replay_.begin(), replay_.end(), key) != replay_.end()) return true;
    replay_.push_back(key);
    while (replay_.size() > REPLAY_MAX) replay_.pop_front();
    return false;
}

bool Messenger::onLite(const Bytes& body, uint64_t now, uint32_t wallMinutes) {
    (void)now;
    uint8_t sender[32];
    Bytes inner;
    auto known = [this](const uint8_t pub[32]) { return contactIndex(pub) >= 0; };
    if (!liteOpen(state_.id, body.data(), body.size(), known, sender, inner)) return false;
    const bool replay = replayed(liteReplayKey(body.data(), body.size()));
    LiteMessage lm;
    if (!decodeLiteInner(inner.data(), inner.size(), lm)) return false;
    const size_t contact = size_t(contactIndex(sender));

    if (lm.kind == LiteKind::Ack) {
        if (replay) return false;
        const uint32_t acked = get32(lm.payload.data());
        bool changed = false;
        pending_.erase(std::remove_if(pending_.begin(), pending_.end(),
            [&](const Pending& p) { return p.id == acked && p.contact == contact; }), pending_.end());
        for (StoredMessage& m : state_.messages) {
            if (m.outgoing && m.id == acked && m.contact == contact && m.state != MsgState::Delivered) {
                m.state = MsgState::Delivered;
                changed = true;
            }
        }
        if (changed) dirty = true;
        return changed;
    }

    // Always acknowledge, a copy too: the sender keeps trying until an Ack gets through.
    ack(contact, lm.id, wallMinutes);
    if (replay) return false;
    for (const StoredMessage& m : state_.messages) {
        if (!m.outgoing && m.contact == contact && m.id == lm.id) return false;  // a copy, sealed again after a restart
    }
    StoredMessage m;
    m.contact = uint8_t(contact);
    m.outgoing = false;
    m.id = lm.id;
    m.minutes = lm.sentMinutes ? lm.sentMinutes : wallMinutes;
    m.state = MsgState::Received;
    m.kind = lm.kind;
    if (lm.kind == LiteKind::Sos) {
        if (!parseSos(lm.payload, m.hasPosition, m.lat7, m.lon7, m.text)) return false;
    } else {
        m.text.assign(lm.payload.begin(), lm.payload.end());
    }
    state_.contacts[contact].unread++;
    push(std::move(m));
    if (onIncoming) onIncoming(state_.messages.size() - 1);
    return true;
}

void Messenger::tick(uint64_t now) {
    for (auto it = pending_.begin(); it != pending_.end();) {
        if (now < it->next) { ++it; continue; }
        if (it->tries >= RETRIES) {
            for (StoredMessage& m : state_.messages) {
                if (m.outgoing && m.id == it->id && m.contact == it->contact && m.state != MsgState::Delivered) {
                    m.state = MsgState::Failed;
                }
            }
            dirty = true;
            it = pending_.erase(it);
            continue;
        }
        // Sealed afresh: the same bytes again would be dropped by every radio
        // that heard the first copy, the contact's included, and so never answered.
        Bytes body;
        if (liteSeal(state_.id, state_.contacts[it->contact].pub, it->inner, body)) send_(body, state_.settings.hopLimit);
        // The first copy may not have gone out (a full queue): it has now.
        for (StoredMessage& m : state_.messages) {
            if (m.outgoing && m.id == it->id && m.state == MsgState::Sending) { m.state = MsgState::Sent; dirty = true; }
        }
        it->tries++;
        it->next = now + (it->tries < RETRIES ? RETRY_MS[it->tries] : RETRY_MS[RETRIES - 1]);
        ++it;
    }
}

}  // namespace clx
