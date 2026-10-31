package com.supercart.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Plays the Supercart intro video, then opens the website screen. */
public class SplashActivity extends AppCompatActivity {

    // If the video cannot start or finish, open the app anyway after this long.
    private static final long SAFETY_TIMEOUT_MS = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched = false;
    private VideoView video;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Full screen: hide status and navigation bars while the intro plays.
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        video = findViewById(R.id.splash_video);
        Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.splash);
        video.setVideoURI(uri);
        video.setOnCompletionListener(mp -> openApp());
        video.setOnErrorListener((mp, what, extra) -> {
            openApp();
            return true;
        });
        video.start();

        handler.postDelayed(this::openApp, SAFETY_TIMEOUT_MS);
    }

    private void openApp() {
        if (launched) return;
        launched = true;
        handler.removeCallbacksAndMessages(null);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
