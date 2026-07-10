package com.everyday.pos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
                String t1   = o.optString("t1", time);
                String t2   = o.optString("t2", time);
                String t3   = o.optString("t3", time);

                printFormatted(op, date, time, rate, thb, rub, t1, t2, t3);
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
                                String rate, String thb, String rub,
                                String t1, String t2, String t3) {
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

            // ---- секция ОПЕРАЦИЯ ----
            p.printBitmap(buildSectionHeader("ОПЕРАЦИЯ"), null);
            p.setAlignment(0, null);
            p.setFontSize(22, null);
            row(p, "N операции", op);
            row(p, "Дата/время", date + " " + time);
            p.lineWrap(1, null);

            // ---- формула курса в рамке: THB x rate = RUB ----
            p.printBitmap(buildFxBitmap(rate), null);
            p.lineWrap(1, null);

            // ---- секция СУММА СЧЁТА ----
            p.printBitmap(buildSectionHeader("СУММА СЧЁТА"), null);
            p.setAlignment(0, null);
            p.setFontSize(22, null);
            row(p, "К конвертации", thb + " THB");
            p.lineWrap(1, null);

            // «К ОПЛАТЕ» — инверт-блок, крупнее и жирнее остального чека
            p.printBitmap(buildTotalBitmap(rub + " RUB"), null);
            p.lineWrap(1, null);

            // ---- таймлайн операции: ОПЛАЧЕНО -> КОНВЕРТАЦИЯ -> ГОТОВО ----
            p.printBitmap(buildTimelineBitmap(t1, t2, t3), null);
            p.lineWrap(1, null);

            // ---- штамп ОПЛАЧЕНО ----
            p.printBitmap(buildOkBitmap(), null);
            p.lineWrap(1, null);

            p.setAlignment(1, null);
            p.setFontSize(16, null);
            p.printText("* * * every day is a good day * * *\n", null);
            p.lineWrap(1, null);

            p.lineWrap(3, null);                     // отступ, чтобы чек отрезался
        } catch (RemoteException e) {
            toast("Ошибка принтера: " + e.getMessage());
        }
    }

    // чёрная плашка-заголовок секции с белым текстом (across всю ширину ленты)
    private Bitmap buildSectionHeader(String text) {
        int w = 384, h = 34;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        Canvas c = new Canvas(bmp);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.BLACK);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2);
        c.drawRect(1, 1, w - 1, h - 1, border);

        Paint text_ = new Paint(Paint.ANTI_ALIAS_FLAG);
        text_.setColor(Color.BLACK);
        text_.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        text_.setTextSize(20);
        text_.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = text_.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(text, 12, baseline, text_);
        return bmp;
    }

    // формула конвертации в рамке: THB x rate = RUB
    private Bitmap buildFxBitmap(String rate) {
        int w = 384, h = 60;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        Canvas c = new Canvas(bmp);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.BLACK);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(3);
        c.drawRect(6, 6, w - 6, h - 6, border);

        Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
        bold.setColor(Color.BLACK);
        bold.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        bold.setTextSize(26);
        bold.setTextAlign(Paint.Align.CENTER);

        String formula = "THB  x  " + rate + "  =  RUB";
        Paint.FontMetrics fm = bold.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(formula, w / 2f, baseline, bold);
        return bmp;
    }

    // инверт-блок «К ОПЛАТЕ» с крупной суммой
    private Bitmap buildTotalBitmap(String amount) {
        int w = 384, h = 84;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        Canvas c = new Canvas(bmp);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.BLACK);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(3);
        c.drawRect(2, 2, w - 2, h - 2, border);

        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setColor(Color.BLACK);
        lbl.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        lbl.setTextSize(16);
        lbl.setTextAlign(Paint.Align.CENTER);
        c.drawText("К ОПЛАТЕ", w / 2f, 24, lbl);

        Paint amt = new Paint(Paint.ANTI_ALIAS_FLAG);
        amt.setColor(Color.BLACK);
        amt.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        amt.setTextSize(38);
        amt.setTextAlign(Paint.Align.CENTER);
        c.drawText(amount, w / 2f, 64, amt);
        return bmp;
    }

    // таймлайн операции: 3 точки-вехи, соединённые линией, с подписями и временем
    private Bitmap buildTimelineBitmap(String t1, String t2, String t3) {
        int w = 384, h = 58;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        Canvas c = new Canvas(bmp);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.BLACK);
        line.setStrokeWidth(2);
        c.drawLine(w * 0.08f, 8, w * 0.92f, 8, line);

        String[] labels = {"ОПЛАЧЕНО", "КОНВЕРТАЦИЯ", "ГОТОВО"};
        String[] times  = {t1, t2, t3};
        float[] xs = {w * 0.17f, w * 0.5f, w * 0.83f};

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.BLACK);

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.BLACK);
        label.setTextSize(14);
        label.setTextAlign(Paint.Align.CENTER);

        Paint timeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        timeP.setColor(Color.DKGRAY);
        timeP.setTextSize(12);
        timeP.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < 3; i++) {
            c.drawCircle(xs[i], 8, 5, dot);
            c.drawText(labels[i], xs[i], 30, label);
            c.drawText(times[i], xs[i], 46, timeP);
        }
        return bmp;
    }

    // штамп «ОПЛАЧЕНО»: инверт-плашка с галочкой
    private Bitmap buildOkBitmap() {
        int w = 384, h = 46;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        Canvas c = new Canvas(bmp);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.BLACK);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2);
        c.drawRect(1, 1, w - 1, h - 1, border);

        Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
        check.setColor(Color.BLACK);
        check.setStyle(Paint.Style.STROKE);
        check.setStrokeWidth(4);
        check.setStrokeCap(Paint.Cap.ROUND);
        check.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        float cx = w / 2f - 60, cy = h / 2f;
        path.moveTo(cx - 8, cy);
        path.lineTo(cx - 2, cy + 8);
        path.lineTo(cx + 12, cy - 10);
        c.drawPath(path, check);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.BLACK);
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        text.setTextSize(22);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText("ОПЛАЧЕНО", w / 2f + 20, baseline, text);
        return bmp;
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
