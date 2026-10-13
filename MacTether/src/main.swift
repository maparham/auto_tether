import AppKit
import Foundation

// MARK: - Config

let dexRemotePath = "/data/local/tmp/tether.dex"
let usbTetherType = "1" // TetheringManager.TETHERING_USB

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

// Poll dumpsys until the USB-tether state matches, tolerating the gap where
// adb drops out (USB reconfigures ~3-5s after a start/stop command).
func pollUsbState(adb: String, wantOn: Bool, timeout: TimeInterval = 25) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if waitForDevice(adb: adb, timeout: 6) {
            if isUsbTetheringOn(adb: adb) == wantOn { return true }
        }
        Thread.sleep(forTimeInterval: 2)
    }
    return false
}
func isUsbTetheringOn(adb: String) -> Bool {
    // Filter on-device: full `dumpsys tethering` is ~180KB (would block the
    // capture pipe), the grep reduces it to a few lines.
    let (_, out) = runCmd(adb, ["shell", "dumpsys tethering | grep TetheredState"], timeout: 15)
    // Look for a USB gadget interface (ncm/rndis/usb) in TetheredState.
    // (Avoid matching wlan — Wi-Fi hotspot is a different feature.)
    for line in out.split(separator: "\n") {
        let t = line.trimmingCharacters(in: .whitespaces)
        if t.contains("TetheredState") && (t.hasPrefix("ncm") || t.hasPrefix("rndis") || t.hasPrefix("usb")) {
            return true
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
func setUsbTethering(on: Bool) throws -> String {
    guard let adb = findAdb() else {
        throw TetherError.message("adb not found. Install Android platform-tools (brew install android-platform-tools).")
    }
    guard waitForDevice(adb: adb) else {
        throw TetherError.message("No phone found over adb. Plug it in via USB with USB debugging enabled.")
    }
    if let err = ensureHelperOnDevice(adb: adb) { throw TetherError.message(err) }
    let action = on ? "start" : "stop"
    runCmd(adb, ["shell", "CLASSPATH=\(dexRemotePath) app_process /system/bin Main \(usbTetherType) \(action)"], timeout: 30)
    // Start/stop reconfigures USB and drops adb for a few seconds — poll the
    // ground truth (dumpsys) until it settles instead of checking once.
    if pollUsbState(adb: adb, wantOn: on) {
        return on ? "USB tethering is ON." : "USB tethering is OFF."
    }
    if on {
        throw TetherError.message("Could not confirm tethering turned on. Unlock the phone and try again.")
    } else {
        throw TetherError.message("Tethering still shows as active. Try again.")
    }
}

// MARK: - CLI mode (also used for testing)

func runCLI(_ args: [String]) -> Int32 {
    let on: Bool?
    if args.contains("--on") { on = true }
    else if args.contains("--off") { on = false }
    else {
        print("Usage: MacTether --on | --off")
        return 2
    }
    do {
        print(try setUsbTethering(on: on!))
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
    var onItem: NSMenuItem!
    var offItem: NSMenuItem!
    var statusLine: NSMenuItem!

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
        onItem = NSMenuItem(title: "Turn USB Tethering On", action: #selector(turnOn), keyEquivalent: "")
        offItem = NSMenuItem(title: "Turn USB Tethering Off", action: #selector(turnOff), keyEquivalent: "")
        onItem.target = self
        offItem.target = self
        statusLine = NSMenuItem(title: "Ready", action: nil, keyEquivalent: "")
        statusLine.isEnabled = false
        menu.addItem(onItem)
        menu.addItem(offItem)
        menu.addItem(.separator())
        menu.addItem(statusLine)
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.addItem(quit)
        statusItem.menu = menu
    }

    func setBusy(_ busy: Bool, note: String) {
        DispatchQueue.main.async {
            self.onItem.isEnabled = !busy
            self.offItem.isEnabled = !busy
            self.statusLine.title = note
        }
    }

    @objc func turnOn() { runAction(on: true) }
    @objc func turnOff() { runAction(on: false) }

    func runAction(on: Bool) {
        setBusy(true, note: on ? "Turning USB tethering on…" : "Turning USB tethering off…")
        DispatchQueue.global().async {
            do {
                let msg = try setUsbTethering(on: on)
                self.setBusy(false, note: msg)
            } catch TetherError.message(let m) {
                self.setBusy(false, note: m)
                DispatchQueue.main.async {
                    let alert = NSAlert()
                    alert.messageText = "USB tethering failed"
                    alert.informativeText = m
                    alert.addButton(withTitle: "OK")
                    alert.runModal()
                }
            } catch {
                self.setBusy(false, note: "\(error)")
            }
        }
    }
}

// MARK: - Entry

let cliArgs = CommandLine.arguments
if cliArgs.contains("--on") || cliArgs.contains("--off") || cliArgs.contains("--help") || cliArgs.contains("-h") {
    exit(runCLI(cliArgs))
}
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
