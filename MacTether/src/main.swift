import AppKit
import Foundation

// MARK: - Config

let dexRemotePath = "/data/local/tmp/tether.dex"

enum ToggleKind {
    case usb      // phone shares its connection with this computer (type 1)
    case lan      // phone shares via a USB-Ethernet adapter (type 5)
    case hotspot  // Wi-Fi hotspot (type 0)
    case wifi     // the phone's own Wi-Fi (svc wifi — not tethering)

    var helperType: String? {
        switch self {
        case .usb: return "1"     // TetheringManager.TETHERING_USB
        case .lan: return "5"     // TetheringManager.TETHERING_ETHERNET
        case .hotspot: return "0" // TetheringManager.TETHERING_WIFI
        case .wifi: return nil
        }
    }
    var label: String {
        switch self {
        case .usb: return "USB"
        case .lan: return "LAN"
        case .hotspot: return "Hotspot"
        case .wifi: return "Wi-Fi"
        }
    }
    /// "USB tethering" / "Wi-Fi" — for status and alert messages.
    var noun: String {
        switch self {
        case .wifi: return "Wi-Fi"
        default: return "\(label) tethering"
        }
    }
}

// MARK: - adb helpers

func findAdb() -> String? {
    // Prefer the adb on PATH (the one the user and scripts use) — mixing
    // different adb versions kills/restarts the adb server mid-operation and
    // makes the device flap in and out during verification.
    if let fromPath = ProcessInfo.processInfo.environment["PATH"] {
        for dir in fromPath.split(separator: ":").map(String.init) {
            let p = (dir as NSString).appendingPathComponent("adb")
            if FileManager.default.isExecutableFile(atPath: p) { return p }
        }
    }
    let candidates = [
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
        "/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
        (NSHomeDirectory() as NSString).appendingPathComponent("Library/Android/sdk/platform-tools/adb"),
    ]
    if let sdkRoot = ProcessInfo.processInfo.environment["ANDROID_SDK_ROOT"], !sdkRoot.isEmpty {
        let p = (sdkRoot as NSString).appendingPathComponent("platform-tools/adb")
        if FileManager.default.isExecutableFile(atPath: p) { return p }
    }
    for p in candidates where FileManager.default.isExecutableFile(atPath: p) { return p }
    return nil
}

@discardableResult
func runCmd(_ launchPath: String, _ args: [String], timeout: TimeInterval = 30) -> (Int32, String) {
    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: launchPath)
    proc.arguments = args
    let pipe = Pipe()
    proc.standardOutput = pipe
    proc.standardError = pipe
    do { try proc.run() } catch { return (-1, "failed to launch \(launchPath): \(error)") }
    // Wait via terminationHandler (reliable child-death notification) — NOT a
    // worker thread blocked in waitUntilExit(): that pattern leaked a thread +
    // pipe FDs forever whenever the exit notification was missed, and after
    // hours of adb hiccups the process accumulated ~2.5k pipes / 54 stuck
    // threads and could no longer spawn adb at all.
    let done = DispatchSemaphore(value: 0)
    proc.terminationHandler = { _ in done.signal() }
    if done.wait(timeout: .now() + timeout) == .timedOut {
        proc.terminate() // SIGTERM…
        if done.wait(timeout: .now() + 2) == .timedOut {
            kill(proc.processIdentifier, SIGKILL) // …then force — always reaps
            _ = done.wait(timeout: .now() + 2)
        }
        proc.terminationHandler = nil
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        try? pipe.fileHandleForReading.close()
        try? pipe.fileHandleForWriting.close()
        return (-1, "timed out after \(Int(timeout))s: \(launchPath) \(args.joined(separator: " "))")
    }
    proc.terminationHandler = nil
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    try? pipe.fileHandleForReading.close()
    try? pipe.fileHandleForWriting.close()
    return (proc.terminationStatus, String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "")
}

