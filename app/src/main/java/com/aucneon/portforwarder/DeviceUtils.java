package com.aucneon.portforwarder;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowMetrics;

/**
 * 设备工具类
 * 用于检测设备类型和设置屏幕方向
 */
public class DeviceUtils {
    private static final String TAG = "DeviceUtils";

    /**
     * 检测设备是否为平板
     * @param activity 当前Activity
     * @return true表示平板，false表示手机
     */
    public static boolean isTabletDevice(Activity activity) {
        // 方法1：通过屏幕尺寸判断
        int screenWidth;
        int screenHeight;
        float density;
        float xdpi;
        float ydpi;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics windowMetrics = activity.getWindowManager().getCurrentWindowMetrics();
            Rect bounds = windowMetrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
            density = activity.getResources().getDisplayMetrics().density;
            xdpi = activity.getResources().getDisplayMetrics().xdpi;
            ydpi = activity.getResources().getDisplayMetrics().ydpi;
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            density = metrics.density;
            xdpi = metrics.xdpi;
            ydpi = metrics.ydpi;
        }

        float widthInches = screenWidth / xdpi;
        float heightInches = screenHeight / ydpi;
        double diagonalInches = Math.sqrt(Math.pow(widthInches, 2) + Math.pow(heightInches, 2));

        // 对角线大于7英寸认为是平板
        if (diagonalInches >= 7.0) {
            Log.d(TAG, "通过屏幕尺寸检测到平板设备，对角线: " + diagonalInches + " 英寸");
            return true;
        }

        // 方法2：通过屏幕密度和尺寸综合判断
        // 计算dp尺寸
        int widthDp = (int) (screenWidth / density);
        int heightDp = (int) (screenHeight / density);

        // 如果最小边大于600dp，认为是平板
        int smallestWidth = Math.min(widthDp, heightDp);
        if (smallestWidth >= 600) {
            Log.d(TAG, "通过dp尺寸检测到平板设备，最小宽度: " + smallestWidth + " dp");
            return true;
        }

        // 方法3：通过配置判断
        int screenSize = activity.getResources().getConfiguration().screenLayout
                & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK;
        boolean isTablet = screenSize >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE;
        
        if (isTablet) {
            Log.d(TAG, "通过配置检测到平板设备");
        }
        
        return isTablet;
    }

    /**
     * 根据设备类型设置屏幕方向
     * 手机：竖屏禁止旋转
     * 平板：允许旋转
     * @param activity 当前Activity
     */
    public static void setScreenOrientation(Activity activity) {
        boolean isTablet = isTabletDevice(activity);
        
        if (isTablet) {
            // 平板设备：允许旋转
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            Log.d(TAG, "检测到平板设备，允许屏幕旋转");
        } else {
            // 手机设备：竖屏禁止旋转
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            Log.d(TAG, "检测到手机设备，锁定竖屏模式");
        }
    }

    /**
     * 获取设备屏幕信息
     * @param activity 当前Activity
     * @return 屏幕信息字符串
     */
    public static String getScreenInfo(Activity activity) {
        int screenWidth;
        int screenHeight;
        float density;
        float xdpi;
        float ydpi;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics windowMetrics = activity.getWindowManager().getCurrentWindowMetrics();
            Rect bounds = windowMetrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
            density = activity.getResources().getDisplayMetrics().density;
            xdpi = activity.getResources().getDisplayMetrics().xdpi;
            ydpi = activity.getResources().getDisplayMetrics().ydpi;
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            density = metrics.density;
            xdpi = metrics.xdpi;
            ydpi = metrics.ydpi;
        }

        float widthInches = screenWidth / xdpi;
        float heightInches = screenHeight / ydpi;
        double diagonalInches = Math.sqrt(Math.pow(widthInches, 2) + Math.pow(heightInches, 2));

        int widthDp = (int) (screenWidth / density);
        int heightDp = (int) (screenHeight / density);
        int smallestWidth = Math.min(widthDp, heightDp);

        return String.format("屏幕信息 - 分辨率: %dx%d, 密度: %.2f, 对角线: %.1f英寸, 最小宽度: %ddp, 设备类型: %s",
                screenWidth, screenHeight, density,
                diagonalInches, smallestWidth, isTabletDevice(activity) ? "平板" : "手机");
    }
} 