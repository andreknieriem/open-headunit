package com.andrerinas.openheadunit;
interface IWifiScanControl {
    int readState(int mode) = 0;
    boolean apply(IBinder owner, String id, int mode, int original) = 1;
    boolean restore(String id, int mode, int original) = 2;
    void destroy() = 16777114;
}
