package com.example.kotlinconversionsupportivehousing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.app.TimePickerDialog.OnTimeSetListener
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.ui.AppBarConfiguration
import androidx.viewpager2.widget.ViewPager2
import com.example.kotlinconversionsupportivehousing.databinding.ActivityMainBinding
import com.example.kotlinconversionsupportivehousing.fragments.WelcomeFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.UUID
import java.util.concurrent.Semaphore
import com.google.android.material.tabs.TabLayout

class PillDispenserActivity : AppCompatActivity(), AutoConnect {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    var REQUEST_BLUETOOTH_SCAN = 3
    val REQUEST_ENABLE_BT = 1
    val REQUEST_ENABLE_BLUETOOTH_ADMIN = 2

    //    ESP-01 UUIDs
    val CHARACTERISTIC_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
    val SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
    val DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    // ESP-02 UUIDs
//    val CHARACTERISTIC_UUID = "83755cbd-e485-4153-ac8b-ce260afd3697"
//    val SERVICE_UUID = "adee10c3-91dd-43aa-ab9b-052eb63d456c"
//    val DESCRIPTOR_UUID = "4d6ec567-93f0-4541-8152-81b35dc5cb8b"

//    BLANK
//    val CHARACTERISTIC_UUID = "681F827F-D00E-4307-B77A-F38014D6CC5F"
//    val SERVICE_UUID = "3BED005E-75B7-4DE6-B877-EAE81B0FC93F"
//    val DESCRIPTOR_UUID = "013B54B2-5520-406A-87F5-D644AD3E0565"

    val connectionSemaphore = Semaphore(1)
    private val permissionrequestcode = 123

    private interface MessageConstants {
        companion object {
            const val MESSAGE_READ = 0
            const val MESSAGE_WRITE = 1
            const val MESSAGE_TOAST = 2
        }
    }


    lateinit var initializeBluetooth: Button
    lateinit var scanForBluetooth: Button
    lateinit var startBtn: Button
    lateinit var stopBtn: Button
    lateinit var timeButton: Button
    lateinit var textView: TextView
    lateinit var lastDetection: TextView
    lateinit var displayNotification : TextView
    lateinit var context: Context
    lateinit var sendDataBtn: Button

    lateinit var buttonContainer: LinearLayout
    val list = listOf<String>(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.USE_EXACT_ALARM)

    var hour = 0;
    var minute:Int = 0

    val devices = ArrayList<BluetoothDevice>()
    val uuidMapping: Map<String, Array<Parcelable>> = HashMap()
    val adapter = BluetoothAdapter.getDefaultAdapter()


    val bluetoothScanner = adapter.bluetoothLeScanner

    var device:BluetoothDevice ?= null

    var deviceService:BluetoothGattService ?= null

    val queue: Queue<String> = LinkedList()

