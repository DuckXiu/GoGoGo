package com.zcshou.gogogo;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.CheckBox;
import androidx.appcompat.app.ActionBar;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.OverlayOptions;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.zcshou.database.DataBaseRoute;
import com.zcshou.service.ServiceGo;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RouteRunActivity extends BaseActivity {
    private MapView mMapView;
    private BaiduMap mBaiduMap;
    private TextView mTvRouteName;
    private EditText mEtSpeed;
    private Button mBtnToggle;

    private DataBaseRoute mDb;
    private List<LatLng> mCurrentRoutePoints = new ArrayList<>();
    private boolean isSimulating = false;

    private ServiceGo.ServiceGoBinder mServiceBinder;
    private boolean isBound = false;
    private EditText mEtLoopCount;
    private CheckBox mCbSpeedFloat;
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceBinder = (ServiceGo.ServiceGoBinder) service;
            isBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBinder = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_run);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("路线模拟");
        }

        mDb = new DataBaseRoute(this);
        initView();
        initMap();
        bindGoService();
    }

    private void initView() {
        mTvRouteName = findViewById(R.id.tv_current_route);
        mEtSpeed = findViewById(R.id.et_run_speed);
        mEtLoopCount = findViewById(R.id.et_loop_count);
        // === 获取 CheckBox ===
        mCbSpeedFloat = findViewById(R.id.cb_speed_float);

        mBtnToggle = findViewById(R.id.btn_run_toggle);
        Button btnLoad = findViewById(R.id.btn_run_load);

        btnLoad.setOnClickListener(v -> showRouteList());
        mBtnToggle.setOnClickListener(v -> toggleSimulation());
    }


    private void initMap() {
        mMapView = findViewById(R.id.map_run);
        mMapView.showZoomControls(false);
        mBaiduMap = mMapView.getMap();
        // 模拟页面不需要自己定位，只需要显示路线
    }

    private void showRouteList() {
        if(isSimulating) {
            GoUtils.DisplayToast(this, "请先停止模拟");
            return;
        }

        List<DataBaseRoute.RouteInfo> routes = mDb.getAllRoutes();
        if (routes.isEmpty()) {
            GoUtils.DisplayToast(this, "暂无保存的路线，请先去绘制");
            return;
        }

        String[] names = new String[routes.size()];
        for (int i = 0; i < routes.size(); i++) {
            names[i] = routes.get(i).name;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择路线")
                .setItems(names, (dialog, which) -> {
                    loadRoute(routes.get(which));
                })
                .show();
    }

    private void loadRoute(DataBaseRoute.RouteInfo route) {
        try {
            mCurrentRoutePoints.clear();
            mBaiduMap.clear();

            JSONArray ja = new JSONArray(route.pointsJson);
            for (int i = 0; i < ja.length(); i++) {
                JSONObject jo = ja.getJSONObject(i);
                mCurrentRoutePoints.add(new LatLng(jo.getDouble("lat"), jo.getDouble("lng")));
            }

            drawRouteOnMap();
            mTvRouteName.setText("当前路线: " + route.name);

            // 移动视角到起点
            if(!mCurrentRoutePoints.isEmpty()){
                mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(mCurrentRoutePoints.get(0), 16.0f));
            }

        } catch (Exception e) {
            e.printStackTrace();
            GoUtils.DisplayToast(this, "路线解析失败");
        }
    }

    private void drawRouteOnMap() {
        if (mCurrentRoutePoints.size() < 2) return;

        // 起点和终点画个标记
        BitmapDescriptor bitmap = getBitmapFromDrawable(R.drawable.icon_gcoding);
        if(bitmap != null) {
            mBaiduMap.addOverlay(new MarkerOptions().position(mCurrentRoutePoints.get(0)).icon(bitmap));
            mBaiduMap.addOverlay(new MarkerOptions().position(mCurrentRoutePoints.get(mCurrentRoutePoints.size()-1)).icon(bitmap));
        }

        OverlayOptions polyline = new PolylineOptions()
                .points(mCurrentRoutePoints)
                .width(10)
                .color(0xAA0000FF); // 蓝色路线
        mBaiduMap.addOverlay(polyline);
    }

    private void toggleSimulation() {
        if (mCurrentRoutePoints.size() < 2) {
            GoUtils.DisplayToast(this, "请先选择一条有效路线");
            return;
        }
        if (!isBound || mServiceBinder == null) {
            GoUtils.DisplayToast(this, "服务未连接");
            bindGoService();
            return;
        }

        if (isSimulating) {
            mServiceBinder.stopRoute();
            isSimulating = false;
            mBtnToggle.setText("开始模拟");
        } else {
            if (!GoUtils.isDeveloperOptionsEnabled(this)) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_dev));
                return;
            }


            String speedStr = mEtSpeed.getText().toString();
            double speed = TextUtils.isEmpty(speedStr) ? 30 : Double.parseDouble(speedStr);

            String loopStr = mEtLoopCount.getText().toString();
            int loopCount = TextUtils.isEmpty(loopStr) ? 1 : Integer.parseInt(loopStr);
            if (loopCount <= 0) loopCount = 1;

            // === 获取浮动状态 ===
            boolean isFloat = mCbSpeedFloat != null && mCbSpeedFloat.isChecked();

            List<double[]> wgsPoints = new ArrayList<>();
            for (LatLng p : mCurrentRoutePoints) {
                wgsPoints.add(MapUtils.bd2wgs(p.longitude, p.latitude));
            }

            // === 传递给 Service ===
            mServiceBinder.startRoute(wgsPoints, speed , loopCount, isFloat);

            isSimulating = true;
            mBtnToggle.setText("停止模拟");
            String tips = "开始模拟，循环 " + loopCount + " 次";
            if (isFloat) tips += " (速度浮动开启)";
            GoUtils.DisplayToast(this, tips);
        }
    }

    // ... 记得复制 getBitmapFromDrawable ...
    private BitmapDescriptor getBitmapFromDrawable(int resId) {
        android.graphics.drawable.Drawable drawable = androidx.core.content.ContextCompat.getDrawable(this, resId);
        if (drawable == null) return null;
        int width = (int) (30 * getResources().getDisplayMetrics().density);
        int height = (int) (30 * getResources().getDisplayMetrics().density);
        drawable.setBounds(0, 0, width, height);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        drawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    private void bindGoService() {
        Intent intent = new Intent(this, ServiceGo.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(mMapView != null) mMapView.onDestroy();
        if(isBound) unbindService(mConnection);
    }
}