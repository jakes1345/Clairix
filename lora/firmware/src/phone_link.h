// The Bluetooth link to the owner's phone (link.h): one GATT service, one
// characteristic, frames cut into chunks as the phone-to-phone mesh does.
//
// Only a bonded phone gets in. Bonding happens only while the pairing window
// is open: for two minutes after the button is pressed, or whenever no phone
// is bonded yet. Boards with a screen show a six-digit passkey the phone's
// owner types in; the XIAO has no screen, so it bonds without one, which is
// why its window matters.
//
// Outside the window the radio advertises nothing but that it can be
// connected to, from an address that changes (a resolvable private address):
// no name and no Clairix service in the air for a scanner to pick out. The
// bonded phone still finds it, by the identity key exchanged when bonding.
#pragma once

#include <NimBLEDevice.h>

#include <atomic>
#include <deque>
#include <mutex>

#include "clx/bridge.h"

class PhoneLink : public NimBLEServerCallbacks, public NimBLECharacteristicCallbacks {
public:
    static constexpr uint32_t WINDOW_MS = 120000;
    static constexpr uint32_t AUTH_TIMEOUT_MS = 30000;

    /** [passkeyScreen]: the board shows a passkey, so bonding asks for it. */
    void begin(bool passkeyScreen);
    /** Switches the link on or off (the handheld's setting). Off: no advertising, phone dropped. */
    void setEnabled(bool on);
    bool enabled() const { return enabled_; }
    void openWindow(uint64_t now);
    bool windowOpen(uint64_t now) const;
    /** Forgets every bonded phone. */
    void forgetPhones();
    /** The passkey to show while a phone is bonding, 0 otherwise. */
    uint32_t passkey() const { return passkey_.load(); }
    bool connected() const { return trusted_; }
    int bondedPhones() const;

    /** Queues a whole frame for the phone. */
    void send(const clx::Bytes& frame);
    /** One step of the main loop: events, incoming frames, outgoing chunks, advertising. */
    void poll(clx::Bridge& bridge, uint64_t now);

    // NimBLE callbacks, on its own task: they only queue events.
    void onConnect(NimBLEServer* server, ble_gap_conn_desc* desc) override;
    void onDisconnect(NimBLEServer* server, ble_gap_conn_desc* desc) override;
    void onMTUChange(uint16_t mtu, ble_gap_conn_desc* desc) override;
    uint32_t onPassKeyRequest() override;
    void onAuthenticationComplete(ble_gap_conn_desc* desc) override;
    bool onConfirmPIN(uint32_t pin) override;
    void onWrite(NimBLECharacteristic* c, ble_gap_conn_desc* desc) override;

private:
    enum class Kind { Connected, Trusted, Refused, Disconnected, Data };
    struct Event {
        Kind kind;
        uint16_t conn;
        clx::Bytes data;
    };
    void push(Event e);
    void advertise(uint64_t now);

    NimBLEServer* server_ = nullptr;
    NimBLECharacteristic* frames_ = nullptr;
    bool passkeyScreen_ = false;
    bool enabled_ = false;
    std::mutex lock_;
    std::deque<Event> events_;
    std::deque<clx::Bytes> out_;
    std::atomic<uint32_t> passkey_{0};
    std::atomic<uint16_t> mtu_{23};
    clx::Dechunker dechunker_;
    bool connectedLink_ = false;
    bool trusted_ = false;
    uint16_t conn_ = 0;
    uint64_t connectedAt_ = 0;
    uint64_t windowUntil_ = 0;
    int advertising_ = -1;  // -1 stopped, 0 quiet, 1 window, -3 failed
    uint64_t retryAt_ = 0;
    bool privateAddress_ = true;
};
