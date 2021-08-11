package com.example.myempatica;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.empatica.empalink.ConnectionNotAllowedException;
import com.empatica.empalink.EmpaDeviceManager;
import com.empatica.empalink.EmpaticaDevice;
import com.empatica.empalink.config.EmpaSensorType;
import com.empatica.empalink.config.EmpaStatus;
import com.empatica.empalink.delegate.EmpaDataDelegate;
import com.empatica.empalink.delegate.EmpaStatusDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.tensorflow.lite.Interpreter;

public class MainActivity extends AppCompatActivity implements EmpaStatusDelegate, EmpaDataDelegate, ExpandableListView.OnChildClickListener{

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSION_ACCESS_FINE_LOCATION = 1;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final long SCAN_PERIOD = 10000; //for scanning for bluetooth devices

    //private static final String TEDDY_ADDRESS = "3C:61:05:08:AD:2E"; //in de beer
    private static final String TEDDY_ADDRESS = "3C:61:05:08:AD:76";
    private static final String BLE_SERVICE_UUID = "ab0828b1-198e-4351-b779-901fa0e0371e";
    private static final String BLE_CHARACTERISTIC_UUID ="4ac8a682-9736-4e5d-932b-e9b31405049c";

    private static final String EMPATICA_API_KEY = "f38d7dbb0e9749b1b766d4fdcfe96b1f";
    private EmpaDeviceManager empaDeviceManager;
    private Interpreter interpreter;

    private BLEScanner bleScanner;
    private BluetoothDevice teddyDevice;
    private BluetoothLeService bluetoothService;
    private ArrayList<BluetoothDevice> deviceList;
    private BluetoothGattCharacteristic characteristic;
    private BroadcastReceiver_BTLE_GATT gattUpdateReceiver;

    private boolean empaConnected = false;
    private boolean teddyConnected = false;

    TextView welcome;
    TextView empaticaStatus;
    TextView teddyStatus;
    Button connectEmpatica;
    Button connectTeddy;
    Button negative;
    Button positive;

    LinearLayout dataLayout;
    TextView bvp;
    TextView eda;
    TextView temp;
    TextView acc_x;
    TextView acc_y;
    TextView acc_z;
    TextView stress;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bluetoothService = ((BluetoothLeService.LocalBinder) service).getService();
            if (bluetoothService != null) {
                if (!bluetoothService.initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth");
                    finish();
                } else {
                    updateLabel(teddyStatus, "connecting...");
                    bluetoothService.connect(teddyDevice);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bluetoothService = null;
        }
    };

    public static IntentFilter makeGattUpdateIntentFilter() {

        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);

