/*
 * Enginehost integration for LOVE for Android.
 * Copyright (c) 2026 the Enginehost contributors.
 *
 * This software is provided 'as-is', without any express or implied
 * warranty. In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */

package org.love2d.android;

import android.app.Activity;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.annotation.Keep;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.SDLSurface;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import dev.enginehost.api.EngineControllerEvent;
import dev.enginehost.api.EngineHost;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;

/**
 * Runs a LÖVE game Enginehost hands over, on the plugin-api transport
 * (docs/engine-sandbox.md "Single transport": every official plugin is
 * Enginehost's own host Activity owning the window, the engine a library
 * driven through this class). Replaces EngineHostGameActivity, the
 * android-activity-transport wrapper this plugin shipped with before.
 *
 * LÖVE for Android is built on SDL2, whose own Java glue
 * (org.libsdl.app.SDLActivity/SDLSurface, on this plugin's line branch)
 * assumed it always was the Activity. It is not, here: this class drives
 * SDL's static lifecycle (SDL.setupJNI()/handleNativeState()) itself and
 * attaches a real SDLSurface into Enginehost's own session.display(),
 * exactly the shape CatSystem2's ScreenView already has. The handful of
 * SDLActivity/SDLSurface call sites that genuinely needed a live, attached
 * Activity (window flags, orientation, minimize, openURL, multi-window) are
 * patched on this plugin's own line branch to go through
 * SDLActivity.sHostActivity -- EngineHost.activity() -- instead of
 * requiring mSingleton itself to be one (docs/engine-sandbox.md
 * "Correction... SDLSurface.java" has the full account of why that
 * distinction exists and what it cost to find).
 *
 * Not yet isolatable. This targets the non-isolated launch only, to prove
 * the transport migration itself before attempting the Surface-handoff
 * mechanism's own still-open platform question
 * (docs/engine-sandbox.md "Surface handoff feasibility") for a GPU-rendering
 * engine specifically. EngineHost.activity() is required non-null; a host
 * too old to have it, or a future isolated launch (which never gets an
 * Activity, by design), fails onCreate cleanly rather than half-booting.
 *
 * Saves: unchanged from EngineHostGameActivity -- LÖVE saves under the
 * system's application-data folder (%APPDATA%/LOVE/&lt;identity&gt; for a
 * game love.exe runs, %APPDATA%/&lt;identity&gt; for a fused one); the
 * engine's Android branch reads ENGINEHOST_LOVE_SAVE_PARENT for that
 * (Filesystem::setIdentity, this plugin's own upstream patch).
 *
 * Controller: previously LÖVE read the pad itself via SDL's own joystick
 * enumeration ("bypass"), since as an Activity it saw raw Android input
 * before Enginehost's own mapping ever ran. Under the plugin-api transport
 * Enginehost's RuntimeActivity owns all input and always normalizes it
 * first (RuntimeControllerRouter), so there is no raw joystick path left to
 * bypass into -- every pad action arrives here already resolved to LÖVE's
 * own love.gamepad button/axis name (engine-bundle-format.md's documented
 * love_a/love_leftx table), which this class turns into the exact SDL
 * button/axis call LÖVE's own game code already expects. This is a
 * simplification, not a lost feature: one mapping UI for every engine,
 * matching every other plugin-api plugin, instead of a second bypass-vs-map
 * concept specific to this one.
 */
@Keep
public final class EngineHostGamePlugin implements EnginePlugin {
    private static final String TAG = "LOVE[Enginehost]";

    /** getLibraries()'s own former contract (GameActivity), loaded directly since nothing calls SDLActivity.loadLibraries() under this transport. */
    private static final String[] LIBRARIES = {"c++_shared", "mpg123", "openal", "love"};

    /** LÖVE's own love.gamepad button names, as the SDL button code each reports (engine-bundle-format.md's documented table). */
    private static final Map<String, Integer> BUTTONS = new HashMap<>();
    /** LÖVE's own love.gamepad axis names, as the SDL/Android axis each reports on. */
    private static final Map<String, Integer> AXES = new HashMap<>();

