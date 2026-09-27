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
                final MediaPlayer mine = mp;
                // 兜底：8 秒后释放。
                // 但必须带上「身份校验」：若这 8 秒内又播了新的音效，
                // 这个延迟任务仍会执行，直接调 release() 会把正在响的新音效杀掉。
                // 所以只在 current 仍是自己时才释放。
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> releaseIf(mine), 8000);
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

    /** 只释放指定的实例；若已经换了别的音效在播，就不动它 */
    private static void releaseIf(MediaPlayer target) {
        try {
            if (current != target) return;   // 已经不是自己了，说明有新音效在播
            MediaPlayer mp = current;
            current = null;
            if (mp == null) return;
            if (mp.isPlaying()) mp.stop();
            mp.release();
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