        return intentFilter;
    }

    /*********************************************************************************
     *Android Activity functions
     ********************************************************************************/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        welcome = findViewById(R.id.tv_welcome);
        welcome.setText("Welcome! Use the buttons below to connect to the Empatica device and the Teddy Bear.");

        empaticaStatus = findViewById(R.id.tv_empatica);
        empaticaStatus.setText("no empatica device connected");
        connectEmpatica = findViewById(R.id.btn_empatica);
        connectEmpatica.setText("Connect Empatica");
        connectEmpatica.setOnClickListener(v -> {
            empaButton();
            //initEmpatica();
        });

        teddyStatus = findViewById(R.id.tv_teddy);
        teddyStatus.setText("no teddy bear device connected");
        connectTeddy = findViewById(R.id.btn_teddy);
        connectTeddy.setText("Connect Teddy Bear");
        connectTeddy.setOnClickListener(v -> {
            initTeddy();
        });

        dataLayout = findViewById(R.id.dataLayout);
        dataLayout.setVisibility(View.INVISIBLE);
        bvp = findViewById(R.id.bvp);
        eda = findViewById(R.id.eda);
        temp = findViewById(R.id.temp);
        acc_x = findViewById(R.id.acc_x);
        acc_y = findViewById(R.id.acc_y);
        acc_z = findViewById(R.id.acc_z);
        stress = findViewById(R.id.stress);

        negative = findViewById(R.id.button_neg);
        negative.setOnClickListener(v -> {
            sendZero();
        });

        positive = findViewById(R.id.button_pos);
        positive.setOnClickListener(v -> {
            sendOne();
        });

        gattUpdateReceiver = new BroadcastReceiver_BTLE_GATT(this);
        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter());

        //tensorflow model interpreter
        try {
            interpreter = new Interpreter(loadModel(), null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.P)
    @Override
    protected void onStart() {
        super.onStart();
        //check fine location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSION_ACCESS_FINE_LOCATION);
        }
        //check if bluetooth is enabled
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBT = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBT, REQUEST_ENABLE_BT);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSION_ACCESS_FINE_LOCATION:
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission was granted
                } else {
                    // ask permission
                    new AlertDialog.Builder(this)
                            .setTitle("Location permission required")
                            .setMessage("Without this permission bluetooth low energy devices cannot be found, allow it in order to connect to the device.")
                            .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                        // the "never ask again" flag is set so the permission requests is disabled, try open app settings to enable the permission
                                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                                        intent.setData(uri);
                                        startActivity(intent);
                                }
                            })
                            .setNegativeButton("Exit application", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    // without permission exit is the only way
                                    finish();
                                }
                            })
                            .show();
                }
                break;
        }
    }
    /**************************************************************************
     * Teddybeer methods (BLE)
     *************************************************************************/
    private void initTeddy() {
        deviceList = new ArrayList<>();

        bleScanner = new BLEScanner(this, SCAN_PERIOD);
        startScanning();
    }

    private void startScanning() {
        updateLabel(teddyStatus, "start scanning...");
        bleScanner.start();
    }

    public void stopScanning() {
        updateLabel(teddyStatus, "stop scan");
        bleScanner.stop();
        for(BluetoothDevice d : deviceList){
            if(d.getAddress().equals(TEDDY_ADDRESS)){
                teddyConnected = true;
                teddyDevice = d;
                Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
                bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            }
        }
    }

    public void addDevice(BluetoothDevice device) {
        deviceList.add(device);
    }

    public void updateServices() {
        if (bluetoothService != null) {
            List<BluetoothGattService> servicesList = bluetoothService.getSupportedGattServices();
            for (BluetoothGattService service : servicesList) {
                if(service.getUuid().toString().equals(BLE_SERVICE_UUID)) {
                    List<BluetoothGattCharacteristic> characteristicsList = service.getCharacteristics();
                    for (BluetoothGattCharacteristic characteristic : characteristicsList) {
                        if(characteristic.getUuid().toString().equals(BLE_CHARACTERISTIC_UUID)){
                            this.characteristic = characteristic;
                        }
                    }
                }
            }
        }
    }

    private void sendOne() {
        if(teddyConnected){
            characteristic.setValue("1");
            bluetoothService.writeCharacteristic(characteristic);
        }
    }

    private void sendZero() {
        if(teddyConnected){
            characteristic.setValue("0");
            bluetoothService.writeCharacteristic(characteristic);
        }
    }

    /*******************************************************************************
     * Empatica methods
     ******************************************************************************/

    private void empaButton(){
        if (empaConnected){
            //disconnect
            empaDeviceManager.disconnect();
        }
        else {
            //connect
            //initEmpatica();
            empaDeviceManager = new EmpaDeviceManager(getApplicationContext(), this, this);
            empaDeviceManager.authenticateWithAPIKey(EMPATICA_API_KEY);
        }
    }

    private void initEmpatica() {
        empaDeviceManager = new EmpaDeviceManager(getApplicationContext(), this, this);
        empaDeviceManager.authenticateWithAPIKey(EMPATICA_API_KEY);
    }

    @Override
    public void didDiscoverDevice(EmpaticaDevice device, String deviceLabel, int rssi, boolean allowed) {
        //allowed when device is linked to API Key
        Log.i(TAG, "didDiscoverDevice" + deviceLabel + "allowed: " + allowed);

        if (allowed) {
            empaDeviceManager.stopScanning();
            try {
                // Connect to the device
                empaDeviceManager.connectDevice(device);
            } catch (ConnectionNotAllowedException e) {
                // This should happen only if you try to connect when allowed == false.
                Toast.makeText(MainActivity.this, "Sorry, you can't connect to this device", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "didDiscoverDevice" + deviceLabel + "allowed: " + allowed + " - ConnectionNotAllowedException", e);
            }
        }
    }

    @Override
    public void didEstablishConnection() {
        empaConnected = true;
    }

    @Override
    public void didFailedScanning(int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                Log.e(TAG,"Scan failed: a BLE scan with the same settings is already started by the app");
                break;
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                Log.e(TAG,"Scan failed: app cannot be registered");
                break;
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                Log.e(TAG,"Scan failed: power optimized scan feature is not supported");
                break;
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                Log.e(TAG,"Scan failed: internal error");
                break;
            default:
                Log.e(TAG,"Scan failed with unknown error (errorCode=" + errorCode + ")");
                break;
        }
    }

    @Override
    public void didUpdateStatus(EmpaStatus status) {
        switch (status){
            case READY:
                empaDeviceManager.startScanning();
                updateLabel(empaticaStatus, "turn on your Empatica E4");
                break;
            case DISCOVERING:
                updateLabel(empaticaStatus, "scanning for devices...");
                break;
            case CONNECTING:
                updateLabel(empaticaStatus, "connecting...");
                break;
            case CONNECTED:
                updateLabel(empaticaStatus, "connected to: " + empaDeviceManager.getActiveDevice().getName());
                updateButton(connectEmpatica, "Disconnect Empatica");
                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        dataLayout.setVisibility(View.VISIBLE);
                    }
                });
                //start evaluating tensorflow model
                inference();
                break;
            case DISCONNECTING:
                updateLabel(empaticaStatus, "disconnecting...");
                break;
            case DISCONNECTED:
                updateLabel(empaticaStatus, "disconnected");
                updateButton(connectEmpatica, "Connect Empatica");
                empaConnected = false;
                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        dataLayout.setVisibility(View.INVISIBLE);
                    }
                });
                break;
        }
    }


    @Override
    public void didReceiveGSR(float gsr, double timestamp) {
        updateLabel(eda, ""+gsr);
    }

    @Override
    public void didReceiveBVP(float bvp, double timestamp) {
        updateLabel(this.bvp, ""+bvp);
    }

    @Override
    public void didReceiveIBI(float ibi, double timestamp) {

    }

    @Override
    public void didReceiveTemperature(float t, double timestamp) {
        updateLabel(temp, ""+t);

    }

    @Override
    public void didReceiveAcceleration(int x, int y, int z, double timestamp) {
        updateLabel(acc_x, ""+x);
        updateLabel(acc_y, ""+y);
        updateLabel(acc_z, ""+z);
    }

    @Override
    public void didReceiveBatteryLevel(float level, double timestamp) {

    }

    @Override
    public void didReceiveTag(double timestamp) {
        stressResponse(1);
    }

    @Override
    public void didUpdateSensorStatus(int status, EmpaSensorType type) {

    }

    @Override
    public void didRequestEnableBluetooth() {

    }

    @Override
    public void bluetoothStateChanged() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBT = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBT, REQUEST_ENABLE_BT);
        }
    }

    @Override
    public void didUpdateOnWristStatus(int status) {

    }

    /**************************************************************
     * Neural Network methods
     **************************************************************/


    private MappedByteBuffer loadModel() throws IOException {
        AssetFileDescriptor assetFileDescriptor = this.getAssets().openFd("linear.tflite");
        FileInputStream fileInputStream = new FileInputStream(assetFileDescriptor.getFileDescriptor());
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = assetFileDescriptor.getStartOffset();
        long length = assetFileDescriptor.getLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, length);
    }

    public void inference(){
        Timer timer = new Timer();
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                float[] input = new float[6];
                input[0] = Float.parseFloat(bvp.getText().toString());
                input[1] = Float.parseFloat(eda.getText().toString());
                input[2] = Float.parseFloat(temp.getText().toString());
                input[3] = Float.parseFloat(acc_x.getText().toString());
                input[4] = Float.parseFloat(acc_y.getText().toString());
                input[5] = Float.parseFloat(acc_z.getText().toString());

                float[][] output = new float[1][1];
                interpreter.run(input, output);
                stressResponse(output[0][0]);
            }
        };
        timer.schedule(timerTask, 2000, 2000);
    }

    private void stressResponse(float response){
        int stress = Math.round(response);
        if(stress == 1){
            updateLabel(this.stress, "stress detected");
            if(teddyConnected){
                characteristic.setValue("1");
                bluetoothService.writeCharacteristic(characteristic);
            }
        }
        else{
            updateLabel(this.stress, "not stressed");
            if(teddyConnected){
                characteristic.setValue("0");
                bluetoothService.writeCharacteristic(characteristic);
            }
        }
    }

    /***********************************************************************
     * UI methods
     ***********************************************************************/
    //change one of the textviews
    private void updateLabel(TextView label, String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                label.setText(status);
            }
        });
    }

    private void updateButton(Button button, String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                button.setText(text);
            }
        });
    }

    @Override
    public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {

        return false;
    }
}