    static {
        BUTTONS.put("love_a", KeyEvent.KEYCODE_BUTTON_A);
        BUTTONS.put("love_b", KeyEvent.KEYCODE_BUTTON_B);
        BUTTONS.put("love_x", KeyEvent.KEYCODE_BUTTON_X);
        BUTTONS.put("love_y", KeyEvent.KEYCODE_BUTTON_Y);
        BUTTONS.put("love_back", KeyEvent.KEYCODE_BUTTON_SELECT);
        BUTTONS.put("love_guide", KeyEvent.KEYCODE_BUTTON_MODE);
        BUTTONS.put("love_start", KeyEvent.KEYCODE_BUTTON_START);
        BUTTONS.put("love_leftstick", KeyEvent.KEYCODE_BUTTON_THUMBL);
        BUTTONS.put("love_rightstick", KeyEvent.KEYCODE_BUTTON_THUMBR);
        BUTTONS.put("love_leftshoulder", KeyEvent.KEYCODE_BUTTON_L1);
        BUTTONS.put("love_rightshoulder", KeyEvent.KEYCODE_BUTTON_R1);
        BUTTONS.put("love_dpup", KeyEvent.KEYCODE_DPAD_UP);
        BUTTONS.put("love_dpdown", KeyEvent.KEYCODE_DPAD_DOWN);
        BUTTONS.put("love_dpleft", KeyEvent.KEYCODE_DPAD_LEFT);
        BUTTONS.put("love_dpright", KeyEvent.KEYCODE_DPAD_RIGHT);
        AXES.put("love_leftx", MotionEvent.AXIS_X);
        AXES.put("love_lefty", MotionEvent.AXIS_Y);
        AXES.put("love_rightx", MotionEvent.AXIS_Z);
        AXES.put("love_righty", MotionEvent.AXIS_RZ);
        AXES.put("love_triggerleft", MotionEvent.AXIS_LTRIGGER);
        AXES.put("love_triggerright", MotionEvent.AXIS_RTRIGGER);
    }

    private EngineHost host;
    private final Map<String, Boolean> held = new HashMap<>();

