package com.yuanbao.earbuds;

import android.app.Application;

/**
 * v1.4.1：Application 入口，目前只做一件事——安装崩溃捕获。
 *
 * 组件（Activity / Service / Receiver）都可能比 Activity 更早被系统拉起，
 * 崩溃捕获必须挂在 Application 上才能保证任何入口的闪退都留得下堆栈。
 */
public class EarbudsApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashGuard.install(this);
    }
}
