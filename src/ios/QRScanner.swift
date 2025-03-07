import Foundation
import AVFoundation
import StoreKit
import NetworkExtension
import SystemConfiguration

@objc(QRScanner)
class QRScanner : CDVPlugin, AVCaptureMetadataOutputObjectsDelegate {

    class CameraView: UIView {
        var videoPreviewLayer:AVCaptureVideoPreviewLayer?

        func interfaceOrientationToVideoOrientation(_ orientation : UIInterfaceOrientation) -> AVCaptureVideoOrientation {
            switch (orientation) {
            case UIInterfaceOrientation.portrait:
                return AVCaptureVideoOrientation.portrait;
            case UIInterfaceOrientation.portraitUpsideDown:
                return AVCaptureVideoOrientation.portraitUpsideDown;
            case UIInterfaceOrientation.landscapeLeft:
                return AVCaptureVideoOrientation.landscapeLeft;
            case UIInterfaceOrientation.landscapeRight:
                return AVCaptureVideoOrientation.landscapeRight;
            default:
                return AVCaptureVideoOrientation.portraitUpsideDown;
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews();
            if let sublayers = self.layer.sublayers {
                for layer in sublayers {
                    layer.frame = self.bounds;
                }
            }

            self.videoPreviewLayer?.connection?.videoOrientation = interfaceOrientationToVideoOrientation(UIApplication.shared.statusBarOrientation);
        }


        func addPreviewLayer(_ previewLayer:AVCaptureVideoPreviewLayer?) {
            previewLayer!.videoGravity = AVLayerVideoGravity.resizeAspectFill
            previewLayer!.frame = self.bounds
            self.layer.addSublayer(previewLayer!)
            self.videoPreviewLayer = previewLayer;
        }

        func removePreviewLayer() {
            if self.videoPreviewLayer != nil {
                self.videoPreviewLayer!.removeFromSuperlayer()
                self.videoPreviewLayer = nil
            }
        }
    }

    var cameraView: CameraView!
    var captureSession:AVCaptureSession?
    var captureVideoPreviewLayer:AVCaptureVideoPreviewLayer?
    var metaOutput: AVCaptureMetadataOutput?

    var currentCamera: Int = 0;
    var frontCamera: AVCaptureDevice?
    var backCamera: AVCaptureDevice?

    var scanning: Bool = false
    var paused: Bool = false
    var nextScanningCommand: CDVInvokedUrlCommand?

    enum QRScannerError: Int32 {
        case unexpected_error = 0,
        camera_access_denied = 1,
        camera_access_restricted = 2,
        back_camera_unavailable = 3,
        front_camera_unavailable = 4,
        camera_unavailable = 5,
        scan_canceled = 6,
        light_unavailable = 7,
        open_settings_unavailable = 8
    }

    enum CaptureError: Error {
        case backCameraUnavailable
        case frontCameraUnavailable
        case couldNotCaptureInput(error: NSError)
    }

    enum LightError: Error {
        case torchUnavailable
    }

    override func pluginInitialize() {
        super.pluginInitialize()
        NotificationCenter.default.addObserver(self, selector: #selector(pageDidLoad), name: NSNotification.Name.CDVPageDidLoad, object: nil)
        self.cameraView = CameraView(frame: CGRect(x: 0, y: 0, width: UIScreen.main.bounds.width, height: UIScreen.main.bounds.height))
        self.cameraView.autoresizingMask = [.flexibleWidth, .flexibleHeight];
    }

    func sendErrorCode(command: CDVInvokedUrlCommand, error: QRScannerError){
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error.rawValue)
        commandDelegate!.send(pluginResult, callbackId:command.callbackId)
    }