    val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i("Scan passed", "Could  complete scan for nearby BLE devices")
            devices.add(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.i("Scan failed", "Could not complete scan for nearby BLE devices")
            return
        }
    }



    @SuppressLint("MissingPermission")
    val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i("Device Status", newState.toString() + "")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("Device info", "Discovering bluetooth services of target device...")
                runOnUiThread {
                    Toast.makeText(
                        context,
                        "Connected to ESP32 successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                gatt.requestMtu(512)
//                    gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("Device info", "Disconnecting bluetooth device...")
                gatt.disconnect()
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("MTU Request", "MTU request success")
                gatt.discoverServices()
            } else {
                Log.i("MTU Request", "MTU request failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Read the updated characteristic value
            val message = characteristic.value
            val messageString = String(message, StandardCharsets.UTF_8)
            Log.i("Unparsed JSON string: ", messageString)
            var status = ""
            var lastDetected = 0
            val motionDetected: Boolean
            val proximityDetected: Boolean
            val lightDetected: Boolean
            val vibrationDetected: Boolean
            try {
                val jsonObject = JSONObject(messageString)
                status = jsonObject.getString("status")
                lastDetected = jsonObject.getInt("lastDetected")
                motionDetected = jsonObject.getBoolean("motion")
                proximityDetected = jsonObject.getBoolean("proximity")
                lightDetected = jsonObject.getBoolean("light")
                vibrationDetected = jsonObject.getBoolean("vibration")
                //float lightIntensity = (float) jsonObject.getDouble("lightIntensity");
                val finalStatus = status
                val finalLastDetected = lastDetected
                runOnUiThread {
                    textView!!.text =
                        "Status: $finalStatus\nMotion: $motionDetected\nProximity: $proximityDetected\nLight: $lightDetected\nVibration: $vibrationDetected"
                    lastDetection!!.text = "Last detected: " + finalLastDetected + "m ago"

                }
            } catch (e: JSONException) {
                Log.i("Error", "Could not parse JSON string")
            }
            Log.i("Notification", "Updated status: $status")
            Log.i("Notification", "Last detected: $lastDetected")
            // Do something with the updated characteristic value
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                deviceService = service
//                    sendData()
                val operation = queue.poll()
                if(operation.equals("sendData")){
                    sendData(gatt)
                }
                Log.i("Device info", "Successfully discovered services of target device")
                if (service != null) {
                    Log.i("Service status", "Service is not null.")
                    val discoveredCharacteristic =
                        service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))
                    if (discoveredCharacteristic != null) {
                        gatt.readCharacteristic(discoveredCharacteristic)
                        if (gatt.setCharacteristicNotification(discoveredCharacteristic, true)) {
                            Log.i("Set characteristic notification", "Success!")
                            Log.i(
                                "Characteristic property flags",
                                discoveredCharacteristic.properties.toString()
                            )
                            val desc = discoveredCharacteristic.getDescriptor(
                                UUID.fromString(DESCRIPTOR_UUID)
                            )
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                            //gatt.requestMtu(512);
                        } else {
                            Log.i("Set characteristic notification", "Failure!")
                        }
                    } else {
                        Log.i("Characteristic info", "Characteristic not found!")
                    }
                } else {
                    Log.i("Service info", "Service not found!")
                }
            } else {
                Log.i("Service Discovery", "Service discovery failed")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, discoveredCharacteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = discoveredCharacteristic.value
                val value = String(data, StandardCharsets.UTF_8)
                Log.i("Read data", "Received data: $value")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
            }
        }
    }


