package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;

import java.util.List;
import java.util.Random;

public class ServiceGo extends Service {
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;

    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;

    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";

    // 通知相关常量
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    // 停止服务的 Action
    private static final String SERVICE_GO_NOTE_ACTION_STOP_ROUTE = "StopRoute";

    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;

    // 摇杆对象（已禁用，但保留变量以防报错）
    private JoyStick mJoyStick;

    // === 模拟与循环相关变量 ===
    private boolean isRouteSimulating = false;
    private List<double[]> mRoutePoints;
    private int mCurrentRouteIndex = 0;
    private float mDistanceTraveledInSegment = 0;
    private int mTargetLoopCount = 1;
    private int mCurrentLoopCount = 0;

    // === 速度浮动相关 ===
    private boolean isSpeedFloat = false;
    private Random mRandom = new Random();

    // === 回调接口 ===
    public interface OnLocationUpdateListener {
        void onLocationUpdate(double lng, double lat, float bearing);
    }
    private OnLocationUpdateListener mLocationUpdateListener;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        removeTestProviderNetwork();
        addTestProviderNetwork();
        removeTestProviderGPS();
        addTestProviderGPS();
        initGoLocation();
        initNotification();
        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // === 核心逻辑：拦截停止指令 ===
        if (intent != null && SERVICE_GO_NOTE_ACTION_STOP_ROUTE.equals(intent.getAction())) {
            XLog.i("ServiceGo: Received Stop Command from Notification.");
            stopSelf(); // 直接终止服务，会触发 onDestroy
            return START_NOT_STICKY;
        }

        // 处理正常的启动逻辑
        if (intent != null) {
            mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
        }
        if (mJoyStick != null) {
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
        return super.onStartCommand(intent, flags, startId);
    }
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        XLog.i("ServiceGo: 监测到后台任务被清理，即将停止模拟服务");

        // 停止模拟状态
        isRouteSimulating = false;

        // 移除前台通知（参数 true 表示同时移除状态栏通知）
        stopForeground(true);

        // 停止服务，这将触发 onDestroy() 进行彻底的资源释放（如移除模拟定位提供者）
        stopSelf();
    }
    @Override
    public void onDestroy() {
        isStop = true;

        // 停止并移除位置更新线程
        if (mLocHandler != null) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
        }
        if (mLocHandlerThread != null) {
            mLocHandlerThread.quit();
        }

        // 销毁摇杆
        if (mJoyStick != null) {
            mJoyStick.destroy();
        }

        // 移除模拟定位提供者
        removeTestProviderNetwork();
        removeTestProviderGPS();

        // 移除前台通知
        stopForeground(STOP_FOREGROUND_REMOVE);

        XLog.i("ServiceGo: Service Destroyed fully.");
        super.onDestroy();
    }

    private void initNotification() {
        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        // 点击通知主体跳转回 MainActivity
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);

        // === 关键修改：创建直接发送给 Service 的 Intent ===
        Intent stopIntent = new Intent(this, ServiceGo.class);
        stopIntent.setAction(SERVICE_GO_NOTE_ACTION_STOP_ROUTE);
        // 使用 getService 而不是 getBroadcast
        PendingIntent stopPendingPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, "停止模拟", stopPendingPI)) // 按钮点击后触发 onStartCommand
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        // 禁用摇杆，保持为空
        XLog.i("ServiceGo: JoyStick disabled by user request.");
    }

    private void initGoLocation() {
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        mLocHandlerThread.start();
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);
                    if (!isStop) {
                        if (isRouteSimulating && mRoutePoints != null && mRoutePoints.size() > 1) {
                            processRouteStep();
                        }
                        setLocationNetwork();
                        setLocationGPS();
                        if (mLocationUpdateListener != null) {
                            mLocationUpdateListener.onLocationUpdate(mCurLng, mCurLat, mCurBea);
                        }
                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };
        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void processRouteStep() {
        if (mRoutePoints == null || mRoutePoints.size() < 2) return;

        // 如果到达当前路径段的终点
        if (mCurrentRouteIndex >= mRoutePoints.size() - 1) {
            mCurrentLoopCount++;
            if (mCurrentLoopCount < mTargetLoopCount) {
                // 循环模式：重置到起点
                mCurrentRouteIndex = 0;
                mDistanceTraveledInSegment = 0;
                double[] start = mRoutePoints.get(0);
                mCurLng = start[0];
                mCurLat = start[1];
            } else {
                // 结束模拟
                isRouteSimulating = false;
            }
            return;
        }

        double[] startPoint = mRoutePoints.get(mCurrentRouteIndex);
        double[] endPoint = mRoutePoints.get(mCurrentRouteIndex + 1);

        float[] results = new float[2];
        Location.distanceBetween(startPoint[1], startPoint[0], endPoint[1], endPoint[0], results);
        float totalSegmentDistance = results[0];
        float bearing = results[1];

        // 计算当前步进速度
        double currentStepSpeed = mSpeed;
        if (isSpeedFloat) {
            double factor = 0.9 + (mRandom.nextDouble() * 0.2); // 0.9 ~ 1.1
            currentStepSpeed = mSpeed * factor;
        }

        double stepDistance = currentStepSpeed * 0.1; // 100ms 移动的距离
        mDistanceTraveledInSegment += stepDistance;

        if (mDistanceTraveledInSegment >= totalSegmentDistance) {
            // 到达本段终点，准备进入下一段
            mCurrentRouteIndex++;
            mDistanceTraveledInSegment = 0;
            mCurLng = endPoint[0];
            mCurLat = endPoint[1];
            mCurBea = bearing;
        } else {
            // 插值计算当前位置
            double ratio = mDistanceTraveledInSegment / totalSegmentDistance;
            mCurLat = startPoint[1] + (endPoint[1] - startPoint[1]) * ratio;
            mCurLng = startPoint[0] + (endPoint[0] - startPoint[0]) * ratio;
            mCurBea = bearing;
        }

        if (mJoyStick != null) mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
    }

    // === Mock Location Providers ===
    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {}
    }

    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {}
    }

    private void setLocationGPS() {
        try {
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);
            loc.setAltitude(mCurAlt);
            loc.setBearing(mCurBea);
            loc.setLatitude(mCurLat);
            loc.setLongitude(mCurLng);
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);
            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {}
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {}
    }

    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false, true, true, true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false, true, true, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {}
    }

    private void setLocationNetwork() {
        try {
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);
            loc.setAltitude(mCurAlt);
            loc.setBearing(mCurBea);
            loc.setLatitude(mCurLat);
            loc.setLongitude(mCurLng);
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {}
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            isRouteSimulating = false;
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            if (mJoyStick != null) mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        public void startRoute(List<double[]> routePoints, double speed, int loopCount, boolean speedFloat) {
            if (routePoints == null || routePoints.isEmpty()) return;

            mRoutePoints = routePoints;
            mSpeed = speed;
            mTargetLoopCount = loopCount;
            mCurrentLoopCount = 0;
            isSpeedFloat = speedFloat;

            mCurrentRouteIndex = 0;
            mDistanceTraveledInSegment = 0;

            mCurLng = routePoints.get(0)[0];
            mCurLat = routePoints.get(0)[1];
            isRouteSimulating = true;
        }

        public void stopRoute() {
            isRouteSimulating = false;
        }

        public void setOnLocationUpdateListener(OnLocationUpdateListener listener) {
            mLocationUpdateListener = listener;
        }

        public double[] getCurrentPosition() {
            return new double[]{mCurLng, mCurLat};
        }
    }
}