import AppKit
import Foundation

// MARK: - Config

let dexRemotePath = "/data/local/tmp/tether.dex"

enum TetherKind {
    case usb   // phone shares its connection with this computer (type 1)
    case lan   // phone shares via a USB-Ethernet adapter (type 5)

    var helperType: String {
        switch self {
        case .usb: return "1" // TetheringManager.TETHERING_USB
        case .lan: return "5" // TetheringManager.TETHERING_ETHERNET
        }
    }
    var label: String {
        switch self {
        case .usb: return "USB"
        case .lan: return "LAN"
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
    let group = DispatchGroup()
    group.enter()
    DispatchQueue.global().async { proc.waitUntilExit(); group.leave() }
    if group.wait(timeout: .now() + timeout) == .timedOut {
        proc.terminate()
        return (-1, "timed out after \(Int(timeout))s: \(launchPath) \(args.joined(separator: " "))")
    }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
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

// Poll dumpsys until the tether state matches, tolerating the gap where adb
// drops out (USB reconfigures ~3-5s after a USB start/stop command).
func pollTetherState(adb: String, kind: TetherKind, wantOn: Bool, timeout: TimeInterval = 25) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if waitForDevice(adb: adb, timeout: 6) {
            if isTetheringOn(adb: adb, kind: kind) == wantOn { return true }
        }
        Thread.sleep(forTimeInterval: 2)
    }
    return false
}

// Interface prefixes that are never the Ethernet adapter: loopback, Wi-Fi /
// Wi-Fi-direct, cellular, tunnels, and the phone's OWN usb-tether gadget
// (ncm/rndis). Mirrors TetherService on the phone.
let internalIfacePrefixes = ["lo", "wlan", "p2p", "aware", "nan",
                             "rmnet", "ccmni", "umts", "ncm", "rndis",
                             "dummy", "sit", "gre", "tunl", "tun",
                             "ip6", "ip_vti", "erspan", "ifb", "radiotap", "bond"]

func isTetheringOn(adb: String, kind: TetherKind) -> Bool {
    // Filter on-device: full `dumpsys tethering` is ~180KB (would block the
    // capture pipe), the grep reduces it to a few lines.
    let (_, out) = runCmd(adb, ["shell", "dumpsys tethering | grep TetheredState"], timeout: 15)
    for line in out.split(separator: "\n") {
        let t = line.trimmingCharacters(in: .whitespaces)
        // Strict state-line shape: "<iface> - TetheredState …". This skips the
        // dumpsys event-log lines ("… - [ncm0] TetheredState got …"), whose
        // leading field is a timestamp, not an interface name.
        let tok = t.split(separator: " ")
        guard tok.count >= 3 && tok[1] == "-" && tok[2].hasPrefix("TetheredState") else { continue }
        let iface = String(tok[0])
        switch kind {
        case .usb:
            // The phone's USB-tether gadget (ncm/rndis/usb).
            if iface.hasPrefix("ncm") || iface.hasPrefix("rndis") || iface.hasPrefix("usb") { return true }
        case .lan:
            // Any tethered interface that isn't Wi-Fi/cellular/USB-gadget —
            // i.e. the attached Ethernet adapter (eth0/usb0/enx…).
            if !iface.isEmpty && !internalIfacePrefixes.contains(where: { iface.hasPrefix($0) }) { return true }
        }
    }
    return false
}

enum TetherError: Error {
    case message(String)
}

// Runs the helper and verifies via dumpsys, tolerating the expected quirk:
// enabling USB tethering reconfigures USB and drops its own adb connection
// (exit 255, no STARTED line) even on success — so ground truth is dumpsys.
//
// Note: LAN tethering needs the adapter on the phone's USB port, so over USB
// adb this requires a hub (or Wi-Fi adb); otherwise the phone is unreachable
// and this throws.
func setTethering(_ kind: TetherKind, on: Bool) throws -> String {
    guard let adb = findAdb() else {
        throw TetherError.message("adb not found. Install Android platform-tools (brew install android-platform-tools).")
    }
    guard waitForDevice(adb: adb) else {
        throw TetherError.message("No phone found over adb. Plug it in via USB with USB debugging enabled.")
    }
    if let err = ensureHelperOnDevice(adb: adb) { throw TetherError.message(err) }
    let action = on ? "start" : "stop"
    runCmd(adb, ["shell", "CLASSPATH=\(dexRemotePath) app_process /system/bin Main \(kind.helperType) \(action)"], timeout: 30)
    // Start/stop can drop adb for a few seconds — poll the ground truth
    // (dumpsys) until it settles instead of checking once.
    if pollTetherState(adb: adb, kind: kind, wantOn: on) {
        return on ? "\(kind.label) tethering is ON." : "\(kind.label) tethering is OFF."
    }
    if on {
        throw TetherError.message("Could not confirm \(kind.label) tethering turned on. Unlock the phone and try again.")
    } else {
        throw TetherError.message("\(kind.label) tethering still shows as active. Try again.")
    }
}

// MARK: - CLI mode (also used for testing)

func runCLI(_ args: [String]) -> Int32 {
    let kind: TetherKind?
    let on: Bool?
    if args.contains("--on") { kind = .usb; on = true }
    else if args.contains("--off") { kind = .usb; on = false }
    else if args.contains("--lan-on") { kind = .lan; on = true }
    else if args.contains("--lan-off") { kind = .lan; on = false }
    else {
        print("Usage: MacTether --on | --off | --lan-on | --lan-off")
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
    var usbOnItem: NSMenuItem!
    var usbOffItem: NSMenuItem!
    var lanOnItem: NSMenuItem!
    var lanOffItem: NSMenuItem!
    var autoItem: NSMenuItem!
    var statusLine: NSMenuItem!

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
        usbOnItem = NSMenuItem(title: "Turn USB Tethering On", action: #selector(turnUsbOn), keyEquivalent: "")
        usbOffItem = NSMenuItem(title: "Turn USB Tethering Off", action: #selector(turnUsbOff), keyEquivalent: "")
        lanOnItem = NSMenuItem(title: "Turn LAN Tethering On", action: #selector(turnLanOn), keyEquivalent: "")
        lanOffItem = NSMenuItem(title: "Turn LAN Tethering Off", action: #selector(turnLanOff), keyEquivalent: "")
        for item in [usbOnItem, usbOffItem, lanOnItem, lanOffItem] as [NSMenuItem] { item.target = self }
        autoItem = NSMenuItem(title: "Auto-enable USB on reconnect", action: #selector(toggleAuto), keyEquivalent: "")
        autoItem.target = self
        autoItem.state = autoEnabled ? .on : .off
        statusLine = NSMenuItem(title: "Ready", action: nil, keyEquivalent: "")
        statusLine.isEnabled = false
        menu.addItem(usbOnItem)
        menu.addItem(usbOffItem)
        menu.addItem(lanOnItem)
        menu.addItem(lanOffItem)
        menu.addItem(.separator())
        menu.addItem(autoItem)
        menu.addItem(.separator())
        menu.addItem(statusLine)
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.addItem(quit)
        statusItem.menu = menu
        startWatcher()
    }

    func setBusy(_ busy: Bool, note: String) {
        DispatchQueue.main.async {
            for item in [self.usbOnItem, self.usbOffItem, self.lanOnItem, self.lanOffItem] as [NSMenuItem] {
                item.isEnabled = !busy
            }
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

    @objc func turnUsbOn() { runAction(kind: .usb, on: true) }
    @objc func turnUsbOff() { runAction(kind: .usb, on: false) }
    @objc func turnLanOn() { runAction(kind: .lan, on: true) }
    @objc func turnLanOff() { runAction(kind: .lan, on: false) }

    @objc func toggleAuto() {
        autoEnabled = !autoEnabled
        autoItem.state = autoEnabled ? .on : .off
        setNote(autoEnabled ? "Auto-enable on reconnect: on." : "Auto-enable on reconnect: off.")
    }

    func runAction(kind: TetherKind, on: Bool) {
        guard tryBeginOp() else { return }
        setBusy(true, note: on ? "Turning \(kind.label) tethering on…" : "Turning \(kind.label) tethering off…")
        DispatchQueue.global().async {
            defer { self.endOp() }
            do {
                let msg = try setTethering(kind, on: on)
                self.setBusy(false, note: msg)
            } catch TetherError.message(let m) {
                self.setBusy(false, note: m)
                DispatchQueue.main.async {
                    let alert = NSAlert()
                    alert.messageText = "\(kind.label) tethering failed"
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
            while true {
                Thread.sleep(forTimeInterval: 3)
                let seen = self.phonePresent()
                if seen != lastSeen { self.watchLog("presence \(lastSeen) → \(seen)") }
                if seen && !lastSeen && self.autoEnabled { self.runAutoEnable() }
                lastSeen = seen
            }
        }
    }

    func runAutoEnable() {
        guard tryBeginOp() else { watchLog("auto-enable skipped (manual op running)"); return } // a manual action is running — leave it alone
        watchLog("auto-enable: phone reconnected, enabling USB tethering")
        setBusy(true, note: "Phone reconnected — enabling USB tethering…")
        DispatchQueue.global().async {
            defer { self.endOp() }
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
if cliArgs.contains("--on") || cliArgs.contains("--off") || cliArgs.contains("--lan-on") || cliArgs.contains("--lan-off") || cliArgs.contains("--help") || cliArgs.contains("-h") {
    exit(runCLI(cliArgs))
}
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