//        @SuppressLint("MissingPermission")
//        override fun onRequestPermissionsResult(
//            requestCode: Int,
//            permissions: Array<String?>,
//            grantResults: IntArray
//        ) {
//            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//            if (requestCode == REQUEST_ENABLE_BT) {
//                if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                    Log.i("Permission", "Bluetooth permission granted")
//                    adapter.enable()
//                } else {
//                    Log.i("Permission", "Bluetooth permission denied")
//                }
//            } else if (requestCode == REQUEST_BLUETOOTH_SCAN) {
//                if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                    Log.i("Permission", "Bluetooth scan permission granted")
//                } else {
//                    Log.i("Permission", "Bluetooth scan permission denied")
//                }
//            }
//        }

    @SuppressLint("MissingPermission")
    val someActivityResultLauncher = registerForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        super@PillDispenserActivity.onActivityResult(
            REQUEST_ENABLE_BT,
            result.resultCode,
            result.data
        )
        if (result.resultCode == RESULT_OK) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf<String>(Manifest.permission.BLUETOOTH_SCAN),
                    REQUEST_ENABLE_BT
                )
            } else {
                val data = result.data
                adapter.enable()
            }
        }
    }

    fun initializeAdapters() {
        if (adapter == null) {
            Toast.makeText(this, "Device does not support Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is enabled", Toast.LENGTH_SHORT).show()
        } else {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            someActivityResultLauncher.launch(enableBtIntent)
        }
        scanForBluetooth!!.visibility = View.VISIBLE
    }

    open fun scanForBluetooth() {
        if (ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                showExplanation("Permission Needed","Rationale",Manifest.permission.BLUETOOTH_SCAN,REQUEST_BLUETOOTH_SCAN)
            } else {
                requestPermissions( arrayOf<String>(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_BLUETOOTH_SCAN )
            }
        } else {
            bluetoothScanner.startScan(scanCallback)
            startBtn!!.visibility = View.VISIBLE
        }
    }

    private fun showExplanation(s: String, s1: String, bluetoothScan: String, requestBluetoothScan: Int) {
        val builder = AlertDialog.Builder(this)
        with(builder)
        {
            setTitle(s)
            setMessage(s1)
            setPositiveButton("OK"
            ) { dialog, which -> requestPermissions( arrayOf<String>(bluetoothScan), requestBluetoothScan )
            }.show()
        }
    }

    @SuppressLint("MissingPermission")
    open fun startProcess() {

        var device: BluetoothDevice? = null
        var targetDeviceAddress = ""
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
        val scannedDevicesList = java.util.ArrayList<BluetoothDevice>()
        var notFound = true
        while (connectedDevices.isEmpty() && notFound) {
            if (!devices.isEmpty()) {
                val deviceSize = devices.size
                for (i in 0 until deviceSize) {
                    if (devices[i] != null && devices[i].name != null) {
                        targetDeviceAddress = devices[i].name
                        device = devices[i]
                        Log.i("Device Found", "Found target device: " + device.name)
                        Log.i("Device Address", "Device address is: $targetDeviceAddress")
                        scannedDevicesList.add(devices[i])
                        bluetoothScanner.stopScan(scanCallback)
                        notFound = false
//                            break
                        /*if (devices.get(i).getName().equals("ESP32")) {
                        targetDeviceAddress = devices.get(i).getName();
                        device = devices.get(i);
                        Log.i("Device Found", "Found target device: " + device.getName());
                        Log.i("Device Address", "Device address is: " + targetDeviceAddress);
                        scannedDevicesList.add(devices.get(i));
                        bluetoothScanner.stopScan(scanCallback);
                        notFound = false;
                        break;
                    }*/
                    }
                }
                if (device == null) {
                    Log.i("Devices", "Target device was not found")
                    return
                }
            } else {
                Log.i("Devices", "No devices were found")
                return
            }
            createButtons(scannedDevicesList)
            //            BluetoothGatt gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
//            connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT);
        }
        initializeBluetooth!!.visibility = View.INVISIBLE
        scanForBluetooth!!.visibility = View.INVISIBLE
        startBtn!!.visibility = View.INVISIBLE
    }

    @SuppressLint("MissingPermission")
    fun createButtons(scannedDevicesList: java.util.ArrayList<BluetoothDevice>) {

        val removeDups = java.util.ArrayList<BluetoothDevice>()
        val duplicates = hashSetOf<String>()

        for (device in scannedDevicesList) {
            if (!duplicates.contains(device.name)) {
                removeDups.add(device)
                duplicates.add(device.name)
            }
        }

        for (device in removeDups) {
            val button = Button(this)
            button.text = device.name
            button.setOnClickListener { startConnection(device) }
            buttonContainer!!.addView(button)
        }
    }

    @SuppressLint("MissingPermission")
    fun startConnection(device: BluetoothDevice) {
        if(this.device != null){
            this.device = device
        }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        var connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
        val gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)

        startDeviceDiscovery("sendData")

    }

    @SuppressLint("MissingPermission")
    override fun autoConnect(){
        var newDevice =  this.device
        //                val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        //                val gatt = device?.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        Log.d("AlarmReceiver", "Repeating alarm triggered from autoConnect!")
    }

    @SuppressLint("MissingPermission")
    fun startDeviceDiscovery(operation: String){
        val gatt = device?.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt?.discoverServices()
        queue.add(operation)
    }

    @SuppressLint("MissingPermission")
    fun sendData(gatt: BluetoothGatt){

        val s = "message"
        val charsetName = "UTF-16"
        val byteArray = s.toByteArray(StandardCharsets.UTF_8)

        deviceService = gatt?.getService(UUID.fromString(SERVICE_UUID))

        if (deviceService != null) {
            val example: BluetoothGattCharacteristic = deviceService!!.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))
            //                val example: BluetoothGattCharacteristic = BluetoothGattCharacteristic(UUID.fromString(CHARACTERISTIC_UUID), 8, 16)

            if (example != null) {
                Log.i("Permission Value", example.permissions.toString() + "")
                example.value = byteArray
                gatt?.writeCharacteristic(example)

                //                    Below are write characteristics for API 33
                //                    gatt?.writeCharacteristic(example, byteArray, 2)
                //                    device?.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                //                        ?.writeCharacteristic(example, byteArray, 2)
                //                    gatt.writeCharacteristic(example)

                Log.i("Send Data", "the data was sent!")
            }
        }

    }

    open fun switchLayouts(layout: String) {
        if (layout == "setTime") {
//            setContentView(R.layout.set_date);
            timeButton!!.visibility = View.VISIBLE
            initializeBluetooth!!.visibility = View.INVISIBLE
            scanForBluetooth!!.visibility = View.INVISIBLE
            startBtn!!.visibility = View.INVISIBLE
            buttonContainer!!.visibility = View.INVISIBLE
            textView!!.visibility = View.INVISIBLE
            lastDetection!!.visibility = View.INVISIBLE
            timeButton.setOnClickListener { setTime(timeButton) }
        } else {
            setContentView(R.layout.activity_main)
        }
    }

    fun setTime(timeButton: Button) {
        val onTimeSetListener = OnTimeSetListener { timePicker, selectedHour, selectedMinute ->
            hour = selectedHour
            minute = selectedMinute
            timeButton.text =
                String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        }
        val style = android.R.style.Theme_Holo_Light_Dialog_NoActionBar
        val timePickerDialog =
            TimePickerDialog(this, style, onTimeSetListener, hour, minute, true)
        timePickerDialog.setTitle("Select Time")
        timePickerDialog.show()
    }

    lateinit var tabLayout: TabLayout
    lateinit var viewPager2: ViewPager2
    lateinit var myViewPagerAdapter: MyViewPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pill_dispenser)

        context = this

        initializeBluetooth = findViewById<Button>(R.id.initializeBluetoothPillDispenser)
        scanForBluetooth = findViewById<Button>(R.id.scanBluetoothPillDispenser)
        textView = findViewById(R.id.statusTextPillDispenser)

        lastDetection = findViewById<TextView>(R.id.lastDetectionPillDispenser)
        startBtn = findViewById(R.id.startBtnPillDispenser)
        //        pairedDevices = findViewById(R.id.pairedDevices);
        buttonContainer = findViewById<LinearLayout>(R.id.scannedDevicesPillDispenser)
        scanForBluetooth.visibility = View.INVISIBLE
        startBtn.visibility = View.INVISIBLE
