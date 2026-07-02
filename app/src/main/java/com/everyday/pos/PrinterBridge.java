package com.everyday.pos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
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

    private static final long PRINT_COOLDOWN_MS = 10_000; // не чаще раза в 10 сек — защита от случайного дубля чека

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile SunmiPrinterService printerService;
    private volatile long lastPrintAt = 0;

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
        long now = System.currentTimeMillis();
        long sinceLast = now - lastPrintAt;
        if (sinceLast < PRINT_COOLDOWN_MS) {
            long waitSec = (PRINT_COOLDOWN_MS - sinceLast) / 1000 + 1;
            toast("Подождите " + waitSec + " сек. перед повторной печатью");
            return;
        }
        lastPrintAt = now;
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

            p.setAlignment(1, null);
            p.printBitmap(buildLogoBitmap(), null);
            p.lineWrap(1, null);
            p.setFontSize(18, null);
            p.printText("Система мгновенной конвертации\n", null);
            p.lineWrap(1, null);

            thinRule(p);
            p.setAlignment(0, null);
            p.setFontSize(22, null);
            row(p, "Операция",    "RUB -> THB");
            row(p, "N операции",  op);
            row(p, "Дата",        date);
            row(p, "Время",       time);
            row(p, "Курс",        "1 THB = " + rate + " RUB");
            thinRule(p);

            row(p, "Сумма счёта", thb + " THB");
            p.lineWrap(1, null);

            // «К ОПЛАТЕ» — акцентная строка, крупнее и жирнее остального чека
            p.setAlignment(1, null);
            p.setFontSize(20, null);
            p.printText("К ОПЛАТЕ\n", null);
            p.setFontSize(38, null);
            p.printText(rub + " RUB\n", null);
            p.lineWrap(1, null);
            thinRule(p);

            p.setAlignment(1, null);
            p.setFontSize(26, null);
            p.printText("✓ ОПЛАЧЕНО\n", null);
            p.setFontSize(18, null);
            p.lineWrap(1, null);
            p.printText("Спасибо за оплату!\n", null);
            p.printText("everydaypay.com\n", null);

            p.lineWrap(3, null);                     // отступ, чтобы чек отрезался
        } catch (RemoteException e) {
            toast("Ошибка принтера: " + e.getMessage());
        }
    }

    // тонкая сплошная линия-разделитель на всю ширину ленты (аккуратнее пунктира из дефисов)
    private void thinRule(SunmiPrinterService p) throws RemoteException {
        Bitmap rule = Bitmap.createBitmap(384, 2, Bitmap.Config.ARGB_8888);
        rule.eraseColor(Color.BLACK);
        p.setAlignment(1, null);
        p.printBitmap(rule, null);
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

    // рисуем логотип EVERYDAY PAYMENT программно (текст + зелёная плашка) — без внешних файлов
    private Bitmap buildLogoBitmap() {
        int w = 384, h = 90;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        Canvas c = new Canvas(bmp);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.BLACK);
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        text.setTextSize(46);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = h * 0.42f - (fm.ascent + fm.descent) / 2f;
        c.drawText("EVERYDAY", w / 2f, baseline, text);

        // зелёная плашка PAYMENT — на термопринтере цвет не печатается (только ч/б),
        // поэтому рисуем как чёрный прямоугольник с белым текстом (плашка читается контрастом)
        Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(Color.BLACK);
        float plateW = 190, plateH = 30;
        float plateL = (w - plateW) / 2f, plateT = h * 0.62f;
        c.drawRoundRect(plateL, plateT, plateL + plateW, plateT + plateH, 15, 15, plate);

        Paint plateText = new Paint(Paint.ANTI_ALIAS_FLAG);
        plateText.setColor(Color.WHITE);
        plateText.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        plateText.setTextSize(20);
        plateText.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics pfm = plateText.getFontMetrics();
        float plateBaseline = plateT + plateH / 2f - (pfm.ascent + pfm.descent) / 2f;
        c.drawText("PAYMENT", w / 2f, plateBaseline, plateText);

        return bmp;
    }

    private void toast(final String msg) {
        mainHandler.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }
}
