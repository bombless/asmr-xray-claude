package com.example.xraylocal;

import android.app.Application;

/** Process-wide context holder so the network/player layers can reach assets and prefs. */
public class App extends Application {
    static App instance;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }
}