    // utility method
    @objc func backgroundThread(delay: Double = 0.0, background: (() -> Void)? = nil, completion: (() -> Void)? = nil) {
        if #available(iOS 8.0, *) {
            DispatchQueue.global(qos: DispatchQoS.QoSClass.userInitiated).async {
                if (background != nil) {
                    background!()
                }
                DispatchQueue.main.asyncAfter(deadline: DispatchTime.now() + delay * Double(NSEC_PER_SEC)) {
                    if(completion != nil){
                        completion!()
                    }
                }
            }
        } else {
            // Fallback for iOS < 8.0
            if(background != nil){
                background!()
            }
            if(completion != nil){
                completion!()
            }
        }
    }
    @objc func setZoomLevel(_ command: CDVInvokedUrlCommand){
        do {
            let deviceInput = captureSession?.inputs[0];
            if(deviceInput == nil) {
                return;
            }
            let input = deviceInput as! AVCaptureDeviceInput


            let zoomFactor: CGFloat = command.arguments[0] as! CGFloat;

            let maxZoomFactor = input.device.activeFormat.videoMaxZoomFactor
            let clampedZoomFactor = min(zoomFactor, maxZoomFactor)

            try input.device.lockForConfiguration()
            // Animate zoom over time with a rate (1.0 is a standard zoom rate, adjust as needed)
            input.device.ramp(toVideoZoomFactor: clampedZoomFactor, withRate: 3.0)
//          input.device.videoZoomFactor = min(zoomFactor, input.device.activeFormat.videoMaxZoomFactor)

            input.device.unlockForConfiguration()
        }
        catch {
            self.sendErrorCode(command: command, error: QRScannerError.unexpected_error)
        }

    }
    @objc func prepScanner(command: CDVInvokedUrlCommand) -> Bool{
        let ultraWideDevice = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back)
        let status = AVCaptureDevice.authorizationStatus(for: AVMediaType.video)
        if (status == AVAuthorizationStatus.restricted) {
            self.sendErrorCode(command: command, error: QRScannerError.camera_access_restricted)
            return false
        } else if status == AVAuthorizationStatus.denied {
            self.sendErrorCode(command: command, error: QRScannerError.camera_access_denied)
            return false
        }
        do {
            if (captureSession?.isRunning != true){
                cameraView.backgroundColor = UIColor.clear
                self.webView!.superview!.insertSubview(cameraView, belowSubview: self.webView!)
                let availableVideoDevices =  AVCaptureDevice.devices(for: AVMediaType.video)
                for device in availableVideoDevices {
                    if device.position == AVCaptureDevice.Position.back {
                        backCamera = device
                    }
                    else if device.position == AVCaptureDevice.Position.front {
                        frontCamera = device
                    }
                }
                if(ultraWideDevice != nil){
                    backCamera = ultraWideDevice
                }
                // older iPods have no back camera
                if(backCamera == nil){
                    currentCamera = 1
                }
                let input: AVCaptureDeviceInput
                input = try self.createCaptureDeviceInput()
                captureSession = AVCaptureSession()
                captureSession!.addInput(input)
                metaOutput = AVCaptureMetadataOutput()
                captureSession!.addOutput(metaOutput!)
                metaOutput!.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
//                metaOutput!.setMetadataObjectsDelegate(self, queue: DispatchQueue.global(qos: .userInitiated))

                if(command.arguments.contains(where:{ $0 as? String == "only-2d" })){
                    metaOutput!.metadataObjectTypes = [AVMetadataObject.ObjectType.qr, AVMetadataObject.ObjectType.dataMatrix]
                }else{
                    metaOutput!.metadataObjectTypes = [AVMetadataObject.ObjectType.ean13, AVMetadataObject.ObjectType.code128, AVMetadataObject.ObjectType.qr, AVMetadataObject.ObjectType.dataMatrix, AVMetadataObject.ObjectType.code93, AVMetadataObject.ObjectType.ean8, AVMetadataObject.ObjectType.upce, AVMetadataObject.ObjectType.itf14, AVMetadataObject.ObjectType.code39, AVMetadataObject.ObjectType.interleaved2of5, AVMetadataObject.ObjectType.code39Mod43, AVMetadataObject.ObjectType.gs1DataBar, AVMetadataObject.ObjectType.gs1DataBarLimited, AVMetadataObject.ObjectType.gs1DataBarExpanded]
                }

//                metaOutput!.metadataObjectTypes = [ AVMetadataObject.ObjectType.qr, AVMetadataObject.ObjectType.dataMatrix]

                captureVideoPreviewLayer = AVCaptureVideoPreviewLayer(session: captureSession!)
                cameraView.addPreviewLayer(captureVideoPreviewLayer)

                try input.device.lockForConfiguration()
                DispatchQueue.global(qos: DispatchQoS.QoSClass.userInitiated).async {
                    let zoomFactor: CGFloat = 3.0  // or the desired zoom level
                    input.device.videoZoomFactor = min(zoomFactor, input.device.activeFormat.videoMaxZoomFactor)
                    input.device.unlockForConfiguration()

                    self.captureSession!.startRunning()
                }
                setupCenteredRectOfInterest(for: captureSession!, in: cameraView)
            }
            return true
        } catch CaptureError.backCameraUnavailable {
            self.sendErrorCode(command: command, error: QRScannerError.back_camera_unavailable)
        } catch CaptureError.frontCameraUnavailable {
            self.sendErrorCode(command: command, error: QRScannerError.front_camera_unavailable)
        } catch CaptureError.couldNotCaptureInput(let error){
            print(error.localizedDescription)
            self.sendErrorCode(command: command, error: QRScannerError.camera_unavailable)
        } catch {
            self.sendErrorCode(command: command, error: QRScannerError.unexpected_error)
        }
        return false
    }

    func setupCenteredRectOfInterest(for captureSession: AVCaptureSession, in cameraView: UIView) {
        guard let metadataOutput = captureSession.outputs.first as? AVCaptureMetadataOutput else { return }

        // Define the middle 50% scan-able area
        let width = cameraView.frame.width * 0.5
        let height = cameraView.frame.height * 0.5
        let originX = (cameraView.frame.width - width) / 2
        let originY = (cameraView.frame.height - height) / 2
        let scanArea = CGRect(x: originX, y: originY, width: width, height: height)

        // Convert the scanArea to the camera's coordinate system
        let x = scanArea.origin.y / cameraView.frame.height
        let y = 1 - ((scanArea.origin.x + scanArea.width) / cameraView.frame.width)
        let adjustedWidth = scanArea.height / cameraView.frame.height
        let adjustedHeight = scanArea.width / cameraView.frame.width

        // Set the metadata output’s rect of interest
        metadataOutput.rectOfInterest = CGRect(x: x, y: y, width: adjustedWidth, height: adjustedHeight)
    }

    @objc func createCaptureDeviceInput() throws -> AVCaptureDeviceInput {
        var captureDevice: AVCaptureDevice
        if(currentCamera == 0) {
            if(backCamera != nil) {
                captureDevice = backCamera!
            } else {
                throw CaptureError.backCameraUnavailable
            }
        } else {
            if(frontCamera != nil) {
                captureDevice = frontCamera!
            } else {
                throw CaptureError.frontCameraUnavailable
            }
        }


        let captureDeviceInput: AVCaptureDeviceInput
        do {
            captureDeviceInput = try AVCaptureDeviceInput(device: captureDevice)
        } catch let error as NSError {
            throw CaptureError.couldNotCaptureInput(error: error)
        }
        return captureDeviceInput
    }


    @objc func makeOpaque(){
        self.webView?.isOpaque = false
        self.webView?.backgroundColor = UIColor.clear
    }

    @objc func boolToNumberString(bool: Bool) -> String{
        if(bool) {
            return "1"
        } else {
            return "0"
        }
    }

    @objc func configureLight(command: CDVInvokedUrlCommand, state: Bool){
        var useMode = AVCaptureDevice.TorchMode.on
        if(state == false){
            useMode = AVCaptureDevice.TorchMode.off
        }
        do {
            // torch is only available for back camera
            if(self.backCamera == nil){
                print("self.backCamera == nil")
                throw LightError.torchUnavailable
            }
            if(self.backCamera!.hasTorch == false){
                print("self.backCamera!.hasTorch == false")
                throw LightError.torchUnavailable
            }
            if(self.backCamera!.isTorchAvailable == false){
                print("self.backCamera!.isTorchAvailable == false")
                throw LightError.torchUnavailable
            }
            if(self.backCamera!.isTorchModeSupported(useMode) == false){
                print("self.backCamera!.isTorchModeSupported(useMode) == false")
                throw LightError.torchUnavailable
            }
            try self.backCamera!.lockForConfiguration()
            self.backCamera!.torchMode = useMode
            self.backCamera!.unlockForConfiguration()
            self.getStatus(command)
        } catch LightError.torchUnavailable {
            self.sendErrorCode(command: command, error: QRScannerError.light_unavailable)
        } catch let error as NSError {
            print(error.localizedDescription)
            self.sendErrorCode(command: command, error: QRScannerError.unexpected_error)
        }
    }

    // This method processes metadataObjects captured by iOS.
    func metadataOutput(_ captureOutput: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        if metadataObjects.count == 0 || scanning == false {
            // while nothing is detected, or if scanning is false, do nothing.
            return
        }
        let found = metadataObjects[0] as! AVMetadataMachineReadableCodeObject
        print(found.type.rawValue)
        print(found.stringValue)
        if found.stringValue != nil {
            let text:String = found.stringValue ?? "";
            let message = "{\"text\":\"" + text + "\",\"type\":\"" + found.type.rawValue + "\"}";
            scanning = false
            let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: message)
            commandDelegate!.send(pluginResult, callbackId: nextScanningCommand?.callbackId!)
            nextScanningCommand = nil
        }
    }

    @objc func pageDidLoad() {
        self.webView?.isOpaque = false
        self.webView?.backgroundColor = UIColor.clear
    }

    // ---- BEGIN EXTERNAL API ----

    @objc func prepare(_ command: CDVInvokedUrlCommand){
        let status = AVCaptureDevice.authorizationStatus(for: AVMediaType.video)
        if (status == AVAuthorizationStatus.notDetermined) {
            // Request permission before preparing scanner
            AVCaptureDevice.requestAccess(for: AVMediaType.video, completionHandler: { (granted) -> Void in
                // attempt to prepScanner only after the request returns
                self.backgroundThread(delay: 0, completion: {
                    if(self.prepScanner(command: command)){
                        self.getStatus(command)
                    }
                })
            })
        } else {
            if(self.prepScanner(command: command)){
                self.getStatus(command)
            }
        }
    }

    @objc func scan(_ command: CDVInvokedUrlCommand){
        if(self.prepScanner(command: command)){
            nextScanningCommand = command
            scanning = true
        }
    }

    @objc func cancelScan(_ command: CDVInvokedUrlCommand){
        if(self.prepScanner(command: command)){
            scanning = false
            if(nextScanningCommand != nil){
                self.sendErrorCode(command: nextScanningCommand!, error: QRScannerError.scan_canceled)
            }
            self.getStatus(command)
        }
    }

    @objc func show(_ command: CDVInvokedUrlCommand) {
        self.webView?.isOpaque = false
        self.webView?.backgroundColor = UIColor.clear
        self.getStatus(command)
    }

    @objc func hide(_ command: CDVInvokedUrlCommand) {
        self.makeOpaque()
        self.getStatus(command)
    }

    @objc func promptForReview(_ command: CDVInvokedUrlCommand) {
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            SKStoreReviewController.requestReview(in: windowScene)
        }
    }
    @objc func pausePreview(_ command: CDVInvokedUrlCommand) {
        if(scanning){
            paused = true;
            scanning = false;
        }
        captureVideoPreviewLayer?.connection?.isEnabled = false
        self.getStatus(command)
    }

    @objc func resumePreview(_ command: CDVInvokedUrlCommand) {
        if(paused){
            paused = false;
            scanning = true;
        }
        captureVideoPreviewLayer?.connection?.isEnabled = true
        self.getStatus(command)
    }

    // backCamera is 0, frontCamera is 1

    @objc func useCamera(_ command: CDVInvokedUrlCommand){
        let index = command.arguments[0] as! Int
        if(currentCamera != index){
            // camera change only available if both backCamera and frontCamera exist
            if(backCamera != nil && frontCamera != nil){
                // switch camera
                currentCamera = index
                if(self.prepScanner(command: command)){
                    do {
                        captureSession!.beginConfiguration()
                        let currentInput = captureSession?.inputs[0] as! AVCaptureDeviceInput
                        captureSession!.removeInput(currentInput)
                        let input = try self.createCaptureDeviceInput()
                        captureSession!.addInput(input)
                        captureSession!.commitConfiguration()
                        self.getStatus(command)
                    } catch CaptureError.backCameraUnavailable {
                        self.sendErrorCode(command: command, error: QRScannerError.back_camera_unavailable)
                    } catch CaptureError.frontCameraUnavailable {
                        self.sendErrorCode(command: command, error: QRScannerError.front_camera_unavailable)
                    } catch CaptureError.couldNotCaptureInput(let error){
                        print(error.localizedDescription)
                        self.sendErrorCode(command: command, error: QRScannerError.camera_unavailable)
                    } catch {
                        self.sendErrorCode(command: command, error: QRScannerError.unexpected_error)
                    }

                }
            } else {
                if(backCamera == nil){
                    self.sendErrorCode(command: command, error: QRScannerError.back_camera_unavailable)
                } else {
                    self.sendErrorCode(command: command, error: QRScannerError.front_camera_unavailable)
                }
            }
        } else {
            // immediately return status if camera is unchanged
            self.getStatus(command)
        }
    }

    @objc func enableLight(_ command: CDVInvokedUrlCommand) {
        if(self.prepScanner(command: command)){
            self.configureLight(command: command, state: true)
        }
    }

    @objc func disableLight(_ command: CDVInvokedUrlCommand) {
        if(self.prepScanner(command: command)){
            self.configureLight(command: command, state: false)
        }
    }

    @objc func destroy(_ command: CDVInvokedUrlCommand) {
        self.makeOpaque()
        if(self.captureSession != nil){
            backgroundThread(delay: 0, background: {
                self.captureSession!.stopRunning()
                self.cameraView.removePreviewLayer()
                self.captureVideoPreviewLayer = nil
                self.metaOutput = nil
                self.captureSession = nil
                self.currentCamera = 0
                self.frontCamera = nil
                self.backCamera = nil
            }, completion: {
                self.getStatus(command)
            })
        } else {
            self.getStatus(command)
        }
    }

    @objc func getStatus(_ command: CDVInvokedUrlCommand){

        let authorizationStatus = AVCaptureDevice.authorizationStatus(for: AVMediaType.video);

        var authorized = false
        if(authorizationStatus == AVAuthorizationStatus.authorized){
            authorized = true
        }

        var denied = false
        if(authorizationStatus == AVAuthorizationStatus.denied){
            denied = true
        }

        var restricted = false
        if(authorizationStatus == AVAuthorizationStatus.restricted){
            restricted = true
        }

        var prepared = false
        if(captureSession?.isRunning == true){
            prepared = true
        }

        var previewing = false
        if(captureVideoPreviewLayer != nil){
            previewing = captureVideoPreviewLayer!.connection!.isEnabled
        }

        var showing = false
        if(self.webView!.backgroundColor == UIColor.clear){
            showing = true
        }

        var lightEnabled = false
        if(backCamera?.torchMode == AVCaptureDevice.TorchMode.on){
            lightEnabled = true
        }

        var canOpenSettings = false
        if #available(iOS 8.0, *) {
            canOpenSettings = true
        }

        var canEnableLight = false
        if(backCamera?.hasTorch == true && backCamera?.isTorchAvailable == true && backCamera?.isTorchModeSupported(AVCaptureDevice.TorchMode.on) == true){
            canEnableLight = true
        }

        var canChangeCamera = false;
        if(backCamera != nil && frontCamera != nil){
            canChangeCamera = true
        }

        let status = [
            "authorized": boolToNumberString(bool: authorized),
            "denied": boolToNumberString(bool: denied),
            "restricted": boolToNumberString(bool: restricted),
            "prepared": boolToNumberString(bool: prepared),
            "scanning": boolToNumberString(bool: scanning),
            "previewing": boolToNumberString(bool: previewing),
            "showing": boolToNumberString(bool: showing),
            "lightEnabled": boolToNumberString(bool: lightEnabled),
            "canOpenSettings": boolToNumberString(bool: canOpenSettings),
            "canEnableLight": boolToNumberString(bool: canEnableLight),
            "canChangeCamera": boolToNumberString(bool: canChangeCamera),
            "currentCamera": String(currentCamera)
        ]

        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: status)
        commandDelegate!.send(pluginResult, callbackId:command.callbackId)
    }

    @objc func isVPNConnected(_ command: CDVInvokedUrlCommand) -> Bool {
        let False = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "false")
        let True = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "true");

        guard let cfDict = CFNetworkCopySystemProxySettings()?.takeRetainedValue() as? [String: Any],
              let scopedDict = cfDict["__SCOPED__"] as? [String: Any] else {
            commandDelegate!.send(False, callbackId:command.callbackId)
            return false
        }

        for key in scopedDict.keys {
            if key.contains("tap") || key.contains("tun") || key.contains("ppp") || key.contains("ipsec") {
                commandDelegate!.send(True, callbackId:command.callbackId)
                return true
            }
        }
        commandDelegate!.send(False, callbackId:command.callbackId)
        return false
    }

    @objc func openSettings(_ command: CDVInvokedUrlCommand) {
        if #available(iOS 10.0, *) {
            guard let settingsUrl = URL(string: UIApplication.openSettingsURLString) else {
            return
        }
        if UIApplication.shared.canOpenURL(settingsUrl) {
            UIApplication.shared.open(settingsUrl, completionHandler: { (success) in
                self.getStatus(command)
            })
        } else {
            self.sendErrorCode(command: command, error: QRScannerError.open_settings_unavailable)
            }
        } else {
            // pre iOS 10.0
            if #available(iOS 8.0, *) {
                UIApplication.shared.openURL(NSURL(string: UIApplication.openSettingsURLString)! as URL)
                self.getStatus(command)
            } else {
                self.sendErrorCode(command: command, error: QRScannerError.open_settings_unavailable)
            }
        }
    }
}
