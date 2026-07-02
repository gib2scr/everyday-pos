package com.everyday.pos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterException;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.SunmiPrinterService;

import org.json.JSONObject;

/**
 * Мост между сайтом (JavaScript) и встроенным термопринтером Sunmi.
 *
 * На сайте вызывается:  window.AndroidPrinter.printReceipt(jsonСтрока)
 * Сюда прилетает JSON { op, date, time, rate, thb, rub } и печатается чек 58мм.
 */
public class PrinterBridge {

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile SunmiPrinterService printerService;

    public PrinterBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        bindPrinter();
    }

    // подключаемся к службе принтера Sunmi при старте
    private void bindPrinter() {
        try {
            InnerPrinterManager.getInstance().bindService(ctx, new InnerPrinterCallback() {
                @Override
                protected void onConnected(SunmiPrinterService service) {
                    printerService = service;
                }

                @Override
                protected void onDisconnected() {
                    printerService = null;
                }
            });
        } catch (InnerPrinterException e) {
            e.printStackTrace();
        }
    }

    /** Вызывается из JS: window.AndroidPrinter.printReceipt(json) */
    @JavascriptInterface
    public void printReceipt(String json) {
        // JS-мост WebView вызывает этот метод НЕ в главном потоке —
        // а вся печать и Toast должны идти строго в главном потоке
        mainHandler.post(() -> {
            try {
                JSONObject o = new JSONObject(json);
                String op   = o.optString("op",   "—");
                String date = o.optString("date", "—");
                String time = o.optString("time", "—");
                String rate = o.optString("rate", "—");
                String thb  = o.optString("thb",  "0");
                String rub  = o.optString("rub",  "0");

                printFormatted(op, date, time, rate, thb, rub);
            } catch (Exception e) {
                toast("Ошибка печати: " + e.getMessage());
            }
        });
    }

    /** Проверка из JS, доступен ли принтер (необязательно) */
    @JavascriptInterface
    public boolean isReady() {
        return printerService != null;
    }

    private void printFormatted(String op, String date, String time,
                                String rate, String thb, String rub) {
        if (printerService == null) {
            toast("Принтер Sunmi не подключён");
            bindPrinter(); // пробуем переподключиться на следующий раз
            return;
        }
        try {
            SunmiPrinterService p = printerService;

            p.setAlignment(1, null);                 // по центру
            p.setFontSize(30, null);
            p.printText("EVERYDAY PAYMENT\n", null);
            p.setFontSize(20, null);
            p.printText("Система мгновенной конвертации\n", null);

            p.setAlignment(0, null);                 // по левому краю
            p.setFontSize(24, null);
            p.printText("--------------------------------\n", null);
            row(p, "Операция",   "RUB -> THB");
            row(p, "N операции", op);
            row(p, "Дата",       date);
            row(p, "Время",      time);
            row(p, "Курс",       "1 THB = " + rate + " RUB");
            p.printText("--------------------------------\n", null);
            row(p, "Сумма счёта", thb + " THB");

            // строка "К ОПЛАТЕ" — крупнее
            p.setFontSize(30, null);
            row(p, "К ОПЛАТЕ", rub + " RUB");
            p.setFontSize(24, null);
            p.printText("--------------------------------\n", null);

            p.setAlignment(1, null);
            p.setFontSize(28, null);
            p.printText("ОПЛАЧЕНО\n", null);
            p.setFontSize(20, null);
            p.printText("Спасибо за оплату!\n", null);
            p.printText("everydaypay.com\n", null);

            p.lineWrap(3, null);                     // отступ, чтобы чек отрезался
        } catch (RemoteException e) {
            toast("Ошибка принтера: " + e.getMessage());
        }
    }

    // строка вида "Метка ..... Значение" по ширине 32 символа (58мм)
    private void row(SunmiPrinterService p, String label, String value) throws RemoteException {
        int width = 32;
        int dots = width - label.length() - value.length();
        StringBuilder sb = new StringBuilder(label);
        if (dots < 1) {
            sb.append(" ");
        } else {
            for (int i = 0; i < dots; i++) sb.append(" ");
        }
        sb.append(value).append("\n");
        p.printText(sb.toString(), null);
    }

    private void toast(final String msg) {
        mainHandler.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }
}
