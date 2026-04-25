package com.example.myapp;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(true);

        webView.addJavascriptInterface(new SettingsInterface(this), "Android");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class SettingsInterface {
        private Context context;

        SettingsInterface(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void saveSettings(String cycle, String anchorDate) {
            Log.d("MainActivity", "Saving settings: cycle=" + cycle + ", anchorDate=" + anchorDate);
            SharedPreferences preferences = context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("cycle", cycle);
            editor.putString("anchorDate", anchorDate);
            editor.apply();
        }

        @JavascriptInterface
        public String getSettings() {
            SharedPreferences preferences = context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE);
            String cycle = preferences.getString("cycle", "");
            String anchorDate = preferences.getString("anchorDate", "");
            Log.d("MainActivity", "Getting settings: cycle=" + cycle + ", anchorDate=" + anchorDate);
            return "{\"cycle\":\"" + cycle + "\",\"anchorDate\":\"" + anchorDate + "\"}";
        }
    }
}