    @Override
    public void onCreate(EnginePluginSession session) throws Exception {
        host = session.host();
        Activity activity = host.activity();
        if (activity == null) {
            throw new IllegalStateException(
                "LOVE needs a real host Activity for now (docs/engine-sandbox.md "
                    + "\"Surface handoff feasibility\"); EngineHost.activity() was null");
        }

        File folder = new File(session.gamePath());
        if (!folder.isDirectory()) {
            throw new IllegalStateException("Enginehost did not provide a valid game folder");
        }
        String exec = session.execFile();
        File game = (exec == null || exec.isEmpty()) ? folder : new File(folder, exec);
        if (!game.exists()) {
            throw new IllegalStateException("The game file " + exec + " is not in " + folder.getPath());
        }
        boolean fused = game.isFile() && game.getName().toLowerCase(Locale.ROOT).endsWith(".exe");

        File save = host.saveDirectory();
        if (save != null) {
            try {
                Os.setenv("ENGINEHOST_LOVE_SAVE_PARENT", fused ? save.getPath() : save.getPath() + "/LOVE", true);
            } catch (ErrnoException error) {
                throw new IllegalStateException("Could not hand the save folder to LOVE: " + error.getMessage());
            }
        }
        // PhysFS mounts a folder by its path with a trailing separator and an
        // archive (a .love, or the zip a fused exe ends in) by its file path.
        final String gameFile = game.isDirectory() ? game.getPath() + "/" : game.getPath();

        for (String library : LIBRARIES) {
            SDL.loadLibrary(library);
        }

        SDL.initialize(); // resets SDLActivity/SDLAudioManager/SDLControllerManager static state; must run before the hooks below
        SDLActivity.mSingleton = new GameActivity(); // never Activity-attached (see class doc); a plain GameActivity,
        // not bare SDLActivity: dq-actsandbox-01 (BlueStacks) found LOVE's own native/Java glue does
        // "(GameActivity) SDLActivity.mSingleton", which throws ClassCastException on a bare
        // SDLActivity instance -- confirmed from the rig's own logcat, not guessed.
        SDLActivity.sHostActivity = activity;
        SDLActivity.sArguments = () -> new String[] {gameFile};
        SDLActivity.sMainSharedObject = () -> "liblove.so"; // GameActivity.getMainSharedObject()'s own API21+ answer; this plugin's minSdk is 26
        SDLActivity.sOnGameEnded = () -> host.finish();
        SDLActivity.mHasFocus = true; // no onWindowFocusChanged under this transport; Enginehost's own host Activity owns real focus
        SDLActivity.mIsResumedCalled = true; // matches resumeNativeThread(); real pause/resume still toggles this from onPause/onResume below
        SDL.setContext(activity);
        SDL.setupJNI();

        // GameActivity.getGamePath() -- not SDLActivity.sArguments -- is the
        // real JNI-called entry point love-android's own native side uses for
        // the game path (dq-actsandbox-02, BlueStacks: leaving this unset hit
        // getGamePath()'s own checkLovegameFolder() fallback, which calls
        // getExternalFilesDir() on this unattached instance and NPEs).
        ((GameActivity) SDLActivity.mSingleton).setEnginehostGame(gameFile);

        SDLSurface surface = new SDLSurface(activity);
        SDLActivity.mSurface = surface;
        session.display().addView(surface, new FrameLayout.LayoutParams(-1, -1));

        Log.i(TAG, "game: " + gameFile);
    }

    @Override
    public void onResume() {
        SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
        SDLActivity.mIsResumedCalled = true;
        SDLActivity.handleNativeState();
    }

    @Override
    public void onPause() {
        SDLActivity.mNextNativeState = SDLActivity.NativeState.PAUSED;
        SDLActivity.mIsResumedCalled = false;
        SDLActivity.handleNativeState();
    }

    @Override
    public void onDestroy() throws Exception {
        // SDLActivity.appQuitFinish()'s own sequence (private, so replicated
        // here rather than called): mark the quit as ours, ask the SDL
        // thread to stop, wait for it, then let native code tear itself down.
        SDLActivity.mExitCalledFromJava = true;
        if (SDLActivity.mSDLThread != null) {
            SDLActivity.nativeSendQuit();
            try {
                SDLActivity.mSDLThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        SDLActivity.nativeQuit();
    }

    @Override
    public boolean onControllerEvent(EngineControllerEvent event) {
        String action = event.action();
        Integer key = BUTTONS.get(action);
        if (key != null) {
            setDigital(action, event.pressed(), key);
            return true;
        }
        Integer axis = AXES.get(action);
        if (axis != null) {
            reportAxis(axis, event.value(), event.deviceId());
            return true;
        }
        return false;
    }

    /** Reports a LÖVE button once per change, so two controls bound to it do not stutter it. */
    private void setDigital(String action, boolean down, int keyCode) {
        Boolean was = held.get(action);
        if (was != null && was == down) return;
        held.put(action, down);
        if (down) {
            SDLActivity.onNativeKeyDown(keyCode);
        } else {
            SDLActivity.onNativeKeyUp(keyCode);
        }
    }

    /** Reports one LÖVE axis as a joystick MotionEvent in the layout SDL already reads for this device -- the same mechanism EngineHostGameActivity used. */
    private void reportAxis(int axis, float value, int deviceId) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].setAxisValue(axis, value);
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent remapped = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, coords,
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_JOYSTICK, 0);
        try {
            SDLControllerManager.handleJoystickMotionEvent(remapped);
        } finally {
            remapped.recycle();
        }
    }
}