//                    timeButton.visibility = View.INVISIBLE

        initializeBluetooth.setOnClickListener(View.OnClickListener { initializeAdapters() })
//            scanForBluetooth.setOnClickListener(View.OnClickListener { scanForBluetooth() })
//            scanForBluetooth.setOnClickListener(View.OnClickListener { scanForBluetoothWithPermissions() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scanForBluetooth.setOnClickListener(View.OnClickListener { scanForBluetoothWithPermissions() })
        }else {
            scanForBluetooth.setOnClickListener(View.OnClickListener { scanForBluetooth() })
        }

        startBtn.setOnClickListener(View.OnClickListener { startProcess() })

    }

    @SuppressLint("MissingPermission")
    fun scanForBluetoothWithPermissions(){
        if (isPermissionsGranted() != PackageManager.PERMISSION_GRANTED) {
            showAlert()
        } else {
            Toast.makeText(this, "Permissions already granted.", Toast.LENGTH_SHORT).show()
            Log.i("Permission", "Scan Permission granted")
            bluetoothScanner.startScan(scanCallback)
            startBtn!!.visibility = View.VISIBLE
        }
    }

    private fun isPermissionsGranted(): Int {
        var counter = 0;
        for (permission in list) {
            counter += ContextCompat.checkSelfPermission(this, permission)
        }
        return counter
    }

    private fun showAlert() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Need permission(s)")
        builder.setMessage("Bluetooth permissions are required to do the task.")
        builder.setPositiveButton("OK", { dialog, which -> requestPermissions() })
        builder.setNeutralButton("Cancel", null)
        val dialog = builder.create()
        dialog.show()
    }

    private fun requestPermissions() {
        val permission = deniedPermission()
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            // Show an explanation asynchronously
            Toast.makeText(this, "Should show an explanation.", Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, list.toTypedArray(), permissionrequestcode)
        }
    }

    private fun deniedPermission(): String {
        for (permission in list) {
            if (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_DENIED) return permission
        }
        return ""
    }

    fun processPermissionsResult(requestCode: Int, permissions: Array<String>,
                                 grantResults: IntArray): Boolean {
        var result = 0
        if (grantResults.isNotEmpty()) {
            for (item in grantResults) {
                result += item
            }
        }
        if (result == PackageManager.PERMISSION_GRANTED) return true
        return false
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            permissionrequestcode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions already granted.", Toast.LENGTH_SHORT).show()
                    Log.i("Permission", "Scan Permission granted")
                    bluetoothScanner.startScan(scanCallback)
                    startBtn!!.visibility = View.VISIBLE
                } else {
                    showAlert()
                    // Permission denied, handle accordingly (e.g., show a message or disable functionality)
                }
            }
        }
    }

}


