package com.everyday.pos;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

/**
 * EVERYDAY PAYMENT — Android-обёртка.
 * Открывает уже готовый сайт (Netlify) на весь экран во WebView
 * и подключает мост к встроенному термопринтеру Sunmi.
 *
 * Сайт остаётся тем же самым — здесь только "рамка" + доступ к железу.
 */
public class MainActivity extends AppCompatActivity {

    // Адрес нашего сайта. Меняешь тут, если поменяется домен.
    private static final String SITE_URL = "https://eposdemo.netlify.app/";

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // прячем системные панели — приложение на весь экран (как раньше на терминале)
        hideSystemUI();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage/sessionStorage — курс кэшируется
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);

        // ссылки открываются внутри WebView, а не во внешнем браузере
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());

        // МОСТ: window.AndroidPrinter.printReceipt(json) внутри сайта → печать на Sunmi
        web.addJavascriptInterface(new PrinterBridge(this), "AndroidPrinter");

        web.loadUrl(SITE_URL);
    }

    // кнопка "назад" листает историю WebView, а не выходит из приложения
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
