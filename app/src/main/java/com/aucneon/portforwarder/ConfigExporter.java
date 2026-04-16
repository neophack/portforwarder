package com.aucneon.portforwarder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConfigExporter {
    private static final String TAG = "ConfigExporter";
    public static final int REQUEST_EXPORT = 2001;
    public static final int REQUEST_IMPORT = 2002;

    private static class ExportData {
        int version = 2;
        String exportDate;
        String appVersion;
        List<ForwardConfig> configs;
    }

    /**
     * Launch the system file picker to export configs as JSON.
     */
    public static void startExport(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "portforwarder_" + timestamp + ".json");
        activity.startActivityForResult(intent, REQUEST_EXPORT);
    }

    /**
     * Launch the system file picker to import configs from JSON.
     */
    public static void startImport(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        activity.startActivityForResult(intent, REQUEST_IMPORT);
    }

    /**
     * Write configs to the given URI.
     */
    public static boolean exportToUri(Context context, Uri uri, List<ForwardConfig> configs) {
        try {
            ExportData data = new ExportData();
            data.exportDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(new Date());
            data.appVersion = "3.0";
            data.configs = configs;

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(data);

            OutputStream os = context.getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(json.getBytes("UTF-8"));
                os.flush();
                os.close();
                Log.i(TAG, "Exported " + configs.size() + " configs to " + uri);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
        }
        return false;
    }

    /**
     * Read configs from the given URI. Returns null on failure.
     */
    public static List<ForwardConfig> importFromUri(Context context, Uri uri) {
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            Gson gson = new Gson();
            String json = sb.toString();

            // Try parsing as ExportData first
            try {
                ExportData data = gson.fromJson(json, ExportData.class);
                if (data != null && data.configs != null) {
                    Log.i(TAG, "Imported " + data.configs.size() + " configs (v" + data.version + ")");
                    return data.configs;
                }
            } catch (Exception e) {
                // Try as plain list
            }

            // Fallback: try parsing as plain list
            Type listType = new TypeToken<List<ForwardConfig>>(){}.getType();
            List<ForwardConfig> configs = gson.fromJson(json, listType);
            if (configs != null) {
                Log.i(TAG, "Imported " + configs.size() + " configs (plain format)");
                return configs;
            }
        } catch (Exception e) {
            Log.e(TAG, "Import failed", e);
        }
        return null;
    }

    /**
     * Validate imported configs, filtering out invalid ones.
     */
    public static List<ForwardConfig> validateConfigs(List<ForwardConfig> configs) {
        List<ForwardConfig> valid = new ArrayList<>();
        for (ForwardConfig c : configs) {
            if (c.targetHost != null && !c.targetHost.isEmpty()
                    && c.listenPort > 0 && c.listenPort <= 65535
                    && c.targetPort > 0 && c.targetPort <= 65535
                    && (c.protocol == PortForwarder.PROTOCOL_TCP || c.protocol == PortForwarder.PROTOCOL_UDP)) {
                if (c.name == null || c.name.isEmpty()) c.name = "导入配置";
                valid.add(c);
            }
        }
        return valid;
    }
}
