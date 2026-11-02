#include "phone_link.h"

#include <esp_random.h>

#include "board.h"

void PhoneLink::begin(bool passkeyScreen) {
    passkeyScreen_ = passkeyScreen;
    NimBLEDevice::init("");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);
    NimBLEDevice::setMTU(517);
    NimBLEDevice::setOwnAddrType(BLE_OWN_ADDR_RPA_PUBLIC_DEFAULT);
    NimBLEDevice::setSecurityIOCap(passkeyScreen ? BLE_HS_IO_DISPLAY_ONLY : BLE_HS_IO_NO_INPUT_OUTPUT);
    // Bonding is switched on only while the window is open (advertise()).
    NimBLEDevice::setSecurityAuth(false, passkeyScreen, true);

    server_ = NimBLEDevice::createServer();
    server_->setCallbacks(this, false);
    server_->advertiseOnDisconnect(false);
    NimBLEService* service = server_->createService(clx::LINK_SERVICE_UUID);
    frames_ = service->createCharacteristic(
        clx::LINK_FRAMES_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::NOTIFY,
        512);
    frames_->setCallbacks(this);
    service->start();
    enabled_ = true;
}

int PhoneLink::bondedPhones() const { return NimBLEDevice::getNumBonds(); }

void PhoneLink::setEnabled(bool on) {
    if (on == enabled_) return;
    enabled_ = on;
    if (!on) {
        NimBLEDevice::getAdvertising()->stop();
        advertising_ = -1;
        if (connectedLink_) server_->disconnect(conn_);
    }
}

void PhoneLink::openWindow(uint64_t now) { windowUntil_ = now + WINDOW_MS; }

bool PhoneLink::windowOpen(uint64_t now) const { return now < windowUntil_ || bondedPhones() == 0; }

void PhoneLink::forgetPhones() {
    if (connectedLink_) server_->disconnect(conn_);
    NimBLEDevice::deleteAllBonds();
}

void PhoneLink::push(Event e) {
    std::lock_guard<std::mutex> g(lock_);
    if (events_.size() < 64) events_.push_back(std::move(e));
}

void PhoneLink::onConnect(NimBLEServer*, ble_gap_conn_desc* desc) {
    push({Kind::Connected, desc->conn_handle, {}});
    // Ask for encryption at once: a bonded phone resumes it, a new one bonds or is turned away.
    NimBLEDevice::startSecurity(desc->conn_handle);
}

void PhoneLink::onDisconnect(NimBLEServer*, ble_gap_conn_desc* desc) {
    passkey_ = 0;
    push({Kind::Disconnected, desc->conn_handle, {}});
}

void PhoneLink::onMTUChange(uint16_t mtu, ble_gap_conn_desc*) { mtu_ = mtu; }

uint32_t PhoneLink::onPassKeyRequest() {
    // Shown on the board's screen; the phone's owner types it in.
    const uint32_t key = 100000 + esp_random() % 900000;
    passkey_ = key;
    return key;
}

bool PhoneLink::onConfirmPIN(uint32_t) {
    // Numeric comparison needs a yes on the board, which offers no such prompt.
    return false;
}

void PhoneLink::onAuthenticationComplete(ble_gap_conn_desc* desc) {
    passkey_ = 0;
    const bool ok = desc->sec_state.encrypted && desc->sec_state.bonded &&
                    (!passkeyScreen_ || desc->sec_state.authenticated);
    push({ok ? Kind::Trusted : Kind::Refused, desc->conn_handle, {}});
}

void PhoneLink::onWrite(NimBLECharacteristic* c, ble_gap_conn_desc* desc) {
    NimBLEAttValue v = c->getValue();
    push({Kind::Data, desc->conn_handle, clx::Bytes(v.data(), v.data() + v.length())});
}

void PhoneLink::send(const clx::Bytes& frame) {
    if (!trusted_) return;
    const size_t payload = size_t(mtu_.load()) > 3 ? size_t(mtu_.load()) - 3 : 20;
    for (clx::Bytes& c : clx::chunk(frame, payload < 20 ? 20 : payload)) out_.push_back(std::move(c));
}

void PhoneLink::advertise(uint64_t now) {
    const int want = !enabled_ || connectedLink_ ? -1 : (windowOpen(now) ? 1 : 0);
    if (want == advertising_ || now < retryAt_) return;
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->stop();
    advertising_ = want;
    if (want < 0) return;
    // Bonding only while the window is open; a phone that is not bonded cannot get in otherwise.
    NimBLEDevice::setSecurityAuth(want == 1, passkeyScreen_, true);
    adv->reset();
    if (want == 1) {
        adv->addServiceUUID(clx::LINK_SERVICE_UUID);
        adv->setScanResponse(true);
        adv->setName("Clairix radio");
        adv->setMinInterval(160);  // 100 ms
        adv->setMaxInterval(240);
    } else {
        adv->setScanResponse(false);
        adv->setMinInterval(1600);  // 1 s: a bonded phone reconnects within seconds
        adv->setMaxInterval(2400);
    }
    if (!adv->start()) {
        if (privateAddress_) {
            // A controller that cannot advertise from a private address: fall back to the public one.
            Serial.println("[ble] private address refused, using the public address");
            privateAddress_ = false;
            NimBLEDevice::setOwnAddrType(BLE_OWN_ADDR_PUBLIC);
        }
        advertising_ = -3;  // not what anyone wants: tried again shortly
        retryAt_ = now + 1000;
    }
}

void PhoneLink::poll(clx::Bridge& bridge, uint64_t now) {
    std::deque<Event> events;
    {
        std::lock_guard<std::mutex> g(lock_);
        events.swap(events_);
    }
    for (Event& e : events) {
        switch (e.kind) {
            case Kind::Connected:
                if (connectedLink_ || !enabled_) {
                    server_->disconnect(e.conn);
                    break;
                }
                connectedLink_ = true;
                trusted_ = false;
                conn_ = e.conn;
                connectedAt_ = now;
                dechunker_.reset();
                advertising_ = -1;  // the controller stops advertising on a connection
                break;
            case Kind::Trusted:
                if (connectedLink_ && e.conn == conn_) {
                    trusted_ = true;
                    bridge.phoneConnected();
                    windowUntil_ = 0;  // bonded: no need to keep the door open
                }
                break;
            case Kind::Refused:
                server_->disconnect(e.conn);
                break;
            case Kind::Disconnected:
                if (e.conn == conn_) {
                    if (trusted_) bridge.phoneDisconnected();
                    connectedLink_ = false;
                    trusted_ = false;
                    out_.clear();
                    advertising_ = -1;
                }
                break;
            case Kind::Data: {
                if (!trusted_ || e.conn != conn_) break;
                clx::Bytes frame;
                if (dechunker_.add(e.data.data(), e.data.size(), frame) == clx::Dechunker::Result::Frame)
                    bridge.onPhoneFrame(frame.data(), frame.size(), now);
                break;
            }
        }
    }
    if (connectedLink_ && !trusted_ && now - connectedAt_ > AUTH_TIMEOUT_MS) {
        server_->disconnect(conn_);
        connectedAt_ = now;  // once
    }
    // A few chunks per step keeps the Bluetooth stack's buffers from filling.
    for (int i = 0; i < 6 && !out_.empty() && trusted_; i++) {
        frames_->setValue(out_.front().data(), out_.front().size());
        frames_->notify();
        out_.pop_front();
    }
    advertise(now);
}
