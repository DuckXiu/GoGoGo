package com.zcshou.gogogo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.zcshou.database.DataBaseRoute;
import com.zcshou.utils.GoUtils;

import java.util.List;

public class RouteActivity extends BaseActivity {

    private ListView mListView;
    private TextView mTvNoData;
    private DataBaseRoute mDB;
    private List<DataBaseRoute.RouteInfo> mRouteList;
    private ArrayAdapter<DataBaseRoute.RouteInfo> mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route); // 使用已上传的 activity_route.xml

        // 设置标题
        setTitle("路线管理");

        // 设置返回按钮
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        // 初始化数据库
        mDB = new DataBaseRoute(this);

        initView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList(); // 每次界面显示时刷新数据
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish(); // 处理返回按钮点击
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initView() {
        mListView = findViewById(R.id.route_list_view);
        mTvNoData = findViewById(R.id.route_no_data);
        FloatingActionButton fabImport = findViewById(R.id.fab_import);

        // 点击悬浮按钮导入路线
        fabImport.setOnClickListener(v -> showImportDialog());

        // 长按列表项弹出菜单（导出/删除）
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            DataBaseRoute.RouteInfo info = mRouteList.get(position);
            showActionDialog(info);

        });


    }

    // 刷新列表数据
    private void refreshList() {
        mRouteList = mDB.getAllRoutes();
        if (mRouteList == null || mRouteList.isEmpty()) {
            mListView.setVisibility(View.GONE);
            mTvNoData.setVisibility(View.VISIBLE);
        } else {
            mListView.setVisibility(View.VISIBLE);
            mTvNoData.setVisibility(View.GONE);
            // DataBaseRoute.RouteInfo 已经重写了 toString() 返回 name，所以可以直接用 ArrayAdapter
            mAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, mRouteList);
            mListView.setAdapter(mAdapter);
        }
    }

    // 显示导入对话框
    private void showImportDialog() {
        // 获取剪贴板内容
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip().getItemCount() == 0) {
            GoUtils.DisplayToast(this, "剪贴板为空，请先复制路线数据");
            return;
        }

        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
        if (TextUtils.isEmpty(text)) {
            GoUtils.DisplayToast(this, "剪贴板内容无效");
            return;
        }
        String jsonContent = text.toString();

        // 弹出输入框让用户输入路线名称
        final EditText input = new EditText(this);
        input.setHint("请输入路线名称");

        new AlertDialog.Builder(this)
                .setTitle("导入路线")
                .setMessage("检测到剪贴板有数据，请输入名称保存：")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        name = "未命名路线_" + System.currentTimeMillis();
                    }
                    // 保存到数据库
                    mDB.saveRoute(name, jsonContent);
                    GoUtils.DisplayToast(RouteActivity.this, "导入成功");
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 显示操作对话框（导出/删除）
    private void showActionDialog(DataBaseRoute.RouteInfo routeInfo) {
        String[] options = {"导出到剪贴板", "删除路线"};

        new AlertDialog.Builder(this)
                .setTitle("操作: " + routeInfo.name)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // 导出
                            exportRoute(routeInfo);
                            break;
                        case 1: // 删除
                            confirmDelete(routeInfo);
                            break;
                    }
                })
                .show();
    }

    // 导出路线到剪贴板
    private void exportRoute(DataBaseRoute.RouteInfo routeInfo) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("RouteData", routeInfo.pointsJson);
        clipboard.setPrimaryClip(clip);
        GoUtils.DisplayToast(this, "路线数据已复制到剪贴板");
    }

    // 确认删除
    private void confirmDelete(DataBaseRoute.RouteInfo routeInfo) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除路线 \"" + routeInfo.name + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    mDB.deleteRoute(routeInfo.id);
                    GoUtils.DisplayToast(RouteActivity.this, "已删除");
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}