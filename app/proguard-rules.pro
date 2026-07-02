# мост принтера вызывается из JS — не вырезать
-keepclassmembers class com.everyday.pos.PrinterBridge {
    public *;
}
-keep class com.sunmi.peripheral.printer.** { *; }