func findLocalDex() -> String? {
    if let url = Bundle.main.url(forResource: "tether", withExtension: "dex") {
        return url.path
    }
    let exeDir = (CommandLine.arguments[0] as NSString).deletingLastPathComponent
    let fallbacks = [
        (exeDir as NSString).appendingPathComponent("../Resources/tether.dex"),
        (exeDir as NSString).appendingPathComponent("../../../res/tether.dex"),
        (exeDir as NSString).appendingPathComponent("../../../../app/src/main/assets/tether.dex"),
    ]
    for p in fallbacks.map({ ($0 as NSString).standardizingPath }) {
        if FileManager.default.fileExists(atPath: p) { return p }
    }
    return nil
}

func waitForDevice(adb: String, timeout: TimeInterval = 15) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        let (code, out) = runCmd(adb, ["get-state"], timeout: 5)
        if code == 0 && out == "device" { return true }
        Thread.sleep(forTimeInterval: 0.5)
    }
    return false
}

func ensureHelperOnDevice(adb: String) -> String? {
    let (code, _) = runCmd(adb, ["shell", "[ -f \(dexRemotePath) ]"], timeout: 10)
    if code == 0 { return nil }
    guard let local = findLocalDex() else { return "helper dex not found in app bundle" }
    let (pushCode, pushOut) = runCmd(adb, ["push", local, dexRemotePath], timeout: 20)
    if pushCode != 0 { return "adb push failed: \(pushOut)" }
    return nil
}

// Poll until the state matches, tolerating the gap where adb drops out (USB
// reconfigures ~3-5s after a USB start/stop command).
func pollState(adb: String, kind: ToggleKind, wantOn: Bool, timeout: TimeInterval = 25) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if waitForDevice(adb: adb, timeout: 6) {
            if let state = currentState(adb: adb, kind: kind), state == wantOn { return true }
        }
        Thread.sleep(forTimeInterval: 2)
    }
    return false
}

// Interface prefixes that are never the Ethernet adapter: loopback, Wi-Fi /
// Wi-Fi-direct / SoftAP, cellular, tunnels, and the phone's OWN usb-tether
// gadget (ncm/rndis). Mirrors TetherService on the phone.
let internalIfacePrefixes = ["lo", "wlan", "p2p", "aware", "nan",
                             "ap", "swlan", "softap",
                             "rmnet", "ccmni", "umts", "ncm", "rndis",
                             "dummy", "sit", "gre", "tunl", "tun",
                             "ip6", "ip_vti", "erspan", "ifb", "radiotap", "bond"]

/// Interface names currently in TetheredState (strict "<iface> - TetheredState"
/// lines only — skips dumpsys event-log lines). Single adb round-trip.
/// nil = the query failed (transient adb hiccup) — callers keep last state.
func fetchTetheredIfaces(adb: String) -> [String]? {
    // Filter on-device: full `dumpsys tethering` is ~180KB (would block the
    // capture pipe), the grep reduces it to a few lines.
    let (code, out) = runCmd(adb, ["shell", "dumpsys tethering | grep TetheredState"], timeout: 15)
    guard code == 0 else { return nil }
    var ifaces: [String] = []
    for line in out.split(separator: "\n") {
        let t = line.trimmingCharacters(in: .whitespaces)
        // Strict state-line shape: "<iface> - TetheredState …". This skips the
        // dumpsys event-log lines ("… - [ncm0] TetheredState got …"), whose
        // leading field is a timestamp, not an interface name.
        let tok = t.split(separator: " ")
        guard tok.count >= 3 && tok[1] == "-" && tok[2].hasPrefix("TetheredState") else { continue }
        ifaces.append(String(tok[0]))
    }
    return ifaces
}

