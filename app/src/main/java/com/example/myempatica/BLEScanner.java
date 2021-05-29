package com.example.myempatica;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

public class BLEScanner {

    private MainActivity activity;
    private boolean isScanning;
    private Handler handler;
    private long scanPeriod;

    private final BluetoothManager bluetoothManager;

    public BLEScanner(MainActivity mainActivity, long period) {
        activity = mainActivity;
        scanPeriod = period;
        handler = new Handler();
        bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    public void start() {
        if(!isScanning){
            //handler executes after delay period
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    isScanning = false;
                    bluetoothManager.getAdapter().getBluetoothLeScanner().stopScan(leScanCallback);
                    activity.stopScanning();
                }
            }, scanPeriod);

            //start scanning
            isScanning = true;
            bluetoothManager.getAdapter().getBluetoothLeScanner().startScan(leScanCallback);
        }
        else {
            isScanning = false;
            bluetoothManager.getAdapter().getBluetoothLeScanner().stopScan(leScanCallback);
        }
    }

    public void stop() {
        isScanning = false;
        bluetoothManager.getAdapter().getBluetoothLeScanner().stopScan(leScanCallback);
    }

    private ScanCallback leScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            activity.addDevice(result.getDevice());
                        }
                    });
                }
            } ;
}
