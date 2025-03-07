package com.bitpay.cordova.qrscanner;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.camera.CameraInstance;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PermissionHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.hardware.Camera;
import android.os.Build;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;


@SuppressWarnings("deprecation")
public class QRScanner extends CordovaPlugin implements BarcodeCallback {

    private CallbackContext callbackContext;
    private boolean cameraClosing;
    private static Boolean flashAvailable;
    private boolean lightOn = false;
    private boolean showing = false;
    private boolean prepared = false;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private String[] permissions = {Manifest.permission.CAMERA};
    //Preview started or paused
    private boolean previewing = false;
    private BarcodeView  mBarcodeView;
    private boolean switchFlashOn = false;
    private boolean switchFlashOff = false;
    private boolean cameraPreviewing;
    private boolean scanning = false;
    private CallbackContext nextScanCallback;
    private boolean shouldScanAgain;
    private boolean denied;
    private boolean authorized;
    private boolean restricted;
    private boolean oneTime = true;
    private boolean keepDenied = false;
    private boolean appPausedWithActivePreview = false;

    static class QRScannerError {
        private static final int UNEXPECTED_ERROR = 0,
                CAMERA_ACCESS_DENIED = 1,
                CAMERA_ACCESS_RESTRICTED = 2,
                BACK_CAMERA_UNAVAILABLE = 3,
                FRONT_CAMERA_UNAVAILABLE = 4,
                CAMERA_UNAVAILABLE = 5,
                SCAN_CANCELED = 6,
                LIGHT_UNAVAILABLE = 7,
                OPEN_SETTINGS_UNAVAILABLE = 8;
    }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        try {
            if (action.equals("show")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        show(callbackContext);
                    }
                });
                return true;
            }
            else if(action.equals("scan")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        scan(callbackContext);
                    }
                });
                return true;
            }
            else if(action.equals("isVPNConnected")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        isVPNConnected(callbackContext);
                    }
                });
                return true;
            }
            else if(action.equals("cancelScan")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        cancelScan(callbackContext);
                    }
                });
                return true;
            }
            else if(action.equals("openSettings")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        openSettings(callbackContext);
                    }
                });
                return true;
            }
            else if(action.equals("pausePreview")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        pausePreview(callbackContext);
                    }
                });
                return true;
            }
            else if(action.equals("useCamera")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        switchCamera(callbackContext, args);
                    }
                });
                return true;
            }
            else if(action.equals("resumePreview")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        resumePreview(callbackContext);
                    }
                });
                return true;
            }
            else if(action.equals("hide")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        hide(callbackContext);
                    }
                });
                return true;
            }
            else if (action.equals("enableLight")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        while (cameraClosing) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ignore) {
                            }
                        }
                        switchFlashOn = true;
                        if (hasFlash()) {
                            if (!hasPermission()) {
                                requestPermission(33);
                            } else
                                enableLight(callbackContext);
                        } else {
                            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
                        }
                    }
                });
                return true;
            }
            else if (action.equals("disableLight")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        switchFlashOff = true;
                        if (hasFlash()) {
                            if (!hasPermission()) {
                                requestPermission(33);
                            } else
                                disableLight(callbackContext);
                        } else {
                            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
                        }
                    }
                });
                return true;
            }
            else if (action.equals("prepare")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    currentCameraId = args.getInt(0);
                                } catch (JSONException e) {
                                }
                                prepare(callbackContext, args);
                            }
                        });
                    }
                });
                return true;
            }
            else if (action.equals("destroy")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        destroy(callbackContext);
                    }
                });
                return true;
            }
            else if (action.equals("getStatus")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        getStatus(callbackContext);
                    }
                });
                return true;
            }
            else {
                return false;
            }
        } catch (Exception e) {
            callbackContext.error(QRScannerError.UNEXPECTED_ERROR);
            return false;
        }
    }

    public static int getUltraWideCameraId(Context context) {
        if(true) {
            return Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        // no way to accurately pick the wide camera without sometimes returning the front camera.
        //        https://stackoverflow.com/questions/57115158/camera-2-cameracharacteristics-seem-to-show-incorrect-data
        CameraManager cameraManager = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        }
        float smallestFocalLength = 99;
        int bestCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (String cameraId : cameraManager.getCameraIdList()) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

                    // Check if this is a back-facing camera
                    Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    Integer sensorOrientation =  characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    Float focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0];
                    Float sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE).getWidth();
