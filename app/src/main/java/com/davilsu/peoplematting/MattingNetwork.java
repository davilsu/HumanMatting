package com.davilsu.peoplematting;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

public class MattingNetwork {
    static {
        System.loadLibrary("MattingNetwork");
    }

    public native boolean Init(AssetManager mgr, int perfMode);

    public native int Process(AssetManager mgr, Bitmap bitmap, int modelIndex, boolean enableFP16, boolean enableInt8, boolean enableGPU);

    public native boolean isNetworkChange(int modelIndex, boolean enableFP16, boolean enableInt8);
}
