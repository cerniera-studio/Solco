package com.fergusonconsultancy.solco;

import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * The live wallpaper. Android hands each screen a drawing surface; a GLSurfaceView is pointed at
 * it (the same host Fusion Dial runs on the Fold) and a SolcoRenderer draws into it on request.
 *
 * Forces it listens for, only while visible: the fold (which screen arrives), the hinge's speed,
 * a finger, and the minute (for the sun). Hidden, it listens to nothing and draws nothing.
 */
public class SolcoWallpaperService extends WallpaperService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Tronco's record: one sample a minute, only while the service lives. Asleep, nothing runs. */
    private final Runnable growth = new Runnable() {
        @Override
        public void run() {
            Growth.tick(SolcoWallpaperService.this);
            handler.postDelayed(this, 60_000L);
        }
    };

    /**
     * Delta's record: the plug going in and coming out, heard for as long as Solco is set (the screen
     * is usually dark when it happens). Two broadcasts a charge; nothing in between.
     */
    private final BroadcastReceiver plug = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            kotlin.Pair<Float, Boolean> p = Power.INSTANCE.read(c);
            boolean charging = Intent.ACTION_POWER_CONNECTED.equals(i.getAction()) || p.getSecond();
            if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) charging = false;
            Charges.reconcile(c, p.getFirst(), charging);
        }
    };

    /** Polvere: when the wallpaper was last hidden, so its return knows how long the phone lay still. */
    static long hiddenAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        Growth.start(this);
        handler.postDelayed(growth, 60_000L);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(plug, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(plug, f);
        kotlin.Pair<Float, Boolean> p = Power.INSTANCE.read(this);
        Charges.reconcile(this, p.getFirst(), p.getSecond());
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(growth);
        try {
            unregisterReceiver(plug);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public Engine onCreateEngine() {
        return new SolcoEngine();
    }

    private class SolcoEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {

        /** A GLSurfaceView that draws into the wallpaper's surface instead of its own. */
        private class GLView extends GLSurfaceView {
            GLView(Context context) {
                super(context);
            }

            @Override
            public SurfaceHolder getHolder() {
                return getSurfaceHolder();
            }

            void destroy() {
                super.onDetachedFromWindow();
            }
        }

        private GLView view;
        private SolcoRenderer renderer;
        private boolean visible;
        private boolean tall = true;
        private SensorManager sensors;
        private boolean listening;
        private int rotation;

        private Sensor gravity;
        private boolean tilting;
        private float lastGx = 99f, lastGy = 99f;

        /** Marea: the battery's level and charging, as they change. */
        private final BroadcastReceiver battery = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (view == null) return;
                kotlin.Pair<Float, Boolean> p = Power.INSTANCE.from(i);
                final float level = p.getFirst();
                final boolean charging = p.getSecond();
                Charges.reconcile(c, level, charging);
                view.queueEvent(() -> renderer.power(level, charging));
                view.requestRender();
            }
        };

        /** Duna reads the tilt only while it is on screen; nothing else does. */
        private void tiltSensor(boolean on) {
            on = on && sensors != null && Prefs.INSTANCE.style(SolcoWallpaperService.this) == Style.DUNA;
            if (on == tilting) return;
            tilting = on;
            if (on) {
                gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (gravity == null) gravity = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (gravity != null) sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME);
            } else if (gravity != null) {
                sensors.unregisterListener(this, gravity);
                lastGx = 99f; lastGy = 99f;
            }
        }

        /** Pioggia: asks for the weather while it is on screen, at most every 20 minutes (see [Weather]). */
        private void weather() {
            Context c = SolcoWallpaperService.this;
            if (view == null || !visible || Prefs.INSTANCE.style(c) != Style.PIOGGIA || !Weather.INSTANCE.due(c)) return;
            Weather.INSTANCE.fetch(c, changed -> {
                GLView v = view;
                if (v != null) { v.queueEvent(() -> renderer.refresh()); v.requestRender(); }
                return kotlin.Unit.INSTANCE;
            });
        }

        /**
         * Deserto: the phone locking and unlocking. The clock's grain is already laid, so a change
         * only needs a frame. Heard for as long as the engine lives, since the screen goes dark first.
         */
        private final BroadcastReceiver lock = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                GLView v = view;
                if (v == null) return;
                final boolean locked = !Intent.ACTION_USER_PRESENT.equals(i.getAction()) && keyguardLocked();
                v.queueEvent(() -> renderer.locked(locked));
                v.requestRender();
            }
        };

        private boolean keyguardLocked() {
            android.app.KeyguardManager k = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return k != null && k.isKeyguardLocked();
        }

        private final BroadcastReceiver clock = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (view == null) return;
                view.queueEvent(() -> renderer.refresh());
                view.requestRender();
                weather();
            }
        };

        /** Sveglia's dawn: its see-through alarm screen says how far the dawn has come, about once a second. */
        private final BroadcastReceiver dawn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                DawnGlow.INSTANCE.set(i.getFloatExtra(DawnGlow.EXTRA_P, -1f));
                if (view == null) return;
                view.requestRender();
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(true);
            Context c = SolcoWallpaperService.this;
            renderer = new SolcoRenderer(c, () -> {
                GLView v = view;
                if (v != null) v.requestRender();
            });
            view = new GLView(c);
            view.setEGLContextClientVersion(2);
            view.setPreserveEGLContextOnPause(true);
            view.setRenderer(renderer);
            view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            Prefs.of(c).registerOnSharedPreferenceChangeListener(this);
            renderer.setPreview(isPreview());
            renderer.locked(keyguardLocked());
            IntentFilter lf = new IntentFilter();
            lf.addAction(Intent.ACTION_SCREEN_OFF);
            lf.addAction(Intent.ACTION_SCREEN_ON);
            lf.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(lock, lf, Context.RECEIVER_NOT_EXPORTED);
            else c.registerReceiver(lock, lf);
            sensors = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            // The hinge (the Kit's) is heard for as long as the wallpaper is set: the screen goes dark
            // before the hinge finishes moving, so a visible-only listener never had a fold's readings
            Diag.hinge(c, FoldWatch.listen(c));
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // Before the renderer hears of the new size, so a turned screen is built the right way round
            rotation = rotation();
            if (renderer != null) renderer.setRotation(rotation);
            super.onSurfaceChanged(holder, format, width, height);
            // One UI hands over a zero-size surface mid-fold: neither screen, so not an arrival
            if (width <= 0 || height <= 0) return;
            // Cover or main from the shape, either way round: turning the cover is not a fold
            tall = FoldWatch.tall(width, height);
            Diag.seen(SolcoWallpaperService.this, tall, width, height, displayId());
            if (visible) arrive();
        }

        /** The display's rotation (Surface.ROTATION_*), for which way a turned screen faces. */
        private int rotation() {
            try {
                Context d = getDisplayContext();
                return d != null && d.getDisplay() != null ? d.getDisplay().getRotation() : 0;
            } catch (Throwable t) {
                return 0;
            }
        }

        private int displayId() {
            try {
                Context d = getDisplayContext();
                return d != null && d.getDisplay() != null ? d.getDisplay().getDisplayId() : -1;
            } catch (Throwable t) {
                return -1;
            }
        }

        /** A screen arrived. If it is not the one that was last seen, the phone folded or opened. */
        private void arrive() {
            if (view == null) return;
            final int dir = FoldWatch.arrive(tall);
            if (dir != 0) {
                final boolean fast = FoldWatch.fast(SolcoWallpaperService.this);
                view.queueEvent(() -> renderer.stir(dir, fast));
            }
            view.requestRender();
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            super.onVisibilityChanged(v);
            visible = v;
            if (view == null) return;
            if (v) {
                view.onResume();
                // Polvere: how long the phone lay untouched, while the wallpaper was out of sight
                final long rest = hiddenAt > 0L ? android.os.SystemClock.elapsedRealtime() - hiddenAt : 0L;
                if (rest > 0L) view.queueEvent(() -> renderer.rest(rest));
                listen(true);
                final boolean locked = keyguardLocked();
                view.queueEvent(() -> { renderer.locked(locked); renderer.refresh(); });
                arrive();
                weather();
            } else {
                hiddenAt = android.os.SystemClock.elapsedRealtime();
                // Sentiero's trail, written once on the way out of sight rather than on every touch
                Trail.INSTANCE.save(SolcoWallpaperService.this);
                listen(false);
                view.onPause();
            }
        }

        private void listen(boolean on) {
            if (on == listening) return;
            listening = on;
            Context c = SolcoWallpaperService.this;
            if (on) {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_TIME_TICK);
                f.addAction(Intent.ACTION_TIME_CHANGED);
                f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
                if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(clock, f, Context.RECEIVER_NOT_EXPORTED);
                else c.registerReceiver(clock, f);
                if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
                else c.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                // Sveglia is another app, so this one is exported; it only carries the dawn's progress
                if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(dawn, new IntentFilter(DawnGlow.ACTION), Context.RECEIVER_EXPORTED);
                else c.registerReceiver(dawn, new IntentFilter(DawnGlow.ACTION));
                tiltSensor(true);
            } else {
                try {
                    c.unregisterReceiver(clock);
                } catch (IllegalArgumentException ignored) {
                }
                try {
                    c.unregisterReceiver(battery);
                } catch (IllegalArgumentException ignored) {
                }
                try {
                    c.unregisterReceiver(dawn);
                } catch (IllegalArgumentException ignored) {
                }
                tiltSensor(false);
            }
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            int type = e.sensor.getType();
            if (type == Sensor.TYPE_GRAVITY || type == Sensor.TYPE_ACCELEROMETER) {
                if (view == null || e.values.length < 2) return;
                // Gravity across the screen: device x to the right, device y up, so screen y is flipped;
                // turned, the screen's axes are the device's turned with it
                final float ax = e.values[0], ay = e.values[1];
                final float gx, gy;
                switch (rotation) {
                    case 1: gx = ay; gy = -ax; break;
                    case 2: gx = ax; gy = -ay; break;
                    case 3: gx = -ay; gy = ax; break;
                    default: gx = -ax; gy = ay;
                }
                // Fine steps (under a degree), which the grain then eases between, so the slide never lurches
                if (Math.abs(gx - lastGx) + Math.abs(gy - lastGy) < 0.15f) return;
                lastGx = gx; lastGy = gy;
                view.queueEvent(() -> renderer.tilt(gx, gy));
                view.requestRender();
                return;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {
        }

        @Override
        public void onTouchEvent(MotionEvent e) {
            if (view != null) {
                final float x = e.getX(), y = e.getY();
                final int a = e.getActionMasked();
                final boolean down = a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE;
                view.queueEvent(() -> renderer.touch(x, y, down));
                view.requestRender();
            }
            super.onTouchEvent(e);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            if (!Prefs.isLook(key) || view == null) return;
            if (visible) { tiltSensor(false); tiltSensor(true); }
            weather();
            view.queueEvent(() -> renderer.rebuild());
            view.requestRender();
            notifyColorsChanged();
        }

        @Override
        public WallpaperColors onComputeColors() {
            return Look.colors(SolcoWallpaperService.this);
        }

        @Override
        public void onDestroy() {
            Prefs.of(SolcoWallpaperService.this).unregisterOnSharedPreferenceChangeListener(this);
            Trail.INSTANCE.save(SolcoWallpaperService.this);
            try { SolcoWallpaperService.this.unregisterReceiver(lock); } catch (IllegalArgumentException ignored) { }
            listen(false);
            if (sensors != null) sensors.unregisterListener(this);
            FoldWatch.unlisten();
            if (view != null) {
                view.destroy();
                view = null;
            }
            super.onDestroy();
        }
    }
}
