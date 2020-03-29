package com.fengmi.factory_impl_common;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.fengmi.factory_test_interf.sdk_interf.RfNetManagerInterf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;


public class RfNetManagerImpl implements RfNetManagerInterf {
    /* ---------------------Wifi Signal Test Interface----------------*/
    //for static ssid
    protected static final String STATIC_24_SSID = "MITEST_24GAP";
    protected static final String STATIC_5_SSID = "MITEST_5GAP";
    private static final int CONNECT_COUNT = 100;
    private static final int COUNT_SLICE = 200;
    private static final String IP_ERROR = "0.0.0.0";
    private static final int BT_STATE_IDLE = 0;
    private static final int BT_STATE_DISCOVERY_STARTED = 1;
    private static final int BT_STATE_DISCOVERY_FINISHED = 2;
    /*
    private boolean writeBtRcAddr(String mac){
        boolean flag = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "writeBtRcAddr: No bluetooth adapter");
            return flag;
        }
        flag = adapter.writeDefaultBleRCAddr(mac);
        return flag;
    }
    */
    private static final String BTRCMACFILEPATH = "/tvinfo/RemoteControllerBtMac";
    List<BTDevice> mScanedBTDevices = null;
    private Process proc = null;
    private Context mContext;
    //for wifi
    private WifiManager mWifiManager;
    private List<ScanResult> mWifiList;
    private getWifiIpThread mGetWifiIpThread;
    private BTScanThread mBTScanThread = null;
    private BTScanThreadBLE mBTScanThreadBLE = null;
    private boolean mTerminate = false;
    private int mBTState;
    //define a receiver to collect BT broadcast
    BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            Log.i(TAG, "BT BroadCast: Received " + action);
            if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_STARTED)) {
                mBTState = BT_STATE_DISCOVERY_STARTED;
            } else if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)) {
                mBTState = BT_STATE_DISCOVERY_FINISHED;
            } else if (action.equals(BluetoothDevice.ACTION_FOUND)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                boolean duplicate = false;
                for (int i = 0; i < mScanedBTDevices.size(); i++) {
                    BluetoothDevice dev = mScanedBTDevices.get(i).mDevice;
                    if (dev.getAddress().equals(device.getAddress())) {
                        duplicate = true;
                        Log.i(TAG, "BT BroadCast: Found duplicate Name[" + dev.getName() + "], MAC[" + dev.getAddress() + "]");
                        BTDevice btdevice = new BTDevice();
                        btdevice.mDevice = device;
                        btdevice.rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 1);
                        mScanedBTDevices.set(i, btdevice);
                        break;
                    }
                }
                if (!duplicate) {
                    BTDevice btdevice = new BTDevice();
                    btdevice.mDevice = device;
                    btdevice.rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 1);
                    mScanedBTDevices.add(btdevice);
                    Log.i(TAG, "BT BroadCast: Found device Name[" + device.getName() + "],MAC["
                            + device.getAddress() + "],rssi[" + btdevice.rssi + "]");
                }
            }
        }
    };
    private String LastConnectedSSID = null;
    //for ethernet
    private Handler mHandler = new Handler();
    private String WifiIpAddr = IP_ERROR;
    /* ---------------------Ethernet Test Interface----------------*/
    /*===========================================local functions=====================*/
    /*===========================================tool functions=====================*/
    private boolean PingResult = false;
    private boolean PingTesting = true;
    /*===========================================tool functions=====================*/
    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, final int rssi,
                                     byte[] scanRecord) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {

                            Log.i(TAG, "BluetoothDevice.ACTION_FOUND device = " + device + ", [" + device.getName() + "], RSSI = " + rssi);
                            boolean duplicate = false;
                            for (int i = 0; i < mScanedBTDevices.size(); i++) {
                                BluetoothDevice dev = mScanedBTDevices.get(i).mDevice;
                                if (dev.getAddress().equals(device.getAddress())) {
                                    duplicate = true;
                                    Log.i(TAG, "BT BroadCast: Found duplicate Name[" + dev.getName() + "], MAC[" + dev.getAddress() + "]");
                                    break;
                                }
                            }
                            if (!duplicate) {
                                BTDevice btdevice = new BTDevice();
                                btdevice.mDevice = device;
                                btdevice.rssi = (short) rssi;
                                mScanedBTDevices.add(btdevice);
                                Log.i(TAG, "BT BroadCast: Found device Name[" + device.getName() + "],MAC["
                                        + device.getAddress() + "],rssi[" + btdevice.rssi + "]");
                            }
                        }
                    });
                }
            };

    RfNetManagerImpl(Context context) {
        mContext = context;
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        //factory start, set wifi as close
        switchWifi(false);
    }

    public boolean wifiSetStatus(boolean stat) {
        boolean ret = false;
        ret = switchWifi(stat);
        return ret;
    }

    public boolean wifiGetStatus() {
        boolean ret = false;
        ret = readWifiStatus();
        return ret;
    }

    public boolean wifiConnectAp(String ssid) {
        boolean ret = false;
        int cfgid = 0;
        LastConnectedSSID = ssid;
        clearNetworkList();
        if (ssid.contains("5G")) {
            Log.i(TAG, "prepare to connect to 5G AP");
            cfgid = netWorkConfig5GInit(ssid);
        } else if (ssid.contains("24G")) {
            Log.i(TAG, "prepare to connect to 2.4G AP");
            cfgid = netWorkConfig24GInit(ssid);
        }
        if (cfgid > -1) {
            Log.i(TAG, "connect to AP: " + cfgid);
            ret = networkConnect(cfgid);
        }
        return ret;
    }

    public boolean wifiThroughputStart() {
        boolean ret = false;
        ret = iperfStart();
        return ret;
    }

    public boolean wifiThroughputWithParameter(String para) {
        boolean ret = false;
        ret = iperfWithPara(para);
        return ret;
    }

    public boolean wifiThroughputStop() {
        boolean ret = false;
        ret = iperfStop();
        return ret;
    }

    public String wifiGetIpAddr() {
        String ret = null;
        ret = getWifiIp();
        return ret;
    }

    public int wifiGetRssi() {
        int ret = 0;
        ret = getRssi();
        return ret;
    }

    public byte[] wifiGetRssiBox() {
        byte[] ret = null;
        //ret = getRssiBox();
        return ret;
    }

    public boolean wifiDisconnect() {
        boolean ret = false;
        ret = networkDisconnect();
        return ret;
    }

    public boolean wifiStartScan() {
        boolean ret = false;
        ret = wifiScanBegin();
        return ret;
    }

    public List<ScanResult> wifiGetScanList() {
        List<ScanResult> ret = null;
        ret = findWifiListScanned();
        return ret;
    }

    public boolean btSetStatus(boolean stat) {
        boolean ret = false;
        ret = setBtState(stat);
        return ret;
    }

    public boolean btSetStatusBLE(boolean stat) {
        boolean ret = false;
        ret = setBtStateBLE(stat);
        return ret;
    }

    public boolean btGetStatus() {
        boolean ret = false;
        ret = getBtState();
        return ret;
    }

    public boolean btStartScan() {
        boolean ret = false;
        ret = btScanBegin(true);
        return ret;
    }

    public int btGetRssi(String mac) {
        int ret = 0;
        ret = findBTDeviceScanned(mac);
        return ret;
    }

    public List<RfNetManagerInterf.BTDevice> btGetList() {
        List<BTDevice> ret = null;
        ret = findBTListScanned();
        return ret;
    }

    public boolean ethernetSetStatus(boolean stat) {
        boolean ret = true;
        return ret;
    }

    public boolean ethernetGetStatus() {
        boolean ret = false;
        ret = getEthState();
        return ret;
    }

    public boolean ethernetPingAp(String ipaddr) {
        boolean ret = false;
        int i = 0;
        Log.i(TAG, "ping ethernet: " + ipaddr);
        if (mWifiManager.isWifiEnabled()) {
            mWifiManager.setWifiEnabled(false);
            Log.i(TAG, "disableWifi");
        }
        ping(ipaddr);
        while (PingTesting) {
            try {
                Log.i(TAG, "Wifi Waiting Testing");
                Thread.sleep(200);
                if (i++ > 20) {
                    break;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }
        ret = PingResult;
        pingReset();
        return ret;
    }

    public boolean wifiPingAp(String ipaddr) {
        boolean ret = false;
        int i = 0;
        ping(ipaddr);
        while (PingTesting) {
            try {
                Log.i(TAG, "Wifi Waiting Testing");
                Thread.sleep(200);
                if (i++ > 50) {
                    break;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }
        ret = PingResult;
        pingReset();
        return ret;
    }

    public boolean setBtRcMac(String mac) {
        boolean ret = false;
        ret = writeBtRcAddr(mac);
        return ret;
    }

    //empty function for compatible box
    public byte[] nonSigHciCmdRun(String param) {
        byte[] ret = null;
        return ret;
    }

    public boolean nonSigCloseNormalBt() {
        boolean ret = false;
        return ret;
    }

    public boolean nonSigOpenNormalBt() {
        boolean ret = false;
        return ret;
    }

    public int nonSigIsBleSupport() {
        int ret = 0;
        return ret;
    }

    public int nonSigInitBt() {
        int ret = 0;
        return ret;
    }

    public int nonSigUninitBt() {
        int ret = 0;
        return ret;
    }

    public int nonSigBleGetChipId() {
        int ret = 0;
        return ret;
    }

    public boolean enterWifi() {
        boolean ret = false;
        return ret;
    }

    public boolean exitWifi() {
        boolean ret = false;
        return ret;
    }

    public boolean startWifiRx(String param) {
        boolean ret = false;
        return ret;
    }

    public String stopWifiRx() {
        String ret = null;
        return ret;
    }

    public boolean startWifiTx(String param) {
        boolean ret = false;
        return ret;
    }

    public boolean stopWifiTx() {
        boolean ret = false;
        return ret;
    }

    /*===========================================local functions=====================*/
    private boolean switchWifi(boolean stat) {
        boolean ret = false;
        if (stat) {
            mWifiManager.setWifiEnabled(true);
            Log.i(TAG, "enableWifi");
            ret = true;
        } else {
            //firstly, remove last network;
            //mWifiManager.removeNetwork(LastConnectedSSID);
            clearNetworkList();
            mWifiManager.setWifiEnabled(false);
            Log.i(TAG, "disableWifi");
            ret = true;
        }
        return ret;
    }

    private boolean readWifiStatus() {
        boolean ret = false;
        if (mWifiManager.isWifiEnabled()) {
            ret = true;
        }
        return ret;
    }

    private void clearNetworkList() {
        List<WifiConfiguration> nts = mWifiManager.getConfiguredNetworks();
        if (nts == null) {
            Log.e(TAG, "failed to get configured networks");
            return;
        }

        for (WifiConfiguration wc : nts) {
            if (wc != null && wc.SSID != null) {
                mWifiManager.disableNetwork(wc.networkId);
                //mWifiManager.forget(wc.networkId, null);
                Log.d(TAG, "forgot network config: " + wc.toString());
            }
        }
    }

    //2. network connect init
    //if send null, that is to say, default ssid is used.
    private int netWorkConfig24GInit(String ssid) {
        int mWificfg24GId = 0;
        WifiConfiguration mWificfg24G;
        //init 2.4G network configuration
        mWificfg24G = new WifiConfiguration();
        if (ssid == null) {
            mWificfg24G.SSID = String.format("\"%s\"", STATIC_24_SSID);
        } else {
            mWificfg24G.SSID = String.format("\"%s\"", ssid);
        }
        Log.i(TAG, "=============== SSID: " + mWificfg24G.SSID);
        mWificfg24G.allowedKeyManagement.set(KeyMgmt.NONE);
        //if use static ip, system can use it.
        //setStaticIp(mWificfg24G,mWificfg24G_ip, mWificfg24G_gw);
        mWificfg24G.networkId = -1;
        mWificfg24GId = mWifiManager.addNetwork(mWificfg24G);
        Log.i(TAG, "===============mWificfg24GId: " + mWificfg24GId);

        return mWificfg24GId;
    }

    private int netWorkConfig5GInit(String ssid) {
        int mWificfg5GId = 0;
        WifiConfiguration mWificfg5G;
        //init 5G network configuration
        mWificfg5G = new WifiConfiguration();
        if (ssid == null) {
            mWificfg5G.SSID = String.format("\"%s\"", STATIC_5_SSID);
        } else {
            mWificfg5G.SSID = String.format("\"%s\"", ssid);
        }
        mWificfg5G.allowedKeyManagement.set(KeyMgmt.NONE);
        //if use static ip, system can use it.
        //setStaticIp(mWificfg5G,mWificfg5G_ip, mWificfg5G_gw);
        mWificfg5G.networkId = -1;
        mWificfg5GId = mWifiManager.addNetwork(mWificfg5G);
        Log.i(TAG, "HCI:mWificfg5GId" + mWificfg5GId);

        return mWificfg5GId;
    }

    //3. network connect
    private boolean networkConnect(int cfgId) {
        boolean mWifiStatus = false;
        Log.i(TAG, "network connect");
        //1. enable network
        mWifiStatus = mWifiManager.enableNetwork(cfgId, true);
        Log.i(TAG, "enable network result: " + mWifiStatus);
        //2. begin read IP address and save it at locality
        if (mGetWifiIpThread == null) {
            Log.i(TAG, "start getWifiThread");
            mGetWifiIpThread = new getWifiIpThread();
            mGetWifiIpThread.start();
        }

        return mWifiStatus;
    }

    //4. network disconnect
    private boolean networkDisconnect() {
        boolean mWifiStatus = false;
        mWifiStatus = mWifiManager.disconnect();
        WifiIpAddr = IP_ERROR;
        return mWifiStatus;
    }

    //5. get IP address
    private String getWifiIp() {
        Log.i(TAG, "get Wifi Ip Address !");
        return WifiIpAddr;
    }

    //
    private int getRssi() {
        WifiInfo info = mWifiManager.getConnectionInfo();
        if (info == null) {
            Log.i(TAG, "wifiInfo is null !");
            return 1;
        }
        int rssi = info.getRssi();
        Log.i(TAG, "wifiInfo Rssi is [" + rssi + "]");
        return rssi;
    }

    private String GotWlanIP() {
        byte[] bytes_ip = new byte[4];
        int ip = 0;
        int ip_int = 0;
        String string_ip = "";
        if (mWifiManager == null) {
            Log.e(TAG, "connectWifi: Get wifi manager failed!");
            return string_ip;
        }
        WifiInfo info = mWifiManager.getConnectionInfo();
        ip = info.getIpAddress();

        Log.i(TAG, "connectWifi: GOT IP address [" + ip + "]");
        string_ip = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff),
                (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        Log.i(TAG, "connectWifi: GOT IP address [" + string_ip + "]");

        return string_ip;
    }

    //Start Wifi Iperf Server in Box
    private boolean iperfStart() {
        try {
            String cmd = "/system/xbin/iperf3 -s -P 0 -i 1 -p 5001 -f k";
            proc = Runtime.getRuntime().exec(cmd);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    //Start Wifi Iperf Server with parameter
    //server: iperf -s -P 0 -i 1 -p 5001 -f k
    //client: iperf -c xxx.xxx.xxx.xxx -w 1024k -i 1 -t 5 -P 4
    private boolean iperfWithPara(final String para) {
        new Thread() {
            @Override
            public void run() {
                String cmd = "/vendor/bin/iperf " + para;
                try {
                    proc = Runtime.getRuntime().exec(cmd);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.start();
        return true;
    }

    //Stop Wifi Iperf Server in Box
    private boolean iperfStop() {
        if (proc != null) {
            proc.destroy();
        }
        return true;
    }

    private boolean wifiScanBegin() {
        return mWifiManager.startScan();
    }

    private List<ScanResult> findWifiListScanned() {
        mWifiList = mWifiManager.getScanResults();
        return mWifiList;
    }

    private boolean performBTScan() {
        boolean btInitOn = true;
        int slice = 10;
        int total = 5 * 1000; /* second */
        int cnt = total / slice;
        int cur = 0;
        Log.i(TAG, "findBTDevice: Find BT device !");
        mBTState = BT_STATE_IDLE;

        if (mScanedBTDevices == null)
            mScanedBTDevices = new ArrayList<BTDevice>();
        /** register intent receiver */
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "findBTDevice: No bluetooth adapter");
            return false;
        }
        IntentFilter intent = new IntentFilter();
        intent.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intent.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intent.addAction(BluetoothDevice.ACTION_FOUND);
        mContext.registerReceiver(receiver, intent);
        if (!adapter.isEnabled()) {
            Log.e(TAG, "findBTDevice: BT now isn't in work!");
            if (!adapter.enable()) {
                Log.e(TAG, "findBTDevice: BT can't be enable!");
                mContext.unregisterReceiver(receiver);
                return false;
            }
            SystemClock.sleep(2000);
        }
        if (adapter.getState() != BluetoothAdapter.STATE_ON) {
            Log.e(TAG, "findBTDevice: start BT failed! state is " + adapter.getState());
            mContext.unregisterReceiver(receiver);
            return false;
        }
        /** scan remote BT devices */
        adapter.startDiscovery();
        cur = 0;
        while (mBTState != BT_STATE_DISCOVERY_FINISHED && cur < cnt && !mTerminate) {
            if (cur % 10 == 0)
                Log.i(TAG, "findBTDevice: Waiting BT_STATE_DISCOVERY_FINISHED(" + cur + "/" + cnt + ") ... ...");
            SystemClock.sleep(slice);
            cur++;
        }
        if (mBTState != BT_STATE_DISCOVERY_FINISHED) {
            Log.e(TAG, "findBTDevice: Scan BT failed!");
            mContext.unregisterReceiver(receiver);
            return false;
        }
        /** turn BT if initially off  */
        if (!btInitOn) {
            adapter.disable();
            mContext.unregisterReceiver(receiver);
        }
        return true;
    }

    private boolean performBTScanBLE() {
        boolean btInitOn = true;
        int slice = 50;
        int total = 10 * 1000; /* second */
        int cnt = total / slice;
        int cur = 0;
        Log.i(TAG, "findBTDevice: Find BT device !");
        mBTState = BT_STATE_IDLE;

        if (mScanedBTDevices == null)
            mScanedBTDevices = new ArrayList<BTDevice>();
        /** register intent receiver */
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "findBTDevice: No bluetooth adapter");
            return false;
        }

        if (!adapter.isEnabled()) {
            Log.e(TAG, "findBTDevice: BT now isn't in work!");
            if (!adapter.enable()) {
                Log.e(TAG, "findBTDevice: BT can't be enable!");
//				mContext.unregisterReceiver(receiver);
                return false;
            }
            SystemClock.sleep(2000);
        }
        if (adapter.getState() != BluetoothAdapter.STATE_ON) {
            Log.e(TAG, "findBTDevice: start BT failed! state is " + adapter.getState());
//			mContext.unregisterReceiver(receiver);
            return false;
        }
        /** scan remote BT devices */
        if (adapter.isDiscovering())
            adapter.cancelDiscovery();
        adapter.stopLeScan(mLeScanCallback);
        adapter.startLeScan(mLeScanCallback);

        cur = 0;
        while (mBTState != BT_STATE_DISCOVERY_FINISHED && cur < cnt && !mTerminate) {
            if (cur % 10 == 0)
                Log.i(TAG, "findBTDevice: Waiting BT_STATE_DISCOVERY_FINISHED(" + cur + "/" + cnt + ") ... ...");
            SystemClock.sleep(slice);
            cur++;
        }
        if (mBTState != BT_STATE_DISCOVERY_FINISHED) {
            Log.e(TAG, "findBTDevice: Scan BT failed!");
//			mContext.unregisterReceiver(receiver);
            return false;
        }
        /** turn BT if initially off  */
        if (!btInitOn) {
            adapter.disable();
//			mContext.unregisterReceiver(receiver);
        }
        return true;
    }

    /***
     * Do BT scan and check if device of 'address' found.
     *     address: BT MAC address.
     */
    private short findBTDeviceScanned(String address) {
        short ret = 1;
        Log.i(TAG, "filter bt by: address[" + address + "]");
        if (mScanedBTDevices != null) {
            for (int i = 0; i < mScanedBTDevices.size(); i++) {
                BluetoothDevice dev = mScanedBTDevices.get(i).mDevice;
                Log.i(TAG, "filter: Found Name[" + dev.getName() + "], MAC[" + dev.getAddress() + "]");
                if (address.equals(dev.getAddress())) {
                    ret = mScanedBTDevices.get(i).rssi;
                    Log.i(TAG, "filter: Found Name[" + dev.getName() + "], MAC[" + dev.getAddress() + "], RSSI[" + ret + "], MATCHED");
                    break;
                }
            }
        } else {
            Log.i(TAG, "filter: No scanned BT devices");
        }
        Log.i(TAG, "filter: address[" + address + "], ret[" + ret + "]");
        return ret;
    }

    /***
     * Do BT scan read device list.
     */
    private List<BTDevice> findBTListScanned() {
        List<BTDevice> ret = null;
        if (mScanedBTDevices != null) {
            ret = mScanedBTDevices;
        } else {
            Log.i(TAG, "filter: No scanned BT devices");
        }
        Log.i(TAG, "filter: findBTListScanned");
        return ret;
    }

    private boolean setBtState(boolean stat) {
        boolean flag = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "findBTDevice: No bluetooth adapter");
            return flag;
        }
        if (stat) {
            adapter.enable();
            btScanBegin(true);
            Log.i(TAG, "enableBluetooth");
        } else {
            adapter.disable();
            btScanBegin(false);
            Log.i(TAG, "disableBluetooth");
        }
        flag = true;
        return flag;
    }

    private boolean setBtStateBLE(boolean stat) {
        boolean flag = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "findBTDevice: No bluetooth adapter");
            return flag;
        }
        if (stat) {
            adapter.enable();
            btScanBeginBLE(true);
            Log.i(TAG, "enableBluetooth");
        } else {
            adapter.disable();
            btScanBeginBLE(false);
            Log.i(TAG, "disableBluetooth");
        }
        flag = true;
        return flag;
    }

    private boolean getBtState() {
        boolean flag = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "findBTDevice: No bluetooth adapter");
            return flag;
        }
        flag = adapter.isEnabled();
        return flag;
    }

    private boolean btScanBegin(boolean stat) {
        boolean flag = false;
        if (stat) {
            mTerminate = false;
            if (mBTScanThread == null) {
                mBTScanThread = new BTScanThread();
                //before any scanning cycle, clean scan list.
                mScanedBTDevices = null;
                mBTScanThread.start();
            }
        } else {
            mTerminate = true;
            mBTScanThread = null;
        }
        flag = true;
        return flag;
    }
    /* ---------------------Bluetooth Signal Test Interface----------------*/

    private boolean btScanBeginBLE(boolean stat) {
        boolean flag = false;
        if (stat) {
            mTerminate = false;
            if (mBTScanThreadBLE == null) {
                mBTScanThreadBLE = new BTScanThreadBLE();
                //before any scanning cycle, clean scan list.
                mScanedBTDevices = null;
                mBTScanThreadBLE.start();
            }
        } else {
            mTerminate = true;
            mBTScanThreadBLE = null;
        }
        flag = true;
        return flag;
    }

    private boolean getEthState() {
        ConnectivityManager connManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = connManager.getActiveNetworkInfo();
        if (info == null) {
            return false;
        }
        boolean ret = false;
        int type = info.getType();
        Log.i(TAG, "getEthState:: ConnectivityManager.getType:" + type);
        ret = (type == ConnectivityManager.TYPE_ETHERNET);
        return ret;

    }

    private void ping(String ipaddr) {
        Log.i(TAG, "ping :: ipaddr:" + ipaddr);
        Process proc = null;
        int i = 0;
        Log.i(TAG, "Ping Test Started");
        try {
            String msg;
            Runtime runtime = Runtime.getRuntime();
            proc = runtime.exec("ping -c 1 " + ipaddr);
            int waitFor = proc.waitFor();
            Log.i(TAG, "ping :: waitFor:" + waitFor);
            BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            while ((msg = in.readLine()) != null) {
                Log.i(TAG, msg);
                if (msg.contains("Unreachable")) {
                    break;
                }
                if (msg.contains("packet loss")) {
                    if (msg.contains("1 packets transmitted") && msg.contains("1 received")) {
                        PingResult = true;
                        PingTesting = false;
                        break;
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "ping excption:");
            e.printStackTrace();
        } catch (InterruptedException e) {
            Log.e(TAG, "ping excption:");
            e.printStackTrace();
        }
        Log.i(TAG, "ping returned " + PingResult);
        PingTesting = false;
    }

    private void pingReset() {
        PingResult = false;
        PingTesting = true;
    }

    private void wifiSleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean writeBtRcAddr(String mac) {
        if (mac != null) {
            mac = mac.toLowerCase();
        }
        boolean ret = false;
        int j = 0;
        Log.i(TAG, "predetermine the remote control mac address: " + mac);
        FileWriter f;
        File rcbtmacfile = new File(BTRCMACFILEPATH);
        if (!rcbtmacfile.exists()) {
            try {
                rcbtmacfile.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        try {
            f = new FileWriter(rcbtmacfile);
            f.write(mac);
            f.flush();
            f.close();
            ret = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ret;
    }

    private String readBtRcAddr() {
        String ret = "00:00:00:00:00:00";
        byte[] buf = new byte[17];
        Log.i(TAG, "get the predetermined remote control mac address");
        File rcbtmacfile = new File(BTRCMACFILEPATH);
        if (rcbtmacfile.exists()) {
            try {
                FileInputStream fstream = new FileInputStream(rcbtmacfile);
                fstream.read(buf, 0, 17);
                ret = new String(buf);
                fstream.close();
                Log.e(TAG, "Get rc bt mac: " + ret);
            } catch (IOException e) {
                Log.e(TAG, "Error: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            Log.i(TAG, "can't find the rc bt mac file");
        }
        return ret;
    }

    private class getWifiIpThread extends Thread {
        @Override
        public void run() {
            int i = 0;
            do {
                WifiIpAddr = GotWlanIP();
                Log.i(TAG, "ccccccccccccccccc HCI: WIFI IP addr: " + WifiIpAddr +
                        "---" + i + "---");
                wifiSleep(COUNT_SLICE);
                i++;
            } while (WifiIpAddr.contains(IP_ERROR) && i < CONNECT_COUNT);
            mGetWifiIpThread = null;
        }
    }

    /* ---------------------Wifi Signal Test Interface----------------*/
    /* ---------------------Bluetooth Signal Test Interface----------------*/
	/*^^^^^^^^^^^^^^^^^^^ 蓝牙部分的工作原理 ^^^^^^^^^^^^^^^^^^^^
	 * 蓝牙部分是本地完成对蓝牙模块的监听及处理工作，主要有如下三方面的工作：
	 * 1. setBtState: 完成蓝牙模块的启动/停止；完成蓝牙scan线程的启动
	 * 2. performBTScan：执行蓝牙的scan操作。本地管理着一个列表<mScanedBTDevices>，
	 * 记录着scan到的蓝牙设备, 我们scan的时间为total/slice (现在是5s), 将scan到的设备
	 * 记录到列表中。
	 * 3. findBTDeviceScanned：检查scan列表，看是否找到指定的设备
	 ^^^^^^^^^^^^^^^^^^^ 蓝牙部分的工作原理 ^^^^^^^^^^^^^^^^^^^^*/
    private class BTScanThread extends Thread {
        public void run() {
            while (!mTerminate) {
                Log.i(TAG, "@@@@@@@@@@@@@ perform BT scan start @@@@@@@@@@@@@ mTerminate: " + mTerminate);
                performBTScan();
                if (!mTerminate) {
                    SystemClock.sleep(100);
                }
            }
            Log.i(TAG, "@@@@@@@@@@@@@ perform BT scan stop @@@@@@@@@@@@@ mTerminate: " + mTerminate);
        }
    }

    private class BTScanThreadBLE extends Thread {
        public void run() {
            while (!mTerminate) {
                Log.i(TAG, "@@@@@@@@@@@@@ perform BT BLE scan start @@@@@@@@@@@@@ mTerminate: " + mTerminate);
                performBTScanBLE();
                if (!mTerminate) {
                    SystemClock.sleep(100);
                }
            }
            Log.i(TAG, "@@@@@@@@@@@@@ perform BT BLE scan stop @@@@@@@@@@@@@ mTerminate: " + mTerminate);
        }
    }
}
