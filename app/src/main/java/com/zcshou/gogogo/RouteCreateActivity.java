package com.zcshou.gogogo;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.MyLocationConfiguration;
import com.baidu.mapapi.map.MyLocationData;
import com.baidu.mapapi.map.OverlayOptions;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.elvishew.xlog.XLog;
import com.zcshou.database.DataBaseRoute;
import com.zcshou.utils.GoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RouteCreateActivity extends BaseActivity {
    private MapView mMapView;
    private BaiduMap mBaiduMap;
    private List<LatLng> mPoints = new ArrayList<>();
    private LocationClient mLocClient;
    private DataBaseRoute mDb;

    // 从 MainActivity 传过来的初始坐标
    private LatLng mInitLoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_create);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("绘制新路线");
        }

        mDb = new DataBaseRoute(this);

        // 1. 获取 Intent 数据
        parseIntent();

        initView();
        initMap();

        // 2. 初始化定位对象（但不立即开启，除非没有初始坐标）
        initLocationClient();

        // 3. 如果有初始坐标，直接飞过去；否则尝试定位
        if (mInitLoc != null) {
            mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(mInitLoc, 18.0f));
            // 同时设置一下蓝点位置，让界面好看
            updateBlueDot(mInitLoc.latitude, mInitLoc.longitude, 0, 0);
        } else {
            // 没有传过来坐标，只能靠自己了
            checkPermissionsAndLocate();
        }
    }

    // 解析 Intent 传递的坐标
    private void parseIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);
            if (lat != 0.0 && lng != 0.0) {
                mInitLoc = new LatLng(lat, lng);
            }
        }
    }

    private void checkPermissionsAndLocate() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, 100);
            } else {
                startLocation();
            }
        } else {
            startLocation();
        }
    }

    private void initView() {
        Button btnClear = findViewById(R.id.btn_create_clear);
        Button btnSave = findViewById(R.id.btn_create_save);
        ImageButton btnLoc = findViewById(R.id.btn_create_location);

        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                mPoints.clear();
                mBaiduMap.clear();
                // 清除后如果还有定位蓝点，要补回来
                if (mLocClient != null && mLocClient.getLastKnownLocation() != null) {
                    BDLocation loc = mLocClient.getLastKnownLocation();
                    updateBlueDot(loc.getLatitude(), loc.getLongitude(), loc.getRadius(), loc.getDirection());
                } else if (mInitLoc != null) {
                    updateBlueDot(mInitLoc.latitude, mInitLoc.longitude, 0, 0);
                }
                GoUtils.DisplayToast(this, "已清除");
            });
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> showSaveDialog());
        }

        // 点击定位按钮：强制请求一次最新位置
        if (btnLoc != null) {
            btnLoc.setOnClickListener(v -> {
                GoUtils.DisplayToast(this, "正在刷新位置...");
                checkPermissionsAndLocate();
            });
        }
    }

    private void initMap() {
        mMapView = findViewById(R.id.map_create);
        if (mMapView == null) return;

        mMapView.showZoomControls(false);
        mBaiduMap = mMapView.getMap();
        mBaiduMap.setMyLocationEnabled(true);
        mBaiduMap.setMyLocationConfiguration(new MyLocationConfiguration(
                MyLocationConfiguration.LocationMode.NORMAL, true, null));

        // 默认显示北京，等待 Intent 数据覆盖
        mBaiduMap.setMapStatus(MapStatusUpdateFactory.zoomTo(18.0f));

        mBaiduMap.setOnMapClickListener(new BaiduMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                addPoint(latLng);
            }
            @Override
            public void onMapPoiClick(com.baidu.mapapi.map.MapPoi mapPoi) {
                addPoint(mapPoi.getPosition());
            }
        });
    }

    private void initLocationClient() {
        try {
            mLocClient = new LocationClient(getApplicationContext());
            // 使用与 MainActivity 一致的高精度配置
            LocationClientOption option = new LocationClientOption();
            option.setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy);
            option.setCoorType("bd09ll");
            option.setScanSpan(1000);
            option.setOpenGnss(true);
            option.setIgnoreKillProcess(true);
            mLocClient.setLocOption(option);

            mLocClient.registerLocationListener(new BDAbstractLocationListener() {
                @Override
                public void onReceiveLocation(BDLocation location) {
                    if (location == null || mMapView == null) return;

                    // 过滤无效坐标
                    if (Math.abs(location.getLatitude()) < 0.1 && Math.abs(location.getLongitude()) < 0.1) return;
                    if (location.getLocType() == BDLocation.TypeServerError) return;

                    // 更新蓝点
                    updateBlueDot(location.getLatitude(), location.getLongitude(), location.getRadius(), location.getDirection());

                    // 如果是点击按钮触发的，或者是第一次没有Intent数据的情况，移动地图
                    // 这里我们简单处理：只要收到有效定位，且是最近几秒内的，就移动一下
                    // 但为了避免用户拖动地图时被强行拉回，我们只在startLocation后移动一次
                    // (由于 ScanSpan=1000，它会一直回调，这里建议只做蓝点更新，移动地图由按钮逻辑控制比较好)
                    // 但为了简单，我们还是让它首次回调时移动
                    if (mInitLoc == null) {
                        mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLng(
                                new LatLng(location.getLatitude(), location.getLongitude())));
                        mInitLoc = new LatLng(location.getLatitude(), location.getLongitude()); // 视为已初始化
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startLocation() {
        if (mLocClient != null) {
            if (mLocClient.isStarted()) {
                mLocClient.requestLocation();
            } else {
                mLocClient.start();
            }
            // 点击按钮强制移动的标记可以在这里处理，
            // 实际上 requestLocation 会触发回调，回调里如果不移动，按钮就白点了。
            // 所以我们在 listener 里做一个简单判断：如果是手动触发的，最好移动一下。
            // 这里为了稳妥，将 mInitLoc 置空，强制回调里移动一次
            mInitLoc = null;
        }
    }

    private void updateBlueDot(double lat, double lng, float radius, float direction) {
        MyLocationData locData = new MyLocationData.Builder()
                .accuracy(radius)
                .direction(direction)
                .latitude(lat)
                .longitude(lng)
                .build();
        mBaiduMap.setMyLocationData(locData);
    }

    // ... 绘图和保存逻辑保持不变 ...

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

    private void addPoint(LatLng latLng) {
        mPoints.add(latLng);
        BitmapDescriptor bitmap = getBitmapFromDrawable(R.drawable.icon_gcoding);
        if(bitmap != null) {
            mBaiduMap.addOverlay(new MarkerOptions().position(latLng).icon(bitmap));
        }
        if (mPoints.size() >= 2) {
            OverlayOptions polyline = new PolylineOptions()
                    .points(mPoints)
                    .width(10)
                    .color(0xAAFF0000);
            mBaiduMap.addOverlay(polyline);
        }
    }

    private void showSaveDialog() {
        if (mPoints.size() < 2) {
            GoUtils.DisplayToast(this, "请至少绘制两个点");
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("请输入路线名称");
        new AlertDialog.Builder(this)
                .setTitle("保存路线")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString();
                    if (!TextUtils.isEmpty(name)) {
                        saveToDb(name);
                    } else {
                        GoUtils.DisplayToast(this, "名称不能为空");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveToDb(String name) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (LatLng p : mPoints) {
                JSONObject j = new JSONObject();
                j.put("lat", p.latitude);
                j.put("lng", p.longitude);
                jsonArray.put(j);
            }
            mDb.saveRoute(name, jsonArray.toString());
            GoUtils.DisplayToast(this, "保存成功");
            finish();
        } catch (Exception e) {
            e.printStackTrace();
            GoUtils.DisplayToast(this, "保存失败");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(mMapView != null) mMapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(mMapView != null) mMapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(mLocClient != null) mLocClient.stop();
        if(mMapView != null) mMapView.onDestroy();
        if(mDb != null) mDb.close();
    }
}