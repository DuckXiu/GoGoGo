package com.zcshou.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.elvishew.xlog.XLog;

import java.util.ArrayList;
import java.util.List;

public class DataBaseRoute extends SQLiteOpenHelper {
    public static final String TABLE_NAME = "RouteHistory";
    public static final String COL_ID = "ID";
    public static final String COL_NAME = "ROUTE_NAME";
    public static final String COL_POINTS_JSON = "POINTS_JSON";
    public static final String COL_TIMESTAMP = "TIMESTAMP";

    private static final int DB_VERSION = 1;
    private static final String DB_NAME = "RouteHistory.db";

    private static final String CREATE_TABLE = "create table if not exists " + TABLE_NAME +
            " (" + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_NAME + " TEXT, " +
            COL_POINTS_JSON + " TEXT, " +
            COL_TIMESTAMP + " BIGINT)";

    public DataBaseRoute(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        String sql = "DROP TABLE IF EXISTS " + TABLE_NAME;
        db.execSQL(sql);
        onCreate(db);
    }

    // 保存路线
    public void saveRoute(String name, String pointsJson) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_NAME, name);
            cv.put(COL_POINTS_JSON, pointsJson);
            cv.put(COL_TIMESTAMP, System.currentTimeMillis());
            db.insert(TABLE_NAME, null, cv);
        } catch (Exception e) {
            XLog.e("DATABASE: Save route error", e);
        }
    }

    // === 新增：简单的路线信息模型 ===
    public static class RouteInfo {
        public int id;
        public String name;
        public String pointsJson;

        public RouteInfo(int id, String name, String pointsJson) {
            this.id = id;
            this.name = name;
            this.pointsJson = pointsJson;
        }

        @Override
        public String toString() {
            return name; // 用于 ListView 显示
        }
    }

    // === 新增：获取所有路线 ===
    public List<RouteInfo> getAllRoutes() {
        List<RouteInfo> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, null, null, null, null, null, COL_TIMESTAMP + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(cursor.getColumnIndex(COL_ID));
                    String name = cursor.getString(cursor.getColumnIndex(COL_NAME));
                    String json = cursor.getString(cursor.getColumnIndex(COL_POINTS_JSON));
                    list.add(new RouteInfo(id, name, json));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            XLog.e("DATABASE: Query error", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    // === 新增：删除路线 ===
    public void deleteRoute(int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NAME, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }
}