//                    Log.d("CameraInfo", "Sensor Orientation: " + sensorOrientation);
                    if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK && sensorOrientation == 90) {

                        // Check for the ultra-wide camera by field of view or other characteristics


                        // Example logic to detect ultra-wide (adjust based on device testing)
                        if (focalLength < smallestFocalLength) { // Replace with your condition
                            smallestFocalLength = focalLength;
                            bestCameraId = Integer.parseInt(cameraId) ;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        return bestCameraId;
    }

    public int getBestCameraId(){
        if(mBarcodeView == null)return 0;
        return getUltraWideCameraId(mBarcodeView.getContext());
    }


    @Override
    public void onPause(boolean multitasking) {
        if (previewing) {
            this.appPausedWithActivePreview = true;
            this.pausePreview(null);
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        if (this.appPausedWithActivePreview) {
            this.appPausedWithActivePreview = false;
            this.resumePreview(null);
        }
    }

    private boolean hasFlash() {
        if (flashAvailable == null) {
            flashAvailable = false;
            final PackageManager packageManager = this.cordova.getActivity().getPackageManager();
            for (final FeatureInfo feature : packageManager.getSystemAvailableFeatures()) {
                if (PackageManager.FEATURE_CAMERA_FLASH.equalsIgnoreCase(feature.name)) {
                    flashAvailable = true;
                    break;
                }
            }
        }
        return flashAvailable;
    }

    private void switchFlash(boolean toggleLight, CallbackContext callbackContext) {
        try {
            if (hasFlash()) {
                doswitchFlash(toggleLight, callbackContext);
            } else {
                callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
            }
        } catch (Exception e) {
            lightOn = false;
            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
        }
    }

    private String boolToNumberString(Boolean bool) {
        if(bool)
            return "1";
        else
            return "0";
    }

    private void doswitchFlash(final boolean toggleLight, final CallbackContext callbackContext) throws IOException, CameraAccessException {        //No flash for front facing cameras
        if (getCurrentCameraId() == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
            return;
        }
        if (!prepared) {
            if (toggleLight)
                lightOn = true;
            else
                lightOn = false;
            prepare(callbackContext, new JSONArray());
        }
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mBarcodeView != null) {
                    mBarcodeView.setTorch(toggleLight);
                    if (toggleLight)
                        lightOn = true;
                    else
                        lightOn = false;
                }
                getStatus(callbackContext);
            }
        });
    }

    public int getCurrentCameraId() {
        return this.currentCameraId;
    }

    private boolean canChangeCamera() {
        int numCameras= Camera.getNumberOfCameras();
        for(int i=0;i<numCameras;i++){
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if(info.CAMERA_FACING_FRONT == info.facing){
                return true;
            }
        }
        return false;
    }

    public void switchCamera(CallbackContext callbackContext, JSONArray args) {
        int cameraId = 0;

        try {
            cameraId = args.getInt(0);
        } catch (JSONException d) {
            callbackContext.error(QRScannerError.UNEXPECTED_ERROR);
        }
        currentCameraId = cameraId;
        if(scanning) {
            scanning = false;
            prepared = false;
            if(cameraPreviewing) {
                this.cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((ViewGroup) mBarcodeView.getParent()).removeView(mBarcodeView);
                        cameraPreviewing = false;
                    }
                });
            }
            closeCamera();
            prepare(callbackContext, new JSONArray());
            scan(this.nextScanCallback);
        }
        else
            prepare(callbackContext, new JSONArray());
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        oneTime = false;
        if (requestCode == 33) {
            // for each permission check if the user granted/denied them
            // you may want to group the rationale in a single dialog,
            // this is just an example
            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
//                     boolean showRationale = AppCompatActivity.shouldShowRequestPermissionRationale(cordova.getActivity(), permission);
//                     if (! showRationale) {
                    // user denied flagging NEVER ASK AGAIN
                    denied = false;
                    authorized = false;
                    callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
                    return;
//                     } else {
//                         authorized = false;
//                         denied = false;
//                         callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
//                         return;
//                     }
                } else if (grantResults[i] == PackageManager.PERMISSION_GRANTED){
                    authorized = true;
                    denied = false;
                    switch (requestCode) {
                        case 33:
                            if(switchFlashOn && !scanning && !switchFlashOff)
                                switchFlash(true, callbackContext);
                            else if(switchFlashOff && !scanning)
                                switchFlash(false, callbackContext);
                            else {
                                setupCamera(callbackContext, new JSONArray());
                                if(!scanning)
                                    getStatus(callbackContext);
                            }
                            break;
                    }
                }
                else {
                    authorized = false;
                    denied = false;
                    restricted = false;
                }
            }
        }
    }

    public boolean hasPermission() {
        for(String p : permissions)
        {
            if(!PermissionHelper.hasPermission(this, p))
            {
                return false;
            }
        }
        return true;
    }

    private void requestPermission(int requestCode) {
        PermissionHelper.requestPermissions(this, requestCode, permissions);
    }

    private void closeCamera() {
        cameraClosing = true;
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mBarcodeView != null) {
                    mBarcodeView.pause();
                }

                cameraClosing = false;
            }
        });
    }

    private void makeOpaque() {
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.getView().setBackgroundColor(Color.TRANSPARENT);
            }
        });
        showing = false;
    }

    private boolean hasCamera() {
        if (this.cordova.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)){
            return true;
        } else {
            return false;
        }
    }

    private boolean hasFrontCamera() {
        if (this.cordova.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)){
            return true;
        } else {
            return false;
        }
    }
    private void setupCamera(CallbackContext callbackContext, final JSONArray args) {
        boolean only2d = false;
        for (int i = 0; i < args.length(); i++) {
            try {
                if (args.getString(i).equals("only-2d")) {
                    only2d = true;
                    break;
                }
            }catch(Exception e){

            }
        }
        final boolean only2dCodes = only2d;
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Create our Preview view and set it as the content of our activity.
                mBarcodeView = new BarcodeView(cordova.getActivity());
                currentCameraId = getBestCameraId();
                //Configure the decoder
                ArrayList<BarcodeFormat> formatList = new ArrayList<BarcodeFormat>();
//                formatList.add(BarcodeFormat.ITF);
//                formatList.add(BarcodeFormat.MAXICODE);
                if(only2dCodes){
                    formatList.add(BarcodeFormat.DATA_MATRIX);
                    formatList.add(BarcodeFormat.QR_CODE);
                }else{
                    formatList.add(BarcodeFormat.CODABAR);
                    formatList.add(BarcodeFormat.CODE_128);
                    formatList.add(BarcodeFormat.CODE_39);
                    formatList.add(BarcodeFormat.CODE_93);
                    formatList.add(BarcodeFormat.DATA_MATRIX);
                    formatList.add(BarcodeFormat.EAN_13);
                    formatList.add(BarcodeFormat.EAN_8);
                    formatList.add(BarcodeFormat.QR_CODE);
                    formatList.add(BarcodeFormat.RSS_14);
                    formatList.add(BarcodeFormat.RSS_EXPANDED);
                    formatList.add(BarcodeFormat.UPC_A);
                    formatList.add(BarcodeFormat.UPC_E);
                    formatList.add(BarcodeFormat.UPC_EAN_EXTENSION);
                }



//                 formatList.add(BarcodeFormat.ITF);
//                 formatList.add(BarcodeFormat.PDF_417);
//                 formatList.add(BarcodeFormat.AZTEC);
                Map<DecodeHintType, Object> hints = new HashMap<>();
                hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
                mBarcodeView.setDecoderFactory(new DefaultDecoderFactory(formatList, hints, null));

                //Configure the camera (front/back)
                CameraSettings settings = new CameraSettings();
                settings.setRequestedCameraId(getCurrentCameraId());
                mBarcodeView.setCameraSettings(settings);

                FrameLayout.LayoutParams cameraPreviewParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                ((ViewGroup) webView.getView().getParent()).addView(mBarcodeView, cameraPreviewParams);

                cameraPreviewing = true;
                webView.getView().bringToFront();

                mBarcodeView.resume();
            }
        });
        prepared = true;
        previewing = true;
        if(shouldScanAgain)
            scan(callbackContext);

    }

    @Override
    public void barcodeResult(BarcodeResult barcodeResult) {
        if (this.nextScanCallback == null) {
            return;
        }

        if(barcodeResult.getText() != null) {
            scanning = false;
            this.nextScanCallback.success("{\"text\":\"" + barcodeResult.getText() + "\",\"type\":\""+barcodeResult.getBarcodeFormat().toString()+"\"}");
            this.nextScanCallback = null;
        }
        else {
            scan(this.nextScanCallback);
        }
    }

    @Override
    public void possibleResultPoints(List<ResultPoint> list) {
    }

    // ---- BEGIN EXTERNAL API ----
    private void prepare(final CallbackContext callbackContext, final JSONArray args) {
        if(!prepared) {
            if(currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
                if (hasCamera()) {
                    if (!hasPermission()) {
                        requestPermission(33);
                    }
                    else {
                        setupCamera(callbackContext, args);
                        if (!scanning)
                            getStatus(callbackContext);
                    }
                }
                else {
                    callbackContext.error(QRScannerError.BACK_CAMERA_UNAVAILABLE);
                }
            }
            else if(currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                if (hasFrontCamera()) {
                    if (!hasPermission()) {
                        requestPermission(33);
                    }
                    else {
                        setupCamera(callbackContext, args);
                        if (!scanning)
                            getStatus(callbackContext);
                    }
                }
                else {
                    callbackContext.error(QRScannerError.FRONT_CAMERA_UNAVAILABLE);
                }
            }
            else {
                callbackContext.error(QRScannerError.CAMERA_UNAVAILABLE);
            }
        }
        else {
            prepared = false;
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBarcodeView.pause();
                }
            });
            if(cameraPreviewing) {
                this.cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((ViewGroup) mBarcodeView.getParent()).removeView(mBarcodeView);
                        cameraPreviewing = false;
                    }
                });

                previewing = true;
                lightOn = false;
            }
            setupCamera(callbackContext, args);
            getStatus(callbackContext);
        }
    }

    private void scan(final CallbackContext callbackContext) {
        scanning = true;
        if (!prepared) {
            shouldScanAgain = true;
            if (hasCamera()) {
                if (!hasPermission()) {
                    requestPermission(33);
                } else {
                    setupCamera(callbackContext, new JSONArray());
                }
            }
        } else {
            if(!previewing) {
                this.cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if(mBarcodeView != null) {
                            mBarcodeView.resume();
                            previewing = true;
                            if(switchFlashOn)
                                lightOn = true;
                        }
                    }
                });
            }
            shouldScanAgain = false;
            this.nextScanCallback = callbackContext;
            final BarcodeCallback b = this;
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mBarcodeView != null) {
                        mBarcodeView.decodeSingle(b);
                    }
                }
            });
        }
    }

    private void cancelScan(final CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                scanning = false;
                if (mBarcodeView != null) {
                    mBarcodeView.stopDecoding();
                }
            }
        });
        if(this.nextScanCallback != null)
            this.nextScanCallback.error(QRScannerError.SCAN_CANCELED);
        this.nextScanCallback = null;
    }

    private void show(final CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.getView().setBackgroundColor(Color.argb(1, 0, 0, 0));
                showing = true;
                getStatus(callbackContext);
            }
        });
    }

    private void hide(final CallbackContext callbackContext) {
        makeOpaque();
        getStatus(callbackContext);
    }

    private void pausePreview(final CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(mBarcodeView != null) {
                    mBarcodeView.pause();
                    previewing = false;
                    if(lightOn)
                        lightOn = false;
                }

                if (callbackContext != null)
                    getStatus(callbackContext);
            }
        });

    }

    private void resumePreview(final CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(mBarcodeView != null) {
                    mBarcodeView.resume();
                    previewing = true;
                    if(switchFlashOn)
                        lightOn = true;
                }

                if (callbackContext != null)
                    getStatus(callbackContext);
            }
        });
    }

    private void enableLight(CallbackContext callbackContext) {
        lightOn = true;
        if(hasPermission())
            switchFlash(true, callbackContext);
        else callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
    }

    private void isVPNConnected(CallbackContext callbackContext) {
        ConnectivityManager connectivityManager = (ConnectivityManager) cordova.getActivity().getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
                if (networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, "true");
                    callbackContext.sendPluginResult(result);
                }
            }
        }
        PluginResult result = new PluginResult(PluginResult.Status.OK, "false");
        callbackContext.sendPluginResult(result);
    }
    private void disableLight(CallbackContext callbackContext) {
        lightOn = false;
        switchFlashOn = false;
        if(hasPermission())
            switchFlash(false, callbackContext);
        else callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
    }

    private void openSettings(CallbackContext callbackContext) {
        oneTime = true;
        if(denied)
            keepDenied = true;
        try {
            denied = false;
            authorized = false;
            boolean shouldPrepare = prepared;
            boolean shouldFlash = lightOn;
            boolean shouldShow = showing;
            if(prepared)
                destroy(callbackContext);
            lightOn = false;
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri = Uri.fromParts("package", this.cordova.getActivity().getPackageName(), null);
            intent.setData(uri);
            this.cordova.getActivity().getApplicationContext().startActivity(intent);
            getStatus(callbackContext);
            if (shouldPrepare)
                prepare(callbackContext, new JSONArray());
            if (shouldFlash)
                enableLight(callbackContext);
            if (shouldShow)
                show(callbackContext);
        } catch (Exception e) {
            callbackContext.error(QRScannerError.OPEN_SETTINGS_UNAVAILABLE);
        }

    }

    private void getStatus(CallbackContext callbackContext) {

        if(oneTime) {
            boolean authorizationStatus = hasPermission();

            authorized = false;
            if (authorizationStatus)
                authorized = true;

            if(keepDenied && !authorized)
                denied = true;
            else
                denied = false;

            //No applicable API
            restricted = false;
        }
        boolean canOpenSettings = true;

        boolean canEnableLight = hasFlash();

        if(currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT)
            canEnableLight = false;

        HashMap status = new HashMap();
        status.put("authorized",boolToNumberString(authorized));
        status.put("denied",boolToNumberString(denied));
        status.put("restricted",boolToNumberString(restricted));
        status.put("prepared",boolToNumberString(prepared));
        status.put("scanning",boolToNumberString(scanning));
        status.put("previewing",boolToNumberString(previewing));
        status.put("showing",boolToNumberString(showing));
        status.put("lightEnabled",boolToNumberString(lightOn));
        status.put("canOpenSettings",boolToNumberString(canOpenSettings));
        status.put("canEnableLight",boolToNumberString(canEnableLight));
        status.put("canChangeCamera",boolToNumberString(canChangeCamera()));
        status.put("currentCamera",Integer.toString(getCurrentCameraId()));

        JSONObject obj = new JSONObject(status);
        PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
        callbackContext.sendPluginResult(result);
    }

    private void destroy(CallbackContext callbackContext) {
        prepared = false;
        makeOpaque();
        previewing = false;
        if(scanning) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanning = false;
                    if (mBarcodeView != null) {
                        mBarcodeView.stopDecoding();
                    }
                }
            });
            this.nextScanCallback = null;
        }

        if(cameraPreviewing) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ((ViewGroup) mBarcodeView.getParent()).removeView(mBarcodeView);
                    cameraPreviewing = false;
                }
            });
        }
        if(currentCameraId != Camera.CameraInfo.CAMERA_FACING_FRONT) {
            if (lightOn)
                switchFlash(false, callbackContext);
        }
        closeCamera();
        currentCameraId = 0;
        getStatus(callbackContext);
    }
}
