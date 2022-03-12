package com.example.myapplication;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

	// Constants that indicate the current connection state
	public static final int STATE_NONE = 0;         // we're doing nothing
	public static final int STATE_LISTEN = 1;       // now listening for incoming connections
	public static final int STATE_CONNECTING = 2;   // now initiating an outgoing connection
	public static final int STATE_CONNECTED = 3;    // now connected to a remote device
	private static final int REQUEST_ENABLE_BT = 3; // must be greater than or equal to 0

	// A UUID is a standardized 128-bit format for a string ID used to uniquely identify information.
	// A UUID is used to identify information that needs to be unique within a system
	// or a network because the probability of a UUID being repeated is effectively zero.
	// It is generated independently, without the use of a centralized authority.
	// In this case, it's used to uniquely identify your app's Bluetooth service.
	// To get a UUID to use with your app, you can use one of the many random UUID generators on the web,
	// then initialize a UUID with fromString(String).
	private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	// Name for the SDP record when creating server socket
	private static final String NAME_SECURE = "BlueToothApplicationSecure";
	private static final String NAME_INSECURE = "BlueToothApplicationInsecure";

	// Unique UUID for this application
	private static final UUID MY_UUID_SECURE = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
	private static final UUID MY_UUID_INSECURE = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

	// Message types sent from the BluetoothChatService Handler
	private static final int MESSAGE_STATE_CHANGE = 1;
	private static final int MESSAGE_READ = 2;
	private static final int MESSAGE_WRITE = 3;
	private static final int MESSAGE_DEVICE_NAME = 4;
	private static final int MESSAGE_TOAST = 5;

	public static String EXTRA_DEVICE_ADDRESS = "device_address";
	private final String[] permissions = {
			"android.permission.ACCESS_FINE_LOCATION",
			"android.permission.BLUETOOTH_CONNECT",
			"android.permission.BLUETOOTH_SCAN"
	};

	// Key names received from the BluetoothChatService Handler
	String DEVICE_NAME = "device_name";
	String TOAST = "toast";
	String TAG = "BlueToothActivity";

	BluetoothAdapter bluetoothAdapter;

	TextView mBluetoothStatus;
	Intent intent;
	private EditText mCurrentWeight;
	/**
	 * Name of the connected device
	 */
	private String mConnectedDeviceName = null;
	/**
	 * The Handler that gets information back from the Bluetooth Service
	 */
	private final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			Log.i(TAG, msg.toString());

			switch (msg.what) {

				case MESSAGE_STATE_CHANGE:
					switch (msg.arg1) {
						case STATE_CONNECTED:
							String info = "Connected to " + mConnectedDeviceName;
							Log.i(TAG, "STATE_CONNECTED " + info);
							mBluetoothStatus.setText(info);
							Toast.makeText(getApplicationContext(), info, Toast.LENGTH_SHORT).show();
							break;
						case STATE_CONNECTING:
							mBluetoothStatus.setText("Connecting...");
							break;
						case STATE_LISTEN:
						case STATE_NONE:
							mBluetoothStatus.setText("Not connected");
							break;
					}
					break;

				case MESSAGE_READ:
					mBluetoothStatus.setText("Receiving input");

					byte[] readBuf = (byte[]) msg.obj;
					// byte[] weight = parseMessage(readBuf);
					// readMessage = new String(weight, StandardCharsets.UTF_8).trim();

					// construct a string from the valid bytes in the buffer
					String readMessage = new String(readBuf, 0, msg.arg1);
					mCurrentWeight.setText(readMessage);
					break;

				case MESSAGE_WRITE:
					mBluetoothStatus.setText("Writing output");

					byte[] writeBuf = (byte[]) msg.obj;

					// construct a string from the buffer
					String writeMessage = new String(writeBuf);
					break;

				case MESSAGE_DEVICE_NAME:
					// save the connected device's name
					mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
					String info = "Connected to " + mConnectedDeviceName;
					Log.i(TAG, "MESSAGE_DEVICE_NAME " + info);
					mBluetoothStatus.setText(info);
					Toast.makeText(getApplicationContext(), info, Toast.LENGTH_SHORT).show();
					break;

				case MESSAGE_TOAST:
					mBluetoothStatus.setText("Toast state");
					Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST), Toast.LENGTH_SHORT).show();
					break;
			}
		}
	};
	private int mState;
	private int mNewState;
	private AcceptThread mSecureAcceptThread;
	private AcceptThread mInsecureAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
		@Override
		public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
			checkBluetoothPermission();

			// Cancel discovery because it's costly and we're about to connect
			bluetoothAdapter.cancelDiscovery();

			// Get the device MAC address, which is the last 17 chars in the View
			String info = ((TextView) view).getText().toString();
			final String address = info.substring(info.length() - 17);
			final String name = info.substring(info.length() - 20, info.length() - 17);

			// Create the result Intent and include the MAC address
			intent = new Intent();
			intent.putExtra(EXTRA_DEVICE_ADDRESS, address);

			// Set result
			setResult(Activity.RESULT_OK, intent);

			Log.i(TAG, "Selected: " + address);

			connectDevice(intent, false);

			// Always stop your BLE scan before connecting to a BLE device.
			// Doing so saves power and more importantly, helps increase the reliability of the connection process.
			// stopBleScan();

			// Setting autoConnect to true doesn’t cause Android to automatically try to reconnect to the BluetoothDevice!
			// Instead, it causes the connect operation to not timeout,
			// which can be helpful if you’re trying to connect to a BluetoothDevice you had cached from earlier.
			// However, setting the flag to true may result in a slower than usual connection process
			// if the device is already discoverable in the immediate vicinity.
			// Preferably set the flag to false, and instead rely on the onConnectionStateChange callback
			// to inform us on whether the connection process succeeded.
			// In the event of a connection failure, we can simply try to connect again with autoConnect set to false.
			// device.connectGatt(mContext, false, gattCallback);
		}
	};

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
				String deviceName = device.getName();
				String deviceHardwareAddress = device.getAddress(); // MAC address

				String deviceInfo = "Tap to connect to " + deviceName + "\n" + deviceHardwareAddress;

				// add the name to the list
				if (mBTArrayAdapter.getPosition(deviceInfo) < 0) {
					mBTArrayAdapter.add(deviceInfo);
					mBTArrayAdapter.notifyDataSetChanged();
				}
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		String TAG = "BlueToothActivity";

		mBluetoothStatus = findViewById(R.id.bluetoothStatus);
		mCurrentWeight = findViewById(R.id.kgs);

		mState = STATE_NONE;
		mNewState = mState;

		int requestCode = 200;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			requestPermissions(permissions, requestCode);
		}

		// Set result CANCELED in case the user backs out
		setResult(Activity.RESULT_CANCELED);

		// Initialize the button to perform device discovery
		Button btnFind = findViewById(R.id.discover);
		btnFind.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				checkBluetoothPermission();

				doDiscovery();
			}
		});

		// Initialize the button to set weight received from Bluetooth Scale
		Button mSetWeight = findViewById(R.id.setWeight);
		mSetWeight.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				String result = mCurrentWeight.getText().toString();
				if (!result.trim().isEmpty()) {
					Intent returnIntent = new Intent();
					returnIntent.putExtra("result", result);
					setResult(Activity.RESULT_OK, returnIntent);
					finish();
				} else {
					Toast.makeText(getApplicationContext(), "No value was supplied for weight!", Toast.LENGTH_LONG).show();
				}
			}
		});

		// Initialize array adapter for newly discovered devices
		mBTArrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);

		// Find and set up the ListView for newly discovered devices
		ListView mDevicesListView = findViewById(R.id.devicesListView);
		mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
		mDevicesListView.setOnItemClickListener(mDeviceClickListener);

		// Register for broadcasts when a device is discovered
		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		this.registerReceiver(receiver, filter);

		// Register for broadcasts when discovery has finished
		filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		this.registerReceiver(receiver, filter);

		// Get the local Bluetooth adapter
		// The BluetoothAdapter is required for any and all Bluetooth activity.
		// It represents the device's own Bluetooth adapter (the Bluetooth radio).
		bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		if (bluetoothAdapter == null) {
			// Device doesn't support Bluetooth
			Log.d(TAG, "Device doesn't support Bluetooth");
		} else {
			if (!bluetoothAdapter.isEnabled()) {
				checkBluetoothPermission();

				Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
			}

			// To make the local device discoverable to other devices,
			// call startActivityForResult(Intent, int) with the ACTION_REQUEST_DISCOVERABLE intent.
			// This issues a request to enable the system's discoverable mode
			// without having to navigate to the Settings app, which would stop your own app.
			// By default, the device becomes discoverable for two minutes.
			// You can define a different duration, up to one hour, by adding the EXTRA_DISCOVERABLE_DURATION extra.
			// Caution: If you set the EXTRA_DISCOVERABLE_DURATION extra's value to 0, the device is always discoverable.
			// This configuration is insecure and therefore highly discouraged.
			// Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			// discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			// startActivityForResult(discoverableIntent, 1);
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

	/**
	 * Start device discover with the BluetoothAdapter
	 */
	private void doDiscovery() {
		checkBluetoothPermission();

		Log.i(TAG, "doDiscovery()");

		// If we're already discovering, stop it
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
			} else {
				Toast.makeText(getApplicationContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
				mBluetoothStatus.setText("Stopped Scan");
			}
		}
	}

	/**
	 * Ensure that Bluetooth is enabled.
	 * To request that Bluetooth be enabled, call startActivityForResult(), passing in an ACTION_REQUEST_ENABLE intent action.
	 * This call issues a request to enable Bluetooth through the system settings (without stopping your app).
	 */
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
	}

	/**
	 * Start the chat service. Specifically start AcceptThread to begin a
	 * session in listening (server) mode. Called by the Activity onResume()
	 */
	public synchronized void start() {
		Log.i(TAG, "start");

		// Cancel any thread attempting to make a connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to listen on a BluetoothServerSocket
		if (mSecureAcceptThread == null) {
			mSecureAcceptThread = new AcceptThread(true);
			mSecureAcceptThread.start();
		}
		if (mInsecureAcceptThread == null) {
			mInsecureAcceptThread = new AcceptThread(false);
			mInsecureAcceptThread.start();
		}

		// Update UI title
		updateUserInterfaceTitle();
	}

	/**
	 * Designed to initiate the thread for transferring data.
	 *
	 * @param socket A BluetoothSocket
	 */
	private void manageMyConnectedSocket(BluetoothSocket socket, BluetoothDevice device, final String socketType) {
		checkBluetoothPermission();

		Log.i(TAG, "connected, Socket Type:" + socketType);

		// Cancel the thread that completed the connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Cancel the accept thread because we only want to connect to one device
		if (mSecureAcceptThread != null) {
			mSecureAcceptThread.cancel();
			mSecureAcceptThread = null;
		}
		if (mInsecureAcceptThread != null) {
			mInsecureAcceptThread.cancel();
			mInsecureAcceptThread = null;
		}

		// Start the thread to manage the connection and perform transmissions
		mConnectedThread = new ConnectedThread(socket, socketType);
		mConnectedThread.start();

		// Send the name of the connected device back to the UI Activity
		Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME);
		Bundle bundle = new Bundle();
		bundle.putString(DEVICE_NAME, device.getName());
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		// Update UI title
		updateUserInterfaceTitle();
	}

	/**
	 * Indicate that the connection attempt failed and notify the UI Activity.
	 */
	private void connectionFailed() {
		// Send a failure message back to the Activity
		Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(TOAST, "Unable to connect device");
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		// Update UI title
		updateUserInterfaceTitle();

		// Start the service over to restart listening mode
		start();
	}

	/**
	 * Indicate that the connection was lost and notify the UI Activity.
	 */
	private void connectionLost() {
		// Send a failure message back to the Activity
		Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(TOAST, "Device connection was lost");
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		// Update UI title
		updateUserInterfaceTitle();

		// Start the service over to restart listening mode
		start();
	}

	/**
	 * Establish connection with other device
	 *
	 * @param data   An {@link Intent} with {@link MainActivity#EXTRA_DEVICE_ADDRESS} extra.
	 * @param secure Socket Security type - Secure (true) , Insecure (false)
	 */
	private void connectDevice(Intent data, boolean secure) {
		// Get the device MAC address
		Bundle extras = data.getExtras();
		if (extras == null) {
			return;
		}
		String address = extras.getString(MainActivity.EXTRA_DEVICE_ADDRESS);
		// Get the BluetoothDevice object
		BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);

		// Attempt to connect to the device
		connect(device, secure);
	}

	/**
	 * Start the ConnectThread to initiate a connection to a remote device.
	 *
	 * @param device The BluetoothDevice to connect
	 * @param secure Socket Security type - Secure (true) , Insecure (false)
	 */
	public synchronized void connect(BluetoothDevice device, boolean secure) {
		Log.i(TAG, "connect to: " + device);

		// Cancel any thread attempting to make a connection
		if (mState == STATE_CONNECTING) {
			if (mConnectThread != null) {
				mConnectThread.cancel();
				mConnectThread = null;
			}
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to connect with the given device
		mConnectThread = new ConnectThread(device, secure);
		mConnectThread.start();

		// Update UI title
		updateUserInterfaceTitle();
	}

	/**
	 * Update UI title according to the current state of the chat connection
	 */
	private synchronized void updateUserInterfaceTitle() {
		Log.i(TAG, "updateUserInterfaceTitle() " + mNewState + " -> " + mState);
		mNewState = mState;

		// Give the new state to the Handler so the UI Activity can update
		mHandler.obtainMessage(MESSAGE_STATE_CHANGE, mNewState, -1).sendToTarget();
	}

	/**
	 * Write to the ConnectedThread in an unsynchronized manner
	 *
	 * @param out The bytes to write
	 * @see ConnectedThread#write(byte[])
	 */
	public void write(byte[] out) {
		// Create temporary object
		ConnectedThread r;
		// Synchronize a copy of the ConnectedThread
		synchronized (this) {
			if (mState != STATE_CONNECTED) return;
			r = mConnectedThread;
		}
		// Perform the write unsynchronized
		r.write(out);
	}

	/**
	 * When you want to connect two devices, one must act as a server by holding an open BluetoothServerSocket.
	 * The purpose of the server socket is to listen for incoming connection requests and provide a connected BluetoothSocket after a request is accepted.
	 * When the BluetoothSocket is acquired from the BluetoothServerSocket, the BluetoothServerSocket can—and should—be discarded,
	 * unless you want the device to accept more connections.
	 * <p>
	 * To set up a server socket and accept a connection, complete the following sequence of steps:
	 * 1. Get a BluetoothServerSocket by calling listenUsingRfcommWithServiceRecord(String, UUID).
	 * 2. Start listening for connection requests by calling accept().
	 * 3. Unless you want to accept additional connections, call close().
	 * <p>
	 * Because the accept() call is a blocking call, do not execute it in the main activity UI thread.
	 * Executing it in another thread ensures that your app can still respond to other user interactions.
	 * It usually makes sense to do all work that involves a BluetoothServerSocket or BluetoothSocket in a new thread managed by your app.
	 * To abort a blocked call such as accept(), call close() on the BluetoothServerSocket or BluetoothSocket from another thread.
	 * Note that all methods on a BluetoothServerSocket or BluetoothSocket are thread-safe.
	 */
	private class AcceptThread extends Thread {
		private final BluetoothServerSocket mmServerSocket;
		private final String mSocketType;

		public AcceptThread(boolean secure) {
			Log.i(TAG, "AcceptThread started");
			mSocketType = secure ? "Secure" : "Insecure";

			// Use a temporary object that is later assigned to mmServerSocket
			// because mmServerSocket is final.
			BluetoothServerSocket tmp = null;
			try {
				checkBluetoothPermission();

				// The string "BlueToothApplication" is an identifiable name of your service,
				// which the system automatically writes to a new Service Discovery Protocol (SDP) database entry on the device.
				// The name is arbitrary and can simply be your app name.
				// The Universally Unique Identifier (UUID) is also included in the SDP entry
				// and forms the basis for the connection agreement with the client device.
				// That is, when the client attempts to connect with this device,
				// it carries a UUID that uniquely identifies the service with which it wants to connect.
				// These UUIDs must match in order for the connection to be accepted.
				// MY_UUID is the app's UUID string, also used by the client code.
				if (secure) {
					tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, MY_UUID_SECURE);
					Log.i(TAG, "listenUsingRfcommWithServiceRecord called: " + (tmp == null));
				} else {
					tmp = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME_INSECURE, MY_UUID_INSECURE);
					Log.i(TAG, "listenUsingInsecureRfcommWithServiceRecord called: " + (tmp == null));
				}
			} catch (IOException e) {
				Log.e(TAG, "ServerSocket Type: " + mSocketType + " listen() method failed", e);
			}
			mmServerSocket = tmp;
			mState = STATE_LISTEN;
			Log.d(TAG, "mmServerSocket: " + (mmServerSocket == null) + ", state: " + mState);
		}

		public void run() {
			Log.i(TAG, "Socket Type: " + mSocketType + " BEGIN mAcceptThread " + this);
			setName("AcceptThread" + mSocketType);

			BluetoothSocket socket;
			// Listen to the server socket if we're not connected
			while (mState != STATE_CONNECTED) {
				try {
					// This is a blocking call.
					// It returns when either a connection has been accepted or an exception has occurred.
					// A connection is accepted only when a remote device has sent a connection request
					// containing a UUID that matches the one registered with this listening server socket.
					// When successful, accept() returns a connected BluetoothSocket.
					socket = mmServerSocket.accept();
					Log.i(TAG, "ServerSocket's accept() method called");
				} catch (IOException e) {
					Log.e(TAG, "ServerSocket's accept() method failed", e);
					break;
				}

				if (socket != null) {
					synchronized (this) {
						switch (mState) {
							case STATE_LISTEN:
							case STATE_CONNECTING:
								// Situation normal. Start the connected thread.
								// A connection was accepted. Perform work associated with
								// the connection in a separate thread.
								manageMyConnectedSocket(socket, socket.getRemoteDevice(), mSocketType);
								break;
							case STATE_NONE:
							case STATE_CONNECTED:
								// Either not ready or already connected. Terminate new socket.
								cancel();
								break;
						}
					}
				}
			}
		}

		// Closes the connect socket and causes the thread to finish.
		public void cancel() {
			try {
				// This method call releases the server socket and all its resources,
				// but doesn't close the connected BluetoothSocket that's been returned by accept().
				// Unlike TCP/IP, RFCOMM allows only one connected client per channel at a time,
				// so in most cases it makes sense to call close() on the BluetoothServerSocket immediately after accepting a connected socket.
				mmServerSocket.close();
				Log.i(TAG, "ServerSocket's close() method called");
			} catch (IOException e) {
				Log.e(TAG, "Could not close the connect ServerSocket", e);
			}
		}
	}

	/**
	 * In order to initiate a connection with a remote device that is accepting connections on an open server socket,
	 * you must first obtain a BluetoothDevice object that represents the remote device.
	 * You must then use the BluetoothDevice to acquire a BluetoothSocket and initiate the connection.
	 * <p>
	 * The basic procedure is as follows:
	 * 1. Using the BluetoothDevice, get a BluetoothSocket by calling createRfcommSocketToServiceRecord(UUID).
	 * 2. Initiate the connection by calling connect(). Note that this method is a blocking call.
	 * <p>
	 * Because connect() is a blocking call, you should always perform this connection procedure in a thread that is separate from the main activity (UI) thread.
	 */
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;
		private final String mSocketType;

		public ConnectThread(BluetoothDevice device, boolean secure) {
			mSocketType = secure ? "Secure" : "Insecure";

			// Use a temporary object that is later assigned to mmSocket
			// because mmSocket is final.
			BluetoothSocket tmp = null;
			mmDevice = device;

			try {
				checkBluetoothPermission();

				// Get a BluetoothSocket to connect with the given BluetoothDevice.
				// MY_UUID is the app's UUID string, also used in the server code.
				// This method initializes a BluetoothSocket object that allows the client to connect to a BluetoothDevice.
				// The UUID passed here must match the UUID used by the server device
				// when it called listenUsingRfcommWithServiceRecord(String, UUID) to open its BluetoothServerSocket.
				// To use a matching UUID, hard-code the UUID string into your app, and then reference it from both the server and client code.
				if (secure) {
					tmp = device.createRfcommSocketToServiceRecord(MY_UUID_SECURE);
					Log.i(TAG, "createRfcommSocketToServiceRecord called: " + (tmp == null));
				} else {
					tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID_INSECURE);
					Log.i(TAG, "createInsecureRfcommSocketToServiceRecord called: " + (tmp == null));
				}
			} catch (IOException e) {
				Log.e(TAG, "Socket's create() method failed", e);
			}
			mmSocket = tmp;
			Log.d(TAG, "mmSocket: " + (mmSocket == null));
			mState = STATE_CONNECTING;
		}

		public void run() {
			checkBluetoothPermission();

			Log.i(TAG, "BEGIN mConnectThread SocketType:" + mSocketType);
			setName("ConnectThread" + mSocketType);

			// Cancel discovery because it otherwise slows down the connection.
			// Called before the connection attempt occurs.
			// You should always call cancelDiscovery() before connect(),
			// especially because cancelDiscovery() succeeds regardless of whether device discovery is currently in progress.
			// If your app needs to determine whether device discovery is in progress, you can check using isDiscovering().
			bluetoothAdapter.cancelDiscovery();

			try {
				// Connect to the remote device through the socket. This call blocks
				// until it succeeds or throws an exception.
				// After a client calls this method, the system performs an SDP lookup to find the remote device with the matching UUID.
				// If the lookup is successful and the remote device accepts the connection,
				// it shares the RFCOMM channel to use during the connection, and the connect() method returns.
				// If the connection fails, or if the connect() method times out (after about 12 seconds),
				// then the method throws an IOException.
				mmSocket.connect();
				Log.i(TAG, "Socket's connect() method called");
			} catch (IOException connectException) {
				Log.e(TAG, "Socket's connect() method failed " + mSocketType, connectException);

				if (mSocketType.equals("Secure")) {
					// Unable to connect; close the socket and return.
					cancel();

					connectionFailed();
				} else {
					// Retry with secure socket
					connectDevice(intent, true);
				}
			}

			// Reset the ConnectThread because we're done
			synchronized (this) {
				mConnectThread = null;
			}

			// Start the connected thread
			manageMyConnectedSocket(mmSocket, mmDevice, mSocketType);
		}

		// Closes the client socket and causes the thread to finish.
		public void cancel() {
			try {
				// When you're done with your BluetoothSocket, always call close().
				// Doing so immediately closes the connected socket and releases all related internal resources.
				mmSocket.close();
				Log.i(TAG, "Socket's close() method called: " + mSocketType);
			} catch (IOException e) {
				Log.e(TAG, "Could not close the client socket: " + mSocketType, e);
			}
		}
	}

	/**
	 * Transfer Bluetooth data
	 * <p>
	 * After you have successfully connected to a Bluetooth device, each one has a connected BluetoothSocket.
	 * You can now share information between devices.
	 * Using the BluetoothSocket, the general procedure to transfer data is as follows:
	 * 1. Get the InputStream and OutputStream that handle transmissions through the socket using getInputStream() and getOutputStream(), respectively.
	 * 2. Read and write data to the streams using read(byte[]) and write(byte[]).
	 * <p>
	 * You should use a dedicated thread for reading from the stream and writing to it.
	 * This is important because both the read(byte[]) and write(byte[]) methods are blocking calls.
	 * The read(byte[]) method blocks until there is something to read from the stream.
	 * The write(byte[]) method doesn't usually block,
	 * but it can block for flow control if the remote device isn't calling read(byte[]) quickly enough
	 * and the intermediate buffers become full as a result.
	 * So, you should dedicate your main loop in the thread to reading from the InputStream.
	 * You can use a separate public method in the thread to initiate writes to the OutputStream.
	 */
	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private byte[] mmBuffer; // mmBuffer store for the stream

		public ConnectedThread(BluetoothSocket socket, String socketType) {
			Log.i(TAG, "create ConnectedThread: " + socketType);

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
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
			mState = STATE_CONNECTED;
		}

		public void run() {
			Log.i(TAG, "BEGIN mConnectedThread");

			mmBuffer = new byte[1024];
			int numBytes; // bytes returned from read()

			// Keep listening to the InputStream until an exception occurs.
			// Keep listening to the InputStream while connected
			while (mState == STATE_CONNECTED) {
				try {
					// Read from the InputStream.
					numBytes = mmInStream.read(mmBuffer);

					// Send the obtained bytes to the UI activity.
					mHandler.obtainMessage(MESSAGE_READ, numBytes, -1, mmBuffer).sendToTarget();
				} catch (IOException e) {
					Log.e(TAG, "stream disconnected", e);
					connectionLost();
					break;
				}
			}

		}


		/**
		 * Write to the connected OutStream.
		 * Call this from the main activity to send data to the remote device.
		 *
		 * @param buffer The bytes to write
		 */
		public void write(byte[] buffer) {
			try {
				// To send outgoing data, you call the thread's write() method from the main activity and pass in the bytes to be sent.
				// This method calls write(byte[]) to send the data to the remote device.
				// If an IOException is thrown when calling write(byte[]), the thread sends a toast to the main activity,
				// explaining to the user that the device couldn't send the given bytes to the other (connected) device.
				mmOutStream.write(buffer);

				// Share the sent message back to the UI Activity
				mHandler.obtainMessage(MESSAGE_WRITE, -1, -1, buffer).sendToTarget();
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);

				// Send a failure message back to the activity.
				Message writeErrorMsg = mHandler.obtainMessage(MESSAGE_TOAST);
				Bundle bundle = new Bundle();
				bundle.putString("toast", "Couldn't send data to the other device");
				writeErrorMsg.setData(bundle);
				mHandler.sendMessage(writeErrorMsg);
			}
		}

		// Call this method from the main activity to shut down the connection.
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "Could not close the connect socket", e);
			}
		}
	}

}
