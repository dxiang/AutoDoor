package com.xiang.dennis.autodoor;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


public class MainActivity extends AppCompatActivity {

    private final static String TAG = MainActivity.class.getSimpleName();
    private final static int MY_PERMISSIONS_REQUEST = 1;

    private Button unlock_button = null;
    private EditText passcode_field = null;

    // Bluetooth objects
    private BluetoothAdapter BLE_Adapter;
    private BluetoothLeScanner BLE_Scanner;
    private BluetoothGattCharacteristic characteristicTx = null;
    private RBLService mBluetoothLeService;
    private BluetoothDevice BLE_Device = null;

    private Handler mHandler = new Handler();

    private String BLE_DeviceAddress;

    private boolean flag = true;

    private byte[] data = new byte[3];
    private static final long SCAN_PERIOD = 2000;
    private static final int REQUEST_ENABLE_BT = 1;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Initialize bluetooth adapter
        final BluetoothManager mBluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BLE_Adapter = mBluetoothManager.getAdapter();
        BLE_Scanner = BLE_Adapter.getBluetoothLeScanner();

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (BLE_Adapter == null || !BLE_Adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        // Request user permission for location services
        ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION},
                MY_PERMISSIONS_REQUEST);

        // Scan for the RBL Shield
        scanLeDevice();

        // After scan period of SCAN_TIME, connect to the device
        Timer mTimer = new Timer();
        mTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                if (BLE_Device != null) {
                    BLE_DeviceAddress = BLE_Device.getAddress();
                    mBluetoothLeService.connect(BLE_DeviceAddress);
                }
                else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast toast = Toast
                                    .makeText(
                                            MainActivity.this,
                                            "Couldn't find bluetooth device!",
                                            Toast.LENGTH_SHORT);
                            toast.setGravity(0, 0, Gravity.CENTER);
                            toast.show();
                        }
                    });
                    finish(); // Couldnt find device, so quit application
                }
            }
        }, SCAN_PERIOD);

        // Bind RBL Service to MainActivity
        Intent gattServiceIntent = new Intent(MainActivity.this, RBLService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        passcode_field = (EditText) findViewById(R.id.txt_passcode);
        unlock_button = (Button) findViewById(R.id.btn_unlock);
        unlock_button.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                    String str = passcode_field.getText().toString();
                    byte[] tmp = str.getBytes();

                    characteristicTx.setValue(tmp);
                    Log.i(TAG, "set characteristic");
                    mBluetoothLeService.writeCharacteristic(characteristicTx);
            }

        });

        disableUI(); // disables UI until connected to BLE device

    } // End onCreate()


    private void scanLeDevice() {

        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(RBLGattAttributes.BLE_SHIELD_SERVICE)).build();
        ScanSettings settings = new ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build();
        List<ScanFilter> filter_list = new ArrayList<>();
        filter_list.add(filter);
        BLE_Scanner.startScan(filter_list, settings, BLE_ScanCallback);

        // Stops scanning after a pre-defined scan period.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                BLE_Scanner.stopScan(BLE_ScanCallback);
            }
        }, SCAN_PERIOD);

    }


    private ScanCallback BLE_ScanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callBackType, final android.bluetooth.le.ScanResult scan_result) {

            super.onScanResult(callBackType, scan_result);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BLE_Device = scan_result.getDevice();
                    Log.i(TAG, "Bluetooth Device found! Name: " + BLE_Device.getName() + " Address: " + BLE_Device.getAddress());
                }
            });
        }
    };


    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {

            mBluetoothLeService = ((RBLService.LocalBinder) service).getService();

            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth service");
                finish();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (RBLService.ACTION_GATT_DISCONNECTED.equals(action)) {
                Toast.makeText(getApplicationContext(), "Disconnected",
                        Toast.LENGTH_SHORT).show();
                disableUI();
            } else if (RBLService.ACTION_GATT_SERVICES_DISCOVERED
                    .equals(action)) {
                Toast.makeText(getApplicationContext(), "Connected",
                        Toast.LENGTH_SHORT).show();

                getGattService(mBluetoothLeService.getSupportedGattService());
            } else if (RBLService.ACTION_DATA_AVAILABLE.equals(action)) {
                data = intent.getByteArrayExtra(RBLService.EXTRA_DATA);

            } else if (RBLService.ACTION_GATT_RSSI.equals(action)) {
                //displayData(intent.getStringExtra(RBLService.EXTRA_DATA));
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();

        Log.i(TAG, "Resuming Activity");

        if (!BLE_Adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }




    private void getGattService(BluetoothGattService gattService) {
        if (gattService == null)
            return;

        enableUI();
        startReadRssi();

        characteristicTx = gattService
                .getCharacteristic(RBLService.UUID_BLE_SHIELD_TX);

        BluetoothGattCharacteristic characteristicRx = gattService
                .getCharacteristic(RBLService.UUID_BLE_SHIELD_RX);
        mBluetoothLeService.setCharacteristicNotification(characteristicRx,
                true);
        mBluetoothLeService.readCharacteristic(characteristicRx);
    }


    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(RBLService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(RBLService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(RBLService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(RBLService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(RBLService.ACTION_GATT_RSSI);

        return intentFilter;
    }


    private void startReadRssi() {
        new Thread() {
            public void run() {

                while (flag) {
                    mBluetoothLeService.readRssi();
                    try {
                        sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            };
        }.start();
    }


    // disables the UI
    private void disableUI() {
        unlock_button.setEnabled(false);
        passcode_field.setEnabled(false);
    }


    // enables the UI
    private void enableUI() {
        unlock_button.setEnabled(true);
        passcode_field.setEnabled(true);
    }

    @Override
    protected void onStop() {
        super.onStop();

        flag = false;

        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mServiceConnection != null)
            unbindService(mServiceConnection);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT
                && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay! do nothing here, continue on with onCreate() method
                }
                else {
                    // permission denied, boo! display message
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast toast = Toast
                                    .makeText(
                                            MainActivity.this,
                                            "This app needs location services enabled! Closing app...",
                                            Toast.LENGTH_SHORT);
                            toast.setGravity(0, 0, Gravity.CENTER);
                            toast.show();
                        }
                    });
                    // close app
                    finish();
                }

                return;
            }
        }
    }

}
