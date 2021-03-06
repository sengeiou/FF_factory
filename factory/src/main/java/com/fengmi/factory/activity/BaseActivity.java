package com.fengmi.factory.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fengmi.factory.ICommandService;
import com.fengmi.factory.service.CommandService;
import com.fengmi.factory_test_interf.sdk_globle.FactorySetting;
import com.fengmi.factory_test_interf.sdk_globle.TvCommandDescription;

import java.util.Objects;

public abstract class BaseActivity extends Activity {
    protected static final String TAG = "FactoryTest";
    protected static final boolean PASS = true;
    protected static final boolean FAIL = true;
    protected static final int RUN_TASK = 20000;
    private ICommandService sService;
    private String mCaseId = null;
    private String mCaseName = null;
    private String mCasePara = null;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(@NonNull Message msg) {
            if (msg.what == RUN_TASK) {
                handleCommand(mCaseId, mCasePara);
                return;
            }
            super.handleMessage(msg);
        }
    };
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sService = ICommandService.Stub.asInterface(service);
            Log.d(TAG, "Service connected Activity:" + mCaseName);
            mHandler.sendEmptyMessage(RUN_TASK);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG, "Service has unexpectedly disconnected");
            sService = null;
        }
    };
    private BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            //still use original case id when communicate with command service
            String action = intent.getAction();
            String filter = null;
            if (mCaseId.substring(0, 1).equals(FactorySetting.COMMAND_PRODUCT_TYPE_TV)) {
                Log.i(TAG, "commandReceiver: product: TV Type for filter");
                filter = TvCommandDescription.getFilterActionForCmd(mCaseId);
            } else {
                Log.e(TAG, "commandReceiver:  no this product type");
            }
            Log.i(TAG, "BaseActivity commandReceiver: action = " + action);
            Log.i(TAG, "BaseActivity commandReceiver: mCaseId = " + mCaseId);
            Log.i(TAG, "BaseActivity commandReceiver: mCaseName = " + mCaseName);
            Log.i(TAG, "BaseActivity commandReceiver: mCasePara = " + mCasePara);
            if (action != null && action.equals(filter)) {
                Log.i(TAG, "BaseActivity commandReceiver: right action!");
                String param = intent.getStringExtra(FactorySetting.EXTRA_BROADCAST_CMDPARA);
                boolean sameParam = Objects.equals(param, mCasePara);
                Log.i(TAG, "BaseActivity commandReceiver: received param = " + param);
                if (!sameParam) return;
                int cmdtype = intent.getIntExtra(FactorySetting.EXTRA_BROADCAST_CONTROLTYPE, FactorySetting.COMMAND_TASK_BUSINESS);
                String cmdid = intent.getStringExtra(FactorySetting.EXTRA_BROADCAST_CONTROLID);
                String cmdpara = intent.getStringExtra(FactorySetting.EXTRA_BROADCAST_CONTROLPARA);
                Log.i(TAG, "call handleControlMsg para0:" + cmdtype + " para1:" + cmdid + " para2:" + cmdpara);
                handleControlMsg(cmdtype, cmdid, cmdpara);
            }
        }
    };

    protected void handleCommand(String cmdID, String cmdPara){}

    protected void handleControlMsg(int cmdType, String cmdID, String cmdPara){}

    protected void setResult(String id, String msg) {
        setResult(id, msg, true);
    }

    protected void setResult(String id, byte[] msg) {
        setResult(id, msg, true);
    }

    protected void setResult(String id, boolean result) {
        setResult(id, result, true);
    }

    protected void setResult(String id, String msg, boolean finish) {
        try {
            if (sService != null) {
                if (!msg.equals("noresponse"))
                    sService.setResult_string((id == null ? mCaseId : id), msg);
                if (finish) {
                    sService.finishCommand(mCaseId, mCasePara);
                }
            }
        } catch (RemoteException re) {
            re.printStackTrace();
        }
        if (finish) {
            finish();
        }
    }

    protected void setResult(String id, byte[] msg, boolean finish) {
        try {
            if (sService != null) {
                sService.setResult_byte((id == null ? mCaseId : id), msg);
                if (finish) {
                    sService.finishCommand(mCaseId, mCasePara);
                }
            }
        } catch (RemoteException re) {
            re.printStackTrace();
        }
        if (finish) {
            finish();
        }
    }

    protected void setResult(String id, boolean result, boolean finish) {
        Log.i(TAG, "=========================BaseActivity setResult ==========================");
        Log.i(TAG, "=========================BaseActivity activity:" + getLocalClassName() + " ==========================");
        try {
            if (sService != null) {
                sService.setResult_bool((id == null ? mCaseId : id), result);
                if (finish) {
                    sService.finishCommand(mCaseId, mCasePara);
                }
            }
        } catch (RemoteException re) {
            re.printStackTrace();
        }
        Log.i(TAG, "=========================BaseActivity setResult==========================");
        if (finish) {
            finish();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String action = null;
        Log.d(TAG, "FactoryActivity onCreate");
        Intent intent = getIntent();
        mCaseId = intent.getStringExtra(FactorySetting.EXTRA_CMDID);
        mCasePara = intent.getStringExtra(FactorySetting.EXTRA_CMDPARA);
        if (mCaseId == null) {
            Log.e(TAG, "not support cmd name:" + mCaseId + " param:" + mCasePara);
            finish();
            return;
        }
        mCaseName = getLocalClassName();
        Log.i(TAG, "caseId:$mCaseId para:" + mCasePara + " activity:" + mCaseName);

        if (mCaseId.substring(0, 1).equals(FactorySetting.COMMAND_PRODUCT_TYPE_TV)) {
            Log.i(TAG, "product: TV Type");
            action = TvCommandDescription.getFilterActionForCmd(mCaseId);
        } else {
            Log.e(TAG, "no this product type");
        }

        Intent serviceIntent = new Intent(this, CommandService.class);
        serviceIntent.setAction(action);
        serviceIntent.setType(String.valueOf(this.hashCode()));
        serviceIntent.putExtra(FactorySetting.EXTRA_CMDID, mCaseId);
        serviceIntent.putExtra(FactorySetting.EXTRA_CMDPARA, mCasePara);

        if (!bindService(serviceIntent, mConnection,
                Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "bind to Service failed");
            finish();
            return;
        }
        if (action != null) {
            IntentFilter filter = new IntentFilter(action);
            registerReceiver(commandReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "BaseActivity onDestroy in");
        if (sService != null) {
            unbindService(mConnection);
            Log.i(TAG, "BaseActivity unbindService");
        }
        Log.i(TAG, "BaseActivity unregisterReceiver");
        unregisterReceiver(commandReceiver);
        Log.i(TAG, "BaseActivity onDestroy done");
    }
}


