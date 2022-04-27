package com.example.myapplication;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity {

	// A UUID is a standardized 128-bit format for a string ID used to uniquely identify information.
	// A UUID is used to identify information that needs to be unique within a system
	// or a network because the probability of a UUID being repeated is effectively zero.
	// It is generated independently, without the use of a centralized authority.
	// In this case, it's used to uniquely identify your app's Bluetooth service.
	// To get a UUID to use with your app, you can use one of the many random UUID generators on the web,
	// then initialize a UUID with fromString(String).
	private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // "random" unique identifier
	private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

	// Name for the SDP record when creating server socket
	private static final String NAME_SECURE = "BlueToothApplicationSecure";
	private static final String NAME_INSECURE = "BlueToothApplicationInsecure";

	// Unique UUID for this application
	private static final UUID MY_UUID_SECURE = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
	private static final UUID MY_UUID_INSECURE = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

	// Message types sent from the Bluetooth Service Handler
	private static final int GATT_MAX_MTU_SIZE = 517; // ATT Maximum Transmission Unit (MTU) determines the maximum length of an ATT data packet
	// helpful guide: https://punchthrough.com/android-ble-guide/
	// helpful blog: https://medium.com/@shahar_avigezer/bluetooth-low-energy-on-android-22bc7310387a
	// source: https://btprodspecificationrefs.blob.core.windows.net/assigned-values/16-bit%20UUID%20Numbers%20Document.pdf
	// and it was found at https://www.bluetooth.com/specifications/assigned-numbers/
	// other key ref: https://www.bluetooth.com/specifications/specs/?status=active&show_latest_version=0&show_latest_version=1&keyword=weight&filter=
	// vendor mac address search: https://macvendors.com/
	private static final UUID WEIGHT_UUID = intToUUID(0x2A98);
	private static final UUID WEIGHT_MEASUREMENT_UUID = intToUUID(0x2A9D);
	private static final UUID WEIGHT_SCALE_UUID = intToUUID(0x181D);
	private static final UUID WEIGHT_SCALE_FEATURE_UUID = intToUUID(0x2A9E);
	private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = intToUUID(0x2902);
	private static final UUID SERVICE_UUID = UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb");
	private static final UUID CONFIRM_UUID = UUID.fromString("36d4dc5c-814b-4097-a5a6-b93b39085928"); // UUID to confirm device connection
	public static String EXTRA_DEVICE_ADDRESS = "device_address";
	private final String[] permissions = {
			"android.permission.ACCESS_FINE_LOCATION",
			"android.permission.BLUETOOTH_CONNECT",
			"android.permission.BLUETOOTH_SCAN"
	};
	private final String TAG = MainActivity.class.getSimpleName();
	// Key names received from the Bluetooth Service Handler
	String DEVICE_INFO = "device_info";
	BluetoothManager bluetoothManager;
	String TOAST = "toast";
	String readMessage = null;
	boolean fail = false;
	Intent intent;
	// GUI Components
	private TextView mBluetoothStatus;
	private TextView mReadBuffer;
	private EditText mCurrentWeight;
	/**
	 * The Handler that gets information back from the Bluetooth Service
	 */
	private final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			Timber.tag(TAG).i(msg.toString());

			switch (msg.what) {
				case MessageConstants.MESSAGE_STATE_CHANGE:
					switch (msg.arg1) {
						case MessageConstants.STATE_CONNECTED:
							String name = msg.getData().getString(DEVICE_INFO);
							String info = "Connected to " + name;
							Timber.tag(TAG).i("STATE_CONNECTED %s", info);
							mBluetoothStatus.setText(info);
							break;

						case MessageConstants.STATE_CONNECTING:
							mBluetoothStatus.setText(getString(R.string.connecting));
							break;

						case MessageConstants.STATE_LISTEN:
							mBluetoothStatus.setText(getString(R.string.scanning));
							break;

						case MessageConstants.STATE_NONE:
							mBluetoothStatus.setText(getString(R.string.not_connected));
							break;
					}
					break;

				case MessageConstants.MESSAGE_READ:
					mBluetoothStatus.setText(getString(R.string.receiving_input));

					byte[] readBuf = (byte[]) msg.obj;
					// byte[] weight = parseMessage(readBuf);
					// readMessage = new String(weight, StandardCharsets.UTF_8).trim();

					// construct a string from the valid bytes in the buffer
					readMessage = new String(readBuf, 0, msg.arg1);
					mCurrentWeight.setText(readMessage);
					break;

				case MessageConstants.MESSAGE_WRITE:
					mBluetoothStatus.setText(getString(R.string.writing_output));

					byte[] writeBuf = (byte[]) msg.obj;

					// construct a string from the buffer
					String writeMessage = new String(writeBuf);
					break;

				case MessageConstants.MESSAGE_DEVICE_INFO:
					String name = msg.getData().getString(DEVICE_INFO);
					String info = "Selected " + name;
					Timber.tag(TAG).i("MESSAGE_DEVICE_INFO %s", info);
					mBluetoothStatus.setText(info);
					break;

				case MessageConstants.MESSAGE_TOAST:
					mBluetoothStatus.setText(msg.getData().getString(TOAST));
					break;
			}
		}
	};

	private BluetoothGatt gattClient;
	private boolean isScanning = false;
	private ScanCallback scanCallback;
	private BluetoothAdapter bluetoothAdapter;
	private BluetoothGattCallback gattClientCallback;
	private Context mContext;
	private BluetoothLeScanner bleScanner;
	private final AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
		public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
			checkBluetoothPermission();

			if (!bluetoothAdapter.isEnabled()) {
				Toast.makeText(getBaseContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
			} else {
				// Clear the input field
				mCurrentWeight.setText(null);

				// Get the device MAC address, which is the last 17 chars in the View
				String info = ((TextView) v).getText().toString();
				final String address = info.substring(info.length() - 17);
				final String name = info.substring(info.length() - 20, info.length() - 17);

				BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);

				// Send the name of the connected device back to the UI Activity
				Message msg = mHandler.obtainMessage(MessageConstants.MESSAGE_DEVICE_INFO);
				Bundle bundle = new Bundle();
				bundle.putString(DEVICE_INFO, device.getName() + " (" + device.getAddress() + ")");
				msg.setData(bundle);
				mHandler.sendMessage(msg);

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					// Always stop your BLE scan before connecting to a BLE device.
					// Doing so saves power and more importantly, helps increase the reliability of the connection process.
					stopBleScan();

					// Setting autoConnect to true doesn’t cause Android to automatically try to reconnect to the BluetoothDevice!
					// Instead, it causes the connect operation to not timeout,
					// which can be helpful if you’re trying to connect to a BluetoothDevice you had cached from earlier.
					// However, setting the flag to true may result in a slower than usual connection process
					// if the device is already discoverable in the immediate vicinity.
					// Preferably set the flag to false, and instead rely on the onConnectionStateChange callback
					// to inform us on whether the connection process succeeded.
					// In the event of a connection failure, we can simply try to connect again with autoConnect set to false.
					gattClient = device.connectGatt(mContext, false, gattClientCallback);
				} else {
					ConnectThread mConnectThread = new ConnectThread(device);
					mConnectThread.start();
				}
			}
		}
	};
	private ScanSettings scanSettings;
	private ArrayAdapter<String> mBTArrayAdapter;
	/**
	 * BroadcastReceiver for ACTION_FOUND.
	 * Caution: Performing device discovery consumes a lot of the Bluetooth adapter's resources.
	 * After you have found a device to connect to, be certain that you stop discovery with cancelDiscovery() before attempting a connection.
	 * Also, you shouldn't perform discovery while connected to a device because the discovery process
	 * significantly reduces the bandwidth available for any existing connections.
	 */
	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				checkBluetoothPermission();

				// Discovery has found a device. Get the BluetoothDevice
				// object and its info from the Intent.
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				// add the name to the list
				// failed to identify vendor when searching device address via https://macvendors.com/
				String deviceInfo = "Tap to connect to " + device.getName() + "\n" + device.getAddress();
				if (mBTArrayAdapter.getPosition(deviceInfo) < 0) {
					mBTArrayAdapter.add(deviceInfo);
					mBTArrayAdapter.notifyDataSetChanged();
				}
			}
		}
	};
	private ConnectedThread mConnectedThread; // bluetooth background worker thread to send and receive data

	private static UUID intToUUID(int i) {
		final long MSB = 0x0000000000001000L;
		final long LSB = 0x800000805f9b34fbL;
		long value = i & 0xFFFFFFFF;
		return new UUID(MSB | (value << 32), LSB);
	}

	public static String bytesToHex(byte[] bytes) {
		byte[] hexChars = new byte[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEX_ARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}
		return new String(hexChars, StandardCharsets.UTF_8);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mContext = this;
		mBluetoothStatus = findViewById(R.id.bluetoothStatus);
		Button mDiscoverBtn = findViewById(R.id.discover);
		Button mSetWeight = findViewById(R.id.setWeight);
		mCurrentWeight = findViewById(R.id.kgs);

		mBTArrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
		bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		// bluetoothAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio
		bluetoothAdapter = bluetoothManager.getAdapter(); // for BLE

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			bleScanner = bluetoothAdapter.getBluetoothLeScanner();
			// SCAN_MODE_LOW_LATENCY is recommended if the app will only be scanning for a brief period of time,
			// typically to find a very specific type of device.
			scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
		}

		ListView mDevicesListView = findViewById(R.id.devicesListView);
		mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
		mDevicesListView.setOnItemClickListener(mDeviceClickListener);

		// Ask for location permission if not already allowed
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			int LOCATION_PERMISSION_REQUEST_CODE = 2;
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
		}

		if (mBTArrayAdapter == null) {
			// Device does not support Bluetooth
			mBluetoothStatus.setText(getString(R.string.bt_not_found));
			Toast.makeText(getApplicationContext(), "Bluetooth device not found!", Toast.LENGTH_SHORT).show();
		} else {
			mDiscoverBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
						if (isScanning) {
							stopBleScan();

							// Send a scan stopped message to the activity
							Message writeStopMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
							Bundle bundle = new Bundle();
							bundle.putString(TOAST, "Scan stopped. Click discover to re-scan!");
							writeStopMsg.setData(bundle);
							mHandler.sendMessage(writeStopMsg);
						} else {
							startBleScan();
						}
					} else {
						discover();
					}
				}
			});

			mSetWeight.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					String result = mCurrentWeight.getText().toString();
					String str = result.trim();
					Timber.tag(TAG).d("result: " + result + ", str: " + str + " ... " + Float.parseFloat("5"));
					if (!str.equals("")) {
						// check if float
						try {
							Float.parseFloat(str);

							Intent returnIntent = new Intent();
							returnIntent.putExtra("result", str);
							setResult(Activity.RESULT_OK, returnIntent);
//							finish();
							Toast.makeText(getApplicationContext(), str + " is the value that will be set", Toast.LENGTH_LONG).show();
						} catch (NumberFormatException e) {
							Toast.makeText(getApplicationContext(), "Value does not match format 00.0", Toast.LENGTH_LONG).show();
							Timber.tag(TAG).e(e, "Input is not a float");
						}
					} else {
						Toast.makeText(getApplicationContext(), "No value was supplied for weight!", Toast.LENGTH_LONG).show();
					}
				}
			});

			bluetoothOn();

			// Send a prompt message back to the activity.
			Message writePromptMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
			Bundle bundle = new Bundle();
			bundle.putString(TOAST, "Select Discover to find devices");
			writePromptMsg.setData(bundle);
			mHandler.sendMessage(writePromptMsg);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				startBleScan();
			} else {
				discover();
			}
		}

	}

	private byte[] parseMessage(byte[] buffer) {
		byte[] weight = new byte[7];
		for (int i = 0; i < buffer.length; i++) {
			if (buffer[i] == (byte) 0x0D) {
				int k = 0;
				int start = i - 8;
				int end = i - 2;
				for (int j = start; j <= end; j++) {
					weight[k++] = buffer[j];
				}
			}
		}
		return weight;
	}

	private void bluetoothOn() {
		checkBluetoothPermission();
		if (!bluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			int REQUEST_ENABLE_BT = 4; // must be greater than or equal to 0
			startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
			mBluetoothStatus.setText("Bluetooth ON");
			Toast.makeText(getApplicationContext(), "Bluetooth turned on", Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(getApplicationContext(), "Bluetooth is already on", Toast.LENGTH_SHORT).show();
		}
	}

	// Enter here after user selects "yes" or "no" to enabling radio
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent Data) {
		super.onActivityResult(requestCode, resultCode, Data);

		// Check which request we're responding to
		if (requestCode == MessageConstants.MESSAGE_WRITE) {
			// Make sure the request was successful
			if (resultCode == RESULT_OK) {
				// The user picked a contact.
				// The Intent's data Uri identifies which contact was selected.
				mBluetoothStatus.setText("Bluetooth ON");
			} else
				mBluetoothStatus.setText("Bluetooth OFF");
		}
	}

	private void bluetoothOff() {
		checkBluetoothPermission();
		bluetoothAdapter.disable(); // turn off
		mBluetoothStatus.setText("Bluetooth OFF");
		Toast.makeText(getApplicationContext(), "Bluetooth turned Off", Toast.LENGTH_SHORT).show();
	}

	private void discover() {
		checkBluetoothPermission();
		// Check if the device is already discovering
		if (bluetoothAdapter.isDiscovering()) {
			bluetoothAdapter.cancelDiscovery();
			Toast.makeText(getApplicationContext(), "Discovery stopped", Toast.LENGTH_SHORT).show();
			mBluetoothStatus.setText("Stopped Scan");
		} else {
			if (bluetoothAdapter.isEnabled()) {
				mBTArrayAdapter.clear(); // clear items

				// Request discover from BluetoothAdapter
				bluetoothAdapter.startDiscovery();
				Toast.makeText(getApplicationContext(), "Discovery started", Toast.LENGTH_SHORT).show();
				mBluetoothStatus.setText("Scanning...");

				// Register for broadcasts when a device is discovered.
				registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
			} else {
				Toast.makeText(getApplicationContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
				mBluetoothStatus.setText("Stopped Scan");
			}
		}
	}

	private void startBleScan() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			mBTArrayAdapter.clear();

			scanCallback = new ScanCallback() {
				@Override
				public void onScanResult(int callbackType, ScanResult result) {
					super.onScanResult(callbackType, result);
					checkBluetoothPermission();

					BluetoothDevice device = result.getDevice();

					// add the name to the list
					String deviceInfo = "Tap to connect to " + device.getName() + "\n" + device.getAddress();
					if (mBTArrayAdapter.getPosition(deviceInfo) < 0) {
						mBTArrayAdapter.add(deviceInfo);
						mBTArrayAdapter.notifyDataSetChanged();
					}

					// Give the new state to the Handler so the UI Activity can update
					mHandler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, MessageConstants.STATE_LISTEN, -1).sendToTarget();
				}
			};

			gattClientCallback = new BluetoothGattCallback() {
				@Override
				public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
					super.onConnectionStateChange(gatt, status, newState);
					checkBluetoothPermission();

					BluetoothDevice device = gatt.getDevice();
					if (status == BluetoothGatt.GATT_SUCCESS) {
						if (newState == BluetoothProfile.STATE_CONNECTED) {
							Message msg = mHandler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, MessageConstants.STATE_CONNECTED);
							Bundle bundle = new Bundle();
							bundle.putString(DEVICE_INFO, device.getName() + " (" + device.getAddress() + ")");
							msg.setData(bundle);
							mHandler.sendMessage(msg);

							// It’s crucial to store a reference to the BluetoothGatt object that is also provided by this callback.
							// This will be the main interface through which we issue commands to the BLE device.
							// It’s the gateway to other BLE operations such as service discovery, reading and writing data, and even performing a connection teardown.
							gattClient = gatt;

							// result at onMtuChanged()
							// Unfortunately, the onMtuChanged() callback may not get delivered sometimes when working with closed source firmware,
							// which can put a damper on things if the app is relying on the callback being delivered before proceeding with something.
							// Always assume the worst case — that the ATT MTU is at its minimum value of 23
							// and plan around that when working with closed source firmware,
							// with any successful onMtuChanged() calls being considered as added bonuses.
							gatt.requestMtu(GATT_MAX_MTU_SIZE);

							new Handler(Looper.getMainLooper()).post(new Runnable() {
								@Override
								public void run() {
									checkBluetoothPermission();

									// Highly recommended to call discoverServices() from the main/UI thread
									// to prevent a rare threading issue from causing a deadlock situation
									// where the app can be left waiting for the onServicesDiscovered() callback that somehow got dropped.
									// The outcome of service discovery will be delivered via BluetoothGattCallback’s onServicesDiscovered() method
									gatt.discoverServices();
								}
							});
						} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
							Timber.tag(TAG).d("BluetoothGattCallback: Successfully disconnected from %s", device.getAddress());
							mHandler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, MessageConstants.STATE_NONE, -1, device.getName()).sendToTarget();
							gatt.close();
						} else if (newState == BluetoothProfile.STATE_CONNECTING) {
							mHandler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, MessageConstants.STATE_CONNECTING, -1, device.getName()).sendToTarget();
						}
					} else {
						// Send a connection failed message to the activity
						Message writeStopMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
						Bundle bundle = new Bundle();
						bundle.putString(TOAST, "Error: " + status + "!");
						writeStopMsg.setData(bundle);
						mHandler.sendMessage(writeStopMsg);

						Timber.tag(TAG).d("BluetoothGattCallback: Error " + status + " encountered for " + device.getAddress() + "! Disconnecting...");
						gatt.close();
					}
				}

				@Override
				public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
					super.onMtuChanged(gatt, mtu, status);
					Timber.tag(TAG).w("ATT MTU changed to " + mtu + ", success: " + (status == BluetoothGatt.GATT_SUCCESS));

					gattClient = gatt;

					new Handler(Looper.getMainLooper()).post(new Runnable() {
						@Override
						public void run() {
							checkBluetoothPermission();

							// Highly recommended to call discoverServices() from the main/UI thread
							// to prevent a rare threading issue from causing a deadlock situation
							// where the app can be left waiting for the onServicesDiscovered() callback that somehow got dropped.
							// The outcome of service discovery will be delivered via BluetoothGattCallback’s onServicesDiscovered() method
							gatt.discoverServices();
						}
					});
				}

				@Override
				public void onServicesDiscovered(BluetoothGatt gatt, int status) {
					super.onServicesDiscovered(gatt, status);
					checkBluetoothPermission();

					gattClient = gatt;

					// Test service
					// readBatteryLevel();

					// Get all the available services and characteristics
					List<BluetoothGattService> services = gatt.getServices();
					Timber.tag(TAG).d("BluetoothGattCallback: Discovered " + gatt.getServices().size() + " services for " + gatt.getDevice().getName());
					printGattTable(services);
					// Consider connection setup as complete here
				}

				@Override
				public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
					super.onCharacteristicRead(gatt, characteristic, status);

					Timber.tag(TAG).d("BluetoothGattCallback: Read characteristic " + characteristic.getUuid() + " - status: " + status);

					switch (status) {
						case BluetoothGatt.GATT_SUCCESS:
							checkBluetoothPermission();

							byte[] buffer = characteristic.getValue();
							Timber.tag(TAG).d("BluetoothGattCallback: Read characteristic " + characteristic.getUuid() + ":\n" + bytesToHex(buffer));

							// characteristic.getDescriptors().get(0).getValue()
							readMessage = new String(buffer, StandardCharsets.UTF_8).trim();
							Timber.tag(TAG).d("readMessage: %s", readMessage);

							if (!readMessage.equals("")) {
								Message readMsg = mHandler.obtainMessage(
										MessageConstants.MESSAGE_READ, buffer.length, -1,
										buffer);
								readMsg.sendToTarget();
							} else {
								// Send a read failure message back to the activity.
								Message writeFailureMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
								Bundle bundle = new Bundle();
								bundle.putString(TOAST, "Failed to read from device");
								writeFailureMsg.setData(bundle);
								mHandler.sendMessage(writeFailureMsg);
							}

						case BluetoothGatt.GATT_READ_NOT_PERMITTED:
							if (readMessage == null) {
								// Send a read failure message back to the activity.
								Message writeFailureMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
								Bundle bundle = new Bundle();
								bundle.putString(TOAST, "Failed to read from device");
								writeFailureMsg.setData(bundle);
								mHandler.sendMessage(writeFailureMsg);
							}
							Timber.tag(TAG).e("BluetoothGattCallback: Read not permitted for %s", characteristic.getUuid());

						default:
							Timber.tag(TAG).e("BluetoothGattCallback: Characteristic read failed for " + characteristic.getUuid() + ", error: " + status);
					}
				}
			};

			mBTArrayAdapter.notifyDataSetChanged();
			bleScanner.startScan(null, scanSettings, scanCallback);
			isScanning = true;
		}
	}

	private void stopBleScan() {
		checkBluetoothPermission();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			bleScanner.stopScan(scanCallback);
			isScanning = false;
		}
	}

	private void printGattTable(List<BluetoothGattService> services) {
		checkBluetoothPermission();

		// Example logs
		// 1. BluetoothGattService: 00001801-0000-1000-8000-00805f9b34fb
		//     - BluetoothGattCharacteristic: 00002a05-0000-1000-8000-00805f9b34fb Properties: 32
		// 2. BluetoothGattService: 00001800-0000-1000-8000-00805f9b34fb
		// 	- BluetoothGattCharacteristic: 00002a00-0000-1000-8000-00805f9b34fb Properties: 2 (a readCharacteristic)
		// 	- BluetoothGattCharacteristic: 00002a01-0000-1000-8000-00805f9b34fb Properties: 2 (a readCharacteristic)
		// 	- BluetoothGattCharacteristic: 00002aa6-0000-1000-8000-00805f9b34fb Properties: 2 (a readCharacteristic)

		if (services.isEmpty()) {
			Timber.tag(TAG).d("printGattTable: No service and characteristic available, call discoverServices() first?");
		} else {
			for (BluetoothGattService service : services) {
				for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
					// BluetoothGattCharacteristic contains a getProperties() method that is a bitmask of its properties
					// as represented by the BluetoothGattCharacteristic.PROPERTY_* constants.
					// We can then perform a bitwise AND between getProperties() and a given PROPERTY_* constant
					// to figure out if a certain property is present on the characteristic or not.
					Timber.tag(TAG).d("BluetoothGattService: "
							+ "\nUUID: " + service.getUuid()
							+ "\nContents: " + service.describeContents()
							+ "\nType: " + service.getType());

					List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
					Timber.tag(TAG).d("BluetoothGattCharacteristic: "
							+ "\nUUID: " + characteristic.getUuid()
							+ "\nProperties: " + characteristic.getProperties()
							+ "\nPermissions: " + characteristic.getPermissions()
							+ "\nDescriptors: " + descriptors
							+ "\nValue: " + Arrays.toString(characteristic.getValue()));

					// PROPERTY_READ, PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE
					// The formatType parameter determines how the characteristic value is to be interpreted.
					// FORMAT_UINT16 specifies that the first two bytes of the characteristic value at the given offset are interpreted to generate the return value.
					if (characteristic.getProperties() == BluetoothGattCharacteristic.PROPERTY_READ) {
						// && characteristic.getPermissions() == BluetoothGattCharacteristic.PERMISSION_READ) {
						// A callback should get delivered to your BluetoothGattCallback’s onCharacteristicRead(),
						// regardless if the read operation had succeeded or not.
						gattClient.readCharacteristic(characteristic);
						Timber.tag(TAG).d("readCharacteristic: %s", characteristic.getUuid());
					}
				}
			}
		}
	}

	private void listPairedDevices() {
		checkBluetoothPermission();
		mBTArrayAdapter.clear();
		Set<BluetoothDevice> mPairedDevices = bluetoothAdapter.getBondedDevices();
		if (bluetoothAdapter.isEnabled()) {
			// put it's one to the adapter
			for (BluetoothDevice device : mPairedDevices) {
				mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
			}

			Toast.makeText(getApplicationContext(), "Show Paired Devices", Toast.LENGTH_SHORT).show();
		} else
			Toast.makeText(getApplicationContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
	}

	private void checkBluetoothPermission() {
		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				// If enabling Bluetooth succeeds, your activity receives the RESULT_OK result code in the onActivityResult() callback.
				// If Bluetooth was not enabled due to an error (or the user responded "Deny") then the result code is RESULT_CANCELED.
				ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
			}
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		// Don't forget to unregister the ACTION_FOUND receiver.
		unregisterReceiver(receiver);

		// Cancel any thread attempting to make a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread.interrupt();
		}
	}

	private void manageMyConnectedSocket(BluetoothSocket socket, String name) {
		Timber.tag(TAG).d("manageMyConnectedSocket ... name: " + name + ", fail: " + fail);
		checkBluetoothPermission();

		if (!fail) {
			mConnectedThread = new ConnectedThread(socket);
			mConnectedThread.start();

			mHandler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, MessageConstants.STATE_LISTEN, -1, name).sendToTarget();
		}
	}

	private void readBatteryLevel() {
		checkBluetoothPermission();

		UUID batteryServiceUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
		UUID batteryLevelCharUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

		BluetoothGattService bluetoothGattService = gattClient.getService(batteryServiceUuid);
		if (bluetoothGattService != null) {
			BluetoothGattCharacteristic batteryLevelCharacteristic = bluetoothGattService.getCharacteristic(batteryLevelCharUuid);

			Timber.tag(TAG).d("BluetoothGattCharacteristic: "
					+ "\nUUID: " + batteryLevelCharacteristic.getUuid()
					+ "\nProperties: " + batteryLevelCharacteristic.getProperties()
					+ "\nPermissions: " + batteryLevelCharacteristic.getPermissions()
					+ "\nValue: " + Arrays.toString(batteryLevelCharacteristic.getValue()));

			// PROPERTY_READ, PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE
			if (batteryLevelCharacteristic.getProperties() == BluetoothGattCharacteristic.PROPERTY_READ) {
				// A callback should get delivered to your BluetoothGattCallback’s onCharacteristicRead(),
				// regardless if the read operation had succeeded or not.
				gattClient.readCharacteristic(batteryLevelCharacteristic);
			}
		}
	}

	// Defines several constants used when transmitting messages between the
	// service and the UI.
	private interface MessageConstants {
		int MESSAGE_STATE_CHANGE = 1;
		int MESSAGE_READ = 2;
		int MESSAGE_WRITE = 3;
		int MESSAGE_DEVICE_INFO = 4;
		int MESSAGE_TOAST = 5;

		// Constants that indicate the current connection state
		int STATE_NONE = 0;         // we're doing nothing
		int STATE_LISTEN = 1;       // now listening for incoming connections
		int STATE_CONNECTING = 2;   // now initiating an outgoing connection
		int STATE_CONNECTED = 3;    // now connected to a remote device

		// ... (Add other message types here as needed.)
	}

	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private byte[] mmBuffer; // mmBuffer store for the stream

		public ConnectedThread(BluetoothSocket socket) {
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the input and output streams; using temp objects because
			// member streams are final.
			try {
				// After the constructor acquires the necessary streams,
				// the thread waits for data to come through the InputStream.
				// When read(byte[]) returns with data from the stream,
				// the data is sent to the main activity using a member Handler from the parent class.
				// The thread then waits for more bytes to be read from the InputStream.
				tmpIn = socket.getInputStream();
			} catch (IOException e) {
				Timber.tag(TAG).e(e, "Error occurred when creating input stream: %s", e.getMessage());
			}
			try {
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Timber.tag(TAG).e(e, "Error occurred when creating output stream: %s", e.getMessage());
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			Log.i(TAG, "BEGIN mConnectedThread");

			mmBuffer = new byte[1024];
			int numBytes; // bytes returned from read()

			// Keep listening to the InputStream until an exception occurs.
			while (true) {
				try {
					// Read from the InputStream.
					numBytes = mmInStream.read(mmBuffer);
					// Send the obtained bytes to the UI activity.
					Message readMsg = mHandler.obtainMessage(
							MessageConstants.MESSAGE_READ, numBytes, -1,
							mmBuffer);
					readMsg.sendToTarget();
				} catch (IOException e) {
					Timber.tag(TAG).e(e, "Input stream was disconnected: %s", e.getMessage());
					break;
				}
			}
		}

		// Call this from the main activity to send data to the remote device.
		public void write(byte[] bytes) {
			try {
				mmOutStream.write(bytes);

				// Share the sent message with the UI activity.
				Message writtenMsg = mHandler.obtainMessage(
						MessageConstants.MESSAGE_WRITE, -1, -1, mmBuffer);
				writtenMsg.sendToTarget();
			} catch (IOException e) {
				Timber.tag(TAG).e(e, "Error occurred when sending data: %s", e.getMessage());

				// Send a failure message back to the activity.
				Message writeErrorMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
				Bundle bundle = new Bundle();
				bundle.putString(TOAST, "Couldn't send data to the other device");
				writeErrorMsg.setData(bundle);
				mHandler.sendMessage(writeErrorMsg);
			}
		}

		// Call this method from the main activity to shut down the connection.
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Timber.tag(TAG).e(e, "Could not close the connect socket %s", e.getMessage());
			}
		}
	}

	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;

		public ConnectThread(BluetoothDevice device) {
			// Use a temporary object that is later assigned to mmSocket
			// because mmSocket is final.
			BluetoothSocket tmp = null;
			mmDevice = device;

			try {
				checkBluetoothPermission();

				// Get a BluetoothSocket to connect with the given BluetoothDevice.
				// MY_UUID is the app's UUID string, also used in the server code.
				Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
				tmp = (BluetoothSocket) m.invoke(device, MY_UUID);
				// tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
				// tmp = (BluetoothSocket) device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", new Class[] {int.class}).invoke(device,2);
			} catch (Exception e) {
				Timber.tag(TAG).e(e, "Socket's create() method failed for Insecure: %s", e.getMessage());
				try {
					tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
					// tmp = (BluetoothSocket) device.getClass().getMethod("createRfcommSocket", new Class[] {int.class}).invoke(device,2);
				} catch (Exception e2) {
					Timber.tag(TAG).e(e2, "Socket's create() method failed: %s", e2.getMessage());
				}
			}
			mmSocket = tmp;
		}

		public void run() {
			checkBluetoothPermission();

			// Cancel discovery because it otherwise slows down the connection.
			bluetoothAdapter.cancelDiscovery();

			try {
				fail = false;

				// See https://punchthrough.com/android-ble-guide/ to understand device types
				// The Bluetooth Classic scanning API uses a BroadcastReceiver, which is multicast in nature,
				// messy, and typically avoided in modern Android development.
				// The Android SDK requires Bluetooth Classic devices to be paired with Android before an RFCOMM connection can be established,
				// whereas the BLE use case doesn’t have this restriction imposed.
				if (BluetoothDevice.DEVICE_TYPE_LE == mmDevice.getType()) {
					// Connect to the remote device through the socket. This call blocks
					// until it succeeds or throws an exception.
					mmSocket.connect();
				} else {
					Timber.tag(TAG).d("Device not classic: " + mmDevice.getName() + " - " + mmDevice.getType());
				}
			} catch (IOException e) {
				fail = true;

				Timber.tag(TAG).e(e, "Socket connection failed: %s", e.getMessage());

				// Unable to connect; close the socket and return.
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Timber.tag(TAG).e(e2, "Could not close the client socket: %s", e2.getMessage());
				}

				mHandler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, MessageConstants.STATE_NONE, -1, mmDevice.getName()).sendToTarget();
				return;
			}

			// The connection attempt succeeded. Perform work associated with
			// the connection in a separate thread.
			manageMyConnectedSocket(mmSocket, mmDevice.getName());
		}

		// Closes the client socket and causes the thread to finish.
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Timber.tag(TAG).e(e, "Could not close the client socket: %s", e.getMessage());
			}
		}
	}
}