func kindMatches(iface: String, kind: ToggleKind) -> Bool {
    switch kind {
    case .usb:
        // The phone's USB-tether gadget (ncm/rndis/usb).
        return iface.hasPrefix("ncm") || iface.hasPrefix("rndis") || iface.hasPrefix("usb")
    case .lan:
        // Any tethered interface that isn't Wi-Fi/cellular/USB-gadget —
        // i.e. the attached Ethernet adapter (eth0/usb0/enx…).
        return !iface.isEmpty && !internalIfacePrefixes.contains(where: { iface.hasPrefix($0) })
    case .hotspot:
        // SoftAP interface: wlan1 (Pixel), ap0 (Qualcomm), swlan0 (Samsung).
        return iface.hasPrefix("wlan") || iface.hasPrefix("ap") || iface.hasPrefix("swlan") || iface.hasPrefix("softap")
    case .wifi:
        return false // Wi-Fi STA is not a tethered interface — see isWifiOn.
    }
}

/// Wi-Fi STA state from Settings.Global (0=off, 1=on, 2=on-in-airplane-mode).
/// nil = the query failed.
func readWifiOn(adb: String) -> Bool? {
    let (code, out) = runCmd(adb, ["shell", "settings", "get", "global", "wifi_on"], timeout: 10)
    guard code == 0 else { return nil }
    let v = out.trimmingCharacters(in: .whitespacesAndNewlines)
    return v == "1" || v == "2"
}

/// Ground-truth state for any toggle kind (nil = query failed — keep polling).
func currentState(adb: String, kind: ToggleKind) -> Bool? {
    switch kind {
    case .wifi: return readWifiOn(adb: adb)
    default:
        guard let ifaces = fetchTetheredIfaces(adb: adb) else { return nil }
        return ifaces.contains(where: { kindMatches(iface: $0, kind: kind) })
    }
}

enum TetherError: Error {
    case message(String)
}

// Runs the helper (or `svc wifi`) and verifies the resulting state, tolerating
// the expected quirk: enabling USB tethering reconfigures USB and drops its own
// adb connection (exit 255, no STARTED line) even on success — so ground truth
// is the polled state, not the command output.
//
// Note: LAN tethering needs the adapter on the phone's USB port, so over USB
// adb this requires a hub (or Wi-Fi adb); otherwise the phone is unreachable
// and this throws.
func setTethering(_ kind: ToggleKind, on: Bool) throws -> String {
    guard let adb = findAdb() else {
        throw TetherError.message("adb not found. Install Android platform-tools (brew install android-platform-tools).")
    }
    guard waitForDevice(adb: adb) else {
        throw TetherError.message("No phone found over adb. Plug it in via USB with USB debugging enabled.")
    }

    if kind == .wifi {
        // svc wifi runs as shell (uid 2000) — no helper dex needed.
        let (_, out) = runCmd(adb, ["shell", "svc", "wifi", on ? "enable" : "disable"], timeout: 15)
        if out.localizedCaseInsensitiveContains("exception") || out.localizedCaseInsensitiveContains("error") {
            throw TetherError.message("svc wifi failed: \(out)")
        }
        if pollState(adb: adb, kind: .wifi, wantOn: on, timeout: 15) {
            return on ? "Wi-Fi is ON." : "Wi-Fi is OFF."
        }
        throw TetherError.message(on
            ? "Could not confirm Wi-Fi turned on. Unlock the phone and try again."
            : "Wi-Fi still shows as on. Try again.")
    }

    if let err = ensureHelperOnDevice(adb: adb) { throw TetherError.message(err) }
    let action = on ? "start" : "stop"
    let (_, out) = runCmd(adb, ["shell", "CLASSPATH=\(dexRemotePath) app_process /system/bin Main \(kind.helperType!) \(action)"], timeout: 30)
    // error=18 (DUPLICATE_REQUEST) fires both for an already-live USB tether and
    // for a stale/wedged request from an earlier attempt that never brought the
    // interface up. Either way the state is already decided, so check briefly
    // rather than waiting out the full poll before trying the reset below.
    let duplicate = on && kind == .usb && out.contains("error=18")
    // Start/stop can drop adb for a few seconds — poll the ground truth until
    // it settles instead of checking once.
    if pollState(adb: adb, kind: kind, wantOn: on, timeout: duplicate ? 8 : 25) {
        return on ? "\(kind.noun) is ON." : "\(kind.noun) is OFF."
    }
    // Duplicate request with no live interface → wedged; a fresh start keeps
    // bouncing off it. Clear it once with stop→start, same fix the on-phone
    // AutoTether app applies. Unlike the phone (local adb), our adb runs over
    // the very USB link the stop reconfigures, so wait for it to come back
    // before sending the start.
    if duplicate {
        runCmd(adb, ["shell", "CLASSPATH=\(dexRemotePath) app_process /system/bin Main \(kind.helperType!) stop"], timeout: 30)
        _ = waitForDevice(adb: adb, timeout: 10)
        runCmd(adb, ["shell", "CLASSPATH=\(dexRemotePath) app_process /system/bin Main \(kind.helperType!) start"], timeout: 30)
        if pollState(adb: adb, kind: kind, wantOn: on) {
            return "\(kind.noun) is ON."
        }
    }
    if on {
        throw TetherError.message("Could not confirm \(kind.noun) turned on. Unlock the phone and try again.")
    } else {
        throw TetherError.message("\(kind.noun) still shows as active. Try again.")
    }
}

