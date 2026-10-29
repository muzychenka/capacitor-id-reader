import AVFoundation
import UIKit
import Vision

final class MrzScannerViewController: UIViewController {
    private static let requiredMatchingFrames = 2

    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "mrz.scanner")
    private var onResult: (([String]?) -> Void)?
    private var finished = false
    private let finishLock = NSLock()
    private var isFinished: Bool {
        finishLock.lock()
        defer { finishLock.unlock() }
        return finished
    }
    private var lastKey: String?
    private var matchingFrames = 0
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var prompt: String?
    private var cancelLabel: String?

    static func present(from presenter: UIViewController, prompt: String?, cancelLabel: String?, onResult: @escaping ([String]?) -> Void) {
        let controller = MrzScannerViewController()
        controller.prompt = prompt
        controller.cancelLabel = cancelLabel
        controller.onResult = onResult
        controller.modalPresentationStyle = .fullScreen
        presenter.present(controller, animated: true)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
        addCancelButton()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        queue.async { [weak self] in
            guard let self, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        queue.async { [weak self] in self?.session.stopRunning() }
    }

    private func configureSession() {
        session.sessionPreset = .hd1920x1080

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input), session.canAddOutput(output) else {
            finish(with: nil)
            return
        }

        configureFocus(device)

        session.addInput(input)
        output.setSampleBufferDelegate(self, queue: queue)
        output.alwaysDiscardsLateVideoFrames = true
        session.addOutput(output)

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        previewLayer = preview

        let hint = UILabel()
        hint.text = prompt ?? "Point the camera at the machine-readable lines on your document"
        hint.textColor = .white
        hint.numberOfLines = 0
        hint.textAlignment = .center
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)
        NSLayoutConstraint.activate([
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            hint.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 40)
        ])
    }

    private func configureFocus(_ device: AVCaptureDevice) {
        guard (try? device.lockForConfiguration()) != nil else { return }
        defer { device.unlockForConfiguration() }

        if device.isFocusModeSupported(.continuousAutoFocus) {
            device.focusMode = .continuousAutoFocus
        }

        if device.isAutoFocusRangeRestrictionSupported {
            device.autoFocusRangeRestriction = .near
        }

        if device.minimumFocusDistance > 150 {
            device.videoZoomFactor = min(2, device.activeFormat.videoMaxZoomFactor)
        }
    }

    private func addCancelButton() {
        let button = UIButton(type: .system)
        button.setTitle(cancelLabel ?? "Cancel", for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.addTarget(self, action: #selector(onCancel), for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(button)
        NSLayoutConstraint.activate([
            button.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            button.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -30)
        ])
    }

    @objc private func onCancel() {
        finish(with: nil)
    }

    private func finish(with lines: [String]?) {
        finishLock.lock()
        guard !finished else { finishLock.unlock(); return }
        finished = true
        finishLock.unlock()

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let callback = self.onResult
            self.onResult = nil
            self.dismiss(animated: true) { callback?(lines) }
        }
    }

    private func handle(_ result: MrzReader.Result?) {
        guard let result else {
            lastKey = nil
            matchingFrames = 0
            return
        }

        matchingFrames = result.key == lastKey ? matchingFrames + 1 : 1
        lastKey = result.key

        if matchingFrames >= Self.requiredMatchingFrames {
            finish(with: result.lines)
        }
    }
}

extension MrzScannerViewController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard !isFinished, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US"]

        guard (try? VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .right).perform([request])) != nil,
              !isFinished else { return }

        let lines = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }

        handle(MrzReader.read(lines))
    }
}

enum MrzReader {
    struct Result {
        let key: String
        let lines: [String]
    }

    private struct Layout {
        let length: Int
        let documentNumber: (line: Int, start: Int)
        let dateOfBirth: (line: Int, start: Int)
        let dateOfExpiry: (line: Int, start: Int)
    }

    private static let lengthTolerance = 2

    private static let layouts = [
        Layout(length: 30, documentNumber: (0, 5), dateOfBirth: (1, 0), dateOfExpiry: (1, 8)),
        Layout(length: 44, documentNumber: (1, 0), dateOfBirth: (1, 13), dateOfExpiry: (1, 21)),
        Layout(length: 36, documentNumber: (1, 0), dateOfBirth: (1, 13), dateOfExpiry: (1, 21))
    ]

    private static let digitLookalikes: [Character: Character] = [
        "O": "0", "Q": "0", "D": "0", "I": "1", "L": "1", "Z": "2", "S": "5", "B": "8", "G": "6"
    ]

    static func read(_ rawLines: [String]) -> Result? {
        let lines = rawLines.map(normalize).filter { !$0.isEmpty }

        for layout in layouts {
            let candidates = lines.filter { abs($0.count - layout.length) <= lengthTolerance }

            for index in candidates.indices.dropLast() {
                let pair = [candidates[index], candidates[index + 1]]

                if let key = extract(pair, layout) {
                    return Result(key: key, lines: pair)
                }
            }
        }

        return nil
    }

    private static func normalize(_ line: String) -> String {
        line.uppercased()
            .replacingOccurrences(of: "«", with: "<<")
            .replacingOccurrences(of: "‹", with: "<")
            .filter { ("A"..."Z").contains($0) || ("0"..."9").contains($0) || $0 == "<" }
    }

    private static func extract(_ lines: [String], _ layout: Layout) -> String? {
        guard let prefix = lines[0].first, "PIAC".contains(prefix),
              let number = field(lines, layout.documentNumber, 9),
              let numberCheck = field(lines, (layout.documentNumber.line, layout.documentNumber.start + 9), 1),
              let birth = field(lines, layout.dateOfBirth, 6).map(toDigits),
              let birthCheck = field(lines, (layout.dateOfBirth.line, layout.dateOfBirth.start + 6), 1),
              let expiry = field(lines, layout.dateOfExpiry, 6).map(toDigits),
              let expiryCheck = field(lines, (layout.dateOfExpiry.line, layout.dateOfExpiry.start + 6), 1),
              number.contains(where: { $0 != "<" }),
              birth.range(of: "^[0-9]{6}$", options: .regularExpression) != nil,
              expiry.range(of: "^[0-9]{6}$", options: .regularExpression) != nil,
              NfcIdReaderPlugin.checkDigit(number) == toDigits(numberCheck),
              NfcIdReaderPlugin.checkDigit(birth) == toDigits(birthCheck),
              NfcIdReaderPlugin.checkDigit(expiry) == toDigits(expiryCheck) else {
            return nil
        }

        return number + birth + expiry
    }

    private static func field(_ lines: [String], _ position: (line: Int, start: Int), _ length: Int) -> String? {
        let line = lines[position.line]
        guard position.start + length <= line.count else { return nil }

        let start = line.index(line.startIndex, offsetBy: position.start)
        return String(line[start..<line.index(start, offsetBy: length)])
    }

    private static func toDigits(_ value: String) -> String {
        String(value.map { digitLookalikes[$0] ?? $0 })
    }
}
