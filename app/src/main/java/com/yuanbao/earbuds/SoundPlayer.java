package com.yuanbao.earbuds;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * 弹窗音效播放。
 *
 * 规则：
 *  - 开关关闭时什么都不做（默认关闭，避免打扰）
 *  - 用户选了音频文件就用 MediaPlayer 播该文件
 *  - 没选就用系统默认通知提示音
 *
 * 资源释放：播完/onError 都自动 release，并用一个静态引用保证
 * 同一时刻只有一个实例在响，不会叠加。
 */
public final class SoundPlayer {

    private static volatile MediaPlayer current;

    private SoundPlayer() {
    }

    public static void play(Context c, Prefs prefs) {
        if (c == null || prefs == null) return;
        if (!prefs.soundEnabled()) return;

        release();

        String uriStr = prefs.soundUri();
        if (uriStr != null && !uriStr.isEmpty()) {
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(c, Uri.parse(uriStr));
                mp.setVolume(prefs.soundVolume(), prefs.soundVolume());
                mp.setOnCompletionListener(m -> release());
                mp.setOnErrorListener((m, w, e) -> {
                    release();
                    return true;
                });
                mp.prepareAsync();
                mp.setOnPreparedListener(m -> {
                    try {
                        m.start();
                    } catch (Exception ignored) {
                    }
                });
                current = mp;
                // 兜底：8 秒后无论如何释放，避免长期占用
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(SoundPlayer::release, 8000);
                return;
            } catch (Exception ignored) {
                // 自定义音频不可用，退回系统提示音
            }
        }

        // 系统默认通知音
        try {
            Uri def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (def == null) def = Settings.System.DEFAULT_NOTIFICATION_URI;
            Ringtone r = RingtoneManager.getRingtone(c, def);
            if (r != null) r.play();
        } catch (Exception ignored) {
        }
    }

    public static void release() {
        try {
            MediaPlayer mp = current;
            current = null;
            if (mp != null) {
                if (mp.isPlaying()) mp.stop();
                mp.release();
            }
        } catch (Exception ignored) {
        }
    }
}