// MARK: - CLI mode (also used for testing)

func runCLI(_ args: [String]) -> Int32 {
    if args.contains("--print-states") {
        let unknown = "usb=unknown lan=unknown hotspot=unknown wifi=unknown"
        guard let adb = findAdb() else { print("\(unknown) (no adb)"); return 1 }
        guard waitForDevice(adb: adb, timeout: 10) else { print("\(unknown) (no device)"); return 1 }
        guard let ifaces = fetchTetheredIfaces(adb: adb), let wifi = readWifiOn(adb: adb) else {
            print("\(unknown) (query failed)")
            return 1
        }
        let usb = ifaces.contains(where: { kindMatches(iface: $0, kind: .usb) })
        let lan = ifaces.contains(where: { kindMatches(iface: $0, kind: .lan) })
        let hotspot = ifaces.contains(where: { kindMatches(iface: $0, kind: .hotspot) })
        print("usb=\(usb ? "on" : "off") lan=\(lan ? "on" : "off") hotspot=\(hotspot ? "on" : "off") wifi=\(wifi ? "on" : "off")")
        return 0
    }
    let kind: ToggleKind?
    let on: Bool?
    if args.contains("--on") { kind = .usb; on = true }
    else if args.contains("--off") { kind = .usb; on = false }
    else if args.contains("--lan-on") { kind = .lan; on = true }
    else if args.contains("--lan-off") { kind = .lan; on = false }
    else if args.contains("--hotspot-on") { kind = .hotspot; on = true }
    else if args.contains("--hotspot-off") { kind = .hotspot; on = false }
    else if args.contains("--wifi-on") { kind = .wifi; on = true }
    else if args.contains("--wifi-off") { kind = .wifi; on = false }
    else {
        print("Usage: MacTether --on | --off | --lan-on | --lan-off | --hotspot-on | --hotspot-off | --wifi-on | --wifi-off | --print-states")
        return 2
    }
    do {
        print(try setTethering(kind!, on: on!))
        return 0
    } catch TetherError.message(let m) {
        fputs("Error: \(m)\n", stderr)
        return 1
    } catch {
        fputs("Error: \(error)\n", stderr)
        return 1
    }
}

// MARK: - Menu bar app

final class AppDelegate: NSObject, NSApplicationDelegate {
    var statusItem: NSStatusItem!
    var usbItem: NSMenuItem!
    var lanItem: NSMenuItem!
    var hotspotItem: NSMenuItem!
    var wifiItem: NSMenuItem!
    var autoItem: NSMenuItem!
    var statusLine: NSMenuItem!

    /// Last known on/off state per toggle (empty = unknown / phone absent).
    var knownStates: [ToggleKind: Bool] = [:]

    var toggleItems: [NSMenuItem] {
        [usbItem, lanItem, hotspotItem, wifiItem]
    }

    let autoKey = "autoEnableUsbOnReconnect"
    let opLock = NSLock()
    var opBusy = false

    var autoEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: autoKey) == nil { return true }
            return UserDefaults.standard.bool(forKey: autoKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: autoKey) }
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            if let img = NSImage(systemSymbolName: "cable.connector.horizontal", accessibilityDescription: "USB tethering") {
                img.isTemplate = true
                button.image = img
            } else {
                button.title = "Tether"
            }
        }
        let menu = NSMenu()
        usbItem = NSMenuItem(title: "USB Tethering", action: #selector(toggleUsb), keyEquivalent: "")
        usbItem.image = assetIcon("usb", fallback: "iphone")
        lanItem = NSMenuItem(title: "LAN Tethering", action: #selector(toggleLan), keyEquivalent: "")
        lanItem.image = assetIcon("lan", fallback: "cable.connector.horizontal")
        hotspotItem = NSMenuItem(title: "Hotspot", action: #selector(toggleHotspot), keyEquivalent: "")
        hotspotItem.image = assetIcon("hotspot", fallback: "wifi.circle")
        wifiItem = NSMenuItem(title: "Wi-Fi", action: #selector(toggleWifi), keyEquivalent: "")
        wifiItem.image = assetIcon("wifi", fallback: "wifi")
        for item in toggleItems { item.target = self }
        autoItem = NSMenuItem(title: "Auto-enable USB on reconnect", action: #selector(toggleAuto), keyEquivalent: "")
        autoItem.target = self
        autoItem.image = menuIcon("bolt.circle")
        autoItem.state = autoEnabled ? .on : .off
        statusLine = NSMenuItem(title: "Ready", action: nil, keyEquivalent: "")
        statusLine.isEnabled = false
        menu.addItem(usbItem)
        menu.addItem(lanItem)
        menu.addItem(hotspotItem)
        menu.addItem(wifiItem)
        menu.addItem(.separator())
        menu.addItem(autoItem)
        menu.addItem(.separator())
        menu.addItem(statusLine)
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        quit.image = menuIcon("power")
        menu.addItem(quit)
        statusItem.menu = menu
        startWatcher()
    }

    /// Template SF Symbol for a menu item (nil when the symbol is unavailable
    /// on this macOS version — the item then simply shows no icon).
    func menuIcon(_ name: String) -> NSImage? {
        guard let img = NSImage(systemSymbolName: name, accessibilityDescription: name) else { return nil }
        img.isTemplate = true
        return img
    }

    /// Bundled image asset as a menu icon (template rendering via the alpha
    /// channel, so it follows light/dark mode). Falls back to an SF Symbol.
    func assetIcon(_ resource: String, fallback: String) -> NSImage? {
        if let url = Bundle.main.url(forResource: resource, withExtension: "png"),
           let img = NSImage(contentsOf: url) {
            img.isTemplate = true
            img.size = NSSize(width: 16, height: 16)
            return img
        }
        return menuIcon(fallback)
    }

    func setBusy(_ busy: Bool, note: String) {
        DispatchQueue.main.async {
            for item in self.toggleItems { item.isEnabled = !busy }
            self.statusLine.title = note
        }
    }

    func setNote(_ note: String) {
        DispatchQueue.main.async { self.statusLine.title = note }
    }

    func tryBeginOp() -> Bool {
        opLock.lock(); defer { opLock.unlock() }
        if opBusy { return false }
        opBusy = true
        return true
    }

    func endOp() {
        opLock.lock(); defer { opLock.unlock() }
        opBusy = false
    }

    // Click flips the last known state (unknown → try turning on; if the
    // phone is absent the action fails with a clear alert).
    @objc func toggleUsb() { toggleKind(.usb) }
    @objc func toggleLan() { toggleKind(.lan) }
    @objc func toggleHotspot() { toggleKind(.hotspot) }
    @objc func toggleWifi() { toggleKind(.wifi) }

    func toggleKind(_ kind: ToggleKind) {
        runAction(kind: kind, on: !(knownStates[kind] ?? false))
    }

    @objc func toggleAuto() {
        autoEnabled = !autoEnabled
        autoItem.state = autoEnabled ? .on : .off
        setNote(autoEnabled ? "Auto-enable on reconnect: on." : "Auto-enable on reconnect: off.")
    }

    func runAction(kind: ToggleKind, on: Bool) {
        guard tryBeginOp() else {
            watchLog("manual: \(kind.label) \(on ? "on" : "off") ignored — another action is running")
            setNote("Busy — try again in a moment.")
            return
        }
        watchLog("manual: \(kind.label) \(on ? "on" : "off") requested")
        setBusy(true, note: on ? "Turning \(kind.noun) on…" : "Turning \(kind.noun) off…")
        DispatchQueue.global().async {
            defer { self.endOp() }
            defer { self.refreshMenuStates() }
            do {
                let msg = try setTethering(kind, on: on)
                self.watchLog("manual result: \(msg)")
                self.setBusy(false, note: msg)
            } catch TetherError.message(let m) {
                self.watchLog("manual failed: \(m)")
                self.setBusy(false, note: m)
                DispatchQueue.main.async {
                    let alert = NSAlert()
                    alert.messageText = "\(kind.noun) failed"
                    alert.informativeText = m
                    alert.addButton(withTitle: "OK")
                    alert.runModal()
                }
            } catch {
                self.setBusy(false, note: "\(error)")
            }
        }
    }

    // MARK: - Reconnect watcher

    /// Append-only debug log so watcher actions can be verified later.
    func watchLog(_ msg: String) {
        let line = "\(ISO8601DateFormatter().string(from: Date())) \(msg)\n"
        guard let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return }
        let f = dir.appendingPathComponent("mactether-watch.log")
        if let h = try? FileHandle(forWritingTo: f) {
            h.seekToEndOfFile()
            h.write(line.data(using: .utf8) ?? Data())
            try? h.close()
        } else {
            try? line.write(to: f, atomically: true, encoding: .utf8)
        }
    }

    /// True when at least one device is connected in "device" state.
    func phonePresent() -> Bool {
        guard let adb = findAdb() else { return false }
        let (code, out) = runCmd(adb, ["devices"], timeout: 10)
        guard code == 0 else { return false }
        for line in out.split(separator: "\n").dropFirst() {
            let cols = line.split(whereSeparator: { $0 == "\t" || $0 == " " })
            if cols.count >= 2 && cols[1] == "device" { return true }
        }
        return false
    }

    /// Fires USB-enable on absent→present transitions only: no action at launch
    /// when the phone is already connected, and a manual Off while connected
    /// is never overridden (no new transition).
    func startWatcher() {
        Thread.detachNewThread { [weak self] in
            guard let self = self else { return }
            var lastSeen = self.phonePresent()
            self.watchLog("watcher started (present=\(lastSeen))")
            var lastStateCheck = Date.distantPast
            var misses = 0
            if lastSeen { self.refreshMenuStates() }
            while true {
                Thread.sleep(forTimeInterval: 3)
                let raw = self.phonePresent()
                // Debounce: a single failed/empty poll (adb hiccup, e.g. during
                // `svc wifi` transitions) must not register as a disconnect —
                // require 2 consecutive misses before going absent. Reconnects
                // are still seen immediately.
                if raw { misses = 0 } else { misses += 1 }
                let seen = raw || (lastSeen && misses < 2)
                if seen != lastSeen {
                    self.watchLog("presence \(lastSeen) → \(seen)")
                    if !seen {
                        // Phone gone: state unknown — dash all toggles.
                        DispatchQueue.main.async {
                            self.knownStates.removeAll()
                            for item in self.toggleItems { item.state = .mixed }
                            self.statusLine.title = "Phone not connected."
                        }
                    }
                }
                if seen && !lastSeen && self.autoEnabled { self.runAutoEnable() }
                lastSeen = seen
                // Refresh checkmarks from the phone's actual state (cheap single
                // query, at most every 10s).
                if seen && Date().timeIntervalSince(lastStateCheck) >= 10 {
                    lastStateCheck = Date()
                    self.refreshMenuStates()
                }
            }
        }
    }

    /// Reads the phone's actual state and checkmarks each toggle item.
    func refreshMenuStates() {
        DispatchQueue.global().async {
            guard let adb = findAdb(), self.phonePresent() else { return }
            // Skip the update on a transient query failure (adb hiccup, e.g.
            // during `svc wifi` transitions) — keeps the last known marks
            // instead of painting everything off.
            guard let ifaces = fetchTetheredIfaces(adb: adb), let wifi = readWifiOn(adb: adb) else { return }
            let states: [ToggleKind: Bool] = [
                .usb: ifaces.contains(where: { kindMatches(iface: $0, kind: .usb) }),
                .lan: ifaces.contains(where: { kindMatches(iface: $0, kind: .lan) }),
                .hotspot: ifaces.contains(where: { kindMatches(iface: $0, kind: .hotspot) }),
                .wifi: wifi,
            ]
            DispatchQueue.main.async {
                self.knownStates = states
                self.usbItem.state = states[.usb]! ? .on : .off
                self.lanItem.state = states[.lan]! ? .on : .off
                self.hotspotItem.state = states[.hotspot]! ? .on : .off
                self.wifiItem.state = states[.wifi]! ? .on : .off
            }
        }
    }

    func runAutoEnable() {
        guard tryBeginOp() else { watchLog("auto-enable skipped (manual op running)"); return } // a manual action is running — leave it alone
        watchLog("auto-enable: phone reconnected, checking USB tethering")
        setBusy(true, note: "Phone reconnected — checking USB tethering…")
        DispatchQueue.global().async {
            defer { self.endOp() }
            defer { self.refreshMenuStates() }
            // A flap (USB re-enumeration, adb hiccup) looks like a reconnect; if
            // USB tethering is already on there's nothing to do — and re-running
            // startTethering would reconfigure USB for nothing. Today most
            // "auto-enable result: ON" runs were exactly that.
            if let adb = findAdb(), currentState(adb: adb, kind: .usb) == true {
                self.watchLog("auto-enable: already on, nothing to do")
                self.setBusy(false, note: "USB tethering is ON.")
                return
            }
            do {
                let msg = try setTethering(.usb, on: true)
                self.watchLog("auto-enable result: \(msg)")
                self.setBusy(false, note: msg)
            } catch TetherError.message(let m) {
                self.watchLog("auto-enable failed: \(m)")
                self.setBusy(false, note: "Auto-enable failed: \(m)")
            } catch {
                self.watchLog("auto-enable failed: \(error)")
                self.setBusy(false, note: "Auto-enable failed: \(error)")
            }
        }
    }
}

// MARK: - Entry

let cliArgs = CommandLine.arguments
if cliArgs.contains("--on") || cliArgs.contains("--off") || cliArgs.contains("--lan-on") || cliArgs.contains("--lan-off") || cliArgs.contains("--hotspot-on") || cliArgs.contains("--hotspot-off") || cliArgs.contains("--wifi-on") || cliArgs.contains("--wifi-off") || cliArgs.contains("--print-states") || cliArgs.contains("--help") || cliArgs.contains("-h") {
    exit(runCLI(cliArgs))
}
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
