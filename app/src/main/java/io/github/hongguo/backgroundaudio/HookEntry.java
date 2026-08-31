package io.github.hongguo.backgroundaudio;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps Hongguo's current player alive only across a genuine app-background transition.
 * The short suppression window is intentional: lock-screen/headset controls must keep working.
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.phoenix.read";
    private static final String TAG = "HongguoBackgroundAudio";
    private static final long SUPPRESSION_MS = 4_000L;
    private static final long INTERNAL_NAVIGATION_MS = 2_000L;

    private static final Object NAVIGATION_LOCK = new Object();
    private static volatile long suppressUntil;
    private static volatile boolean resumed = true;
    private static long internalNavigationUntil;
    private static WeakReference<Activity> internalNavigationSource =
            new WeakReference<Activity>(null);
    private static WeakReference<Activity> currentActivity =
            new WeakReference<Activity>(null);
    private static final Set<Class<?>> hookedClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    private static final String[] ENGINE_CLASSES = new String[] {
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "com.google.android.exoplayer2.ExoPlayerImpl",
            "androidx.media3.exoplayer.ExoPlayerImpl"
    };

    /** Confirmed in Hongguo 7.3.5.32 (73532); the first entry is its short-video player. */
    private static final String[] WRAPPER_CLASSES = new String[] {
            "x05.w",
            "com.ss.android.videoshop.controller.VideoController"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)
                || !TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        log("loading in process " + lpparam.processName);
        installNavigationHooks();
        installLifecycleHooks();
        installPlatformPlayerHooks();
        installKnownPlayerHooks(lpparam.classLoader);
        installLateClassHook();
        log("hooks ready");
    }

    private static void installNavigationHooks() {
        XC_MethodHook navigationHook = new XC_MethodHook(XCallbackPriority.HIGHEST) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    return;
                }
                Activity source = param.thisObject instanceof Activity
                        ? (Activity) param.thisObject : null;
                Context context = source;
                for (Object arg : param.args) {
                    if (source == null && arg instanceof Activity) {
                        source = (Activity) arg;
                    }
                    if (context == null && arg instanceof Context) {
                        context = (Context) arg;
                    }
                    if (arg instanceof Intent && isInternalIntent((Intent) arg, context)) {
                        markInternalNavigation(source, (Intent) arg);
                        return;
                    }
                    if (arg instanceof Intent[]) {
                        for (Intent intent : (Intent[]) arg) {
                            if (isInternalIntent(intent, context)) {
                                markInternalNavigation(source, intent);
                                return;
                            }
                        }
                    }
                }
            }
        };

        // Activity/Fragment navigation normally reaches startActivityForResult.
        XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", navigationHook);
        // Keep a framework-level fallback for routers using Context or ActivityResult APIs.
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", navigationHook);
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivities", navigationHook);
    }

    private static void installLifecycleHooks() {
        XposedBridge.hookAllMethods(Activity.class, "performPause", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                boolean internalNavigation = consumeInternalNavigation(activity);
                boolean finishing = activity.isFinishing();
                boolean changingConfiguration = activity.isChangingConfigurations();
                clearCurrentActivity(activity);
                resumed = false;
                if (isScreenOff(activity)) {
                    armSuppression("screen off");
                } else if (internalNavigation || finishing || changingConfiguration) {
                    suppressUntil = 0L;
                    log("pause allowed: " + activity.getClass().getName()
                            + " internal=" + internalNavigation
                            + " finishing=" + finishing
                            + " config=" + changingConfiguration);
                } else {
                    // Android 15 ROMs do not consistently dispatch performUserLeaving
                    // for Home gestures, so every non-internal pause is background.
                    armSuppression("activity paused without internal launch");
                }
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "performStop", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (isSuppressionActive() || isScreenOff(activity)) {
                    armSuppression("activity stopped");
                }
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "performResume", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                resumed = true;
                suppressUntil = 0L;
                synchronized (NAVIGATION_LOCK) {
                    internalNavigationUntil = 0L;
                    internalNavigationSource = new WeakReference<Activity>(null);
                    currentActivity = new WeakReference<Activity>((Activity) param.thisObject);
                }
            }
        });
    }

    private static boolean isInternalIntent(Intent intent, Context context) {
        if (intent == null) {
            return false;
        }
        ComponentName component = intent.getComponent();
        if (component != null) {
            return TARGET_PACKAGE.equals(component.getPackageName());
        }
        if (TARGET_PACKAGE.equals(intent.getPackage())) {
            return true;
        }
        if (context != null) {
            try {
                ComponentName resolved = intent.resolveActivity(context.getPackageManager());
                return resolved != null && TARGET_PACKAGE.equals(resolved.getPackageName());
            } catch (Throwable ignored) {
                // Resolution is best-effort; explicit intents already cover Hongguo's router.
            }
        }
        return false;
    }

    private static void markInternalNavigation(Activity source, Intent intent) {
        synchronized (NAVIGATION_LOCK) {
            if (source == null) {
                source = currentActivity.get();
            }
            internalNavigationSource = new WeakReference<Activity>(source);
            internalNavigationUntil = SystemClock.uptimeMillis() + INTERNAL_NAVIGATION_MS;
        }
        ComponentName component = intent.getComponent();
        log("internal activity launch: source="
                + (source == null ? "unknown" : source.getClass().getName())
                + " target=" + (component == null ? intent.getAction() : component));
    }

    private static boolean consumeInternalNavigation(Activity activity) {
        synchronized (NAVIGATION_LOCK) {
            if (SystemClock.uptimeMillis() >= internalNavigationUntil) {
                internalNavigationUntil = 0L;
                internalNavigationSource = new WeakReference<Activity>(null);
                return false;
            }
            Activity source = internalNavigationSource.get();
            if (source != null && source != activity) {
                return false;
            }
            internalNavigationUntil = 0L;
            internalNavigationSource = new WeakReference<Activity>(null);
            return true;
        }
    }

    private static void clearCurrentActivity(Activity activity) {
        synchronized (NAVIGATION_LOCK) {
            if (currentActivity.get() == activity) {
                currentActivity = new WeakReference<Activity>(null);
            }
        }
    }

    private static void installPlatformPlayerHooks() {
        hookPlayerMethod(MediaPlayer.class, "pause");
    }

    private static void installKnownPlayerHooks(ClassLoader loader) {
        for (String className : ENGINE_CLASSES) {
            Class<?> playerClass = XposedHelpers.findClassIfExists(className, loader);
            if (playerClass != null) {
                hookEngineClass(playerClass);
            }
        }
        for (String className : WRAPPER_CLASSES) {
            Class<?> playerClass = XposedHelpers.findClassIfExists(className, loader);
            if (playerClass != null) {
                hookWrapperClass(playerClass);
            }
        }
    }

    private static void installLateClassHook() {
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.getResult() == null) {
                    return;
                }
                Class<?> loaded = (Class<?>) param.getResult();
                String name = loaded.getName();
                for (String candidate : ENGINE_CLASSES) {
                    if (candidate.equals(name)) {
                        hookEngineClass(loaded);
                        return;
                    }
                }
                for (String candidate : WRAPPER_CLASSES) {
                    if (candidate.equals(name)) {
                        hookWrapperClass(loaded);
                        return;
                    }
                }
            }
        });
    }

    private static void hookEngineClass(Class<?> playerClass) {
        if (!hookedClasses.add(playerClass)) {
            return;
        }
        hookPlayerMethod(playerClass, "pause");
        hookBooleanPlayerMethod(playerClass, "setPlayWhenReady");
        log("engine hooked: " + playerClass.getName());
    }

    private static void hookWrapperClass(Class<?> playerClass) {
        if (!hookedClasses.add(playerClass)) {
            return;
        }
        hookPlayerMethod(playerClass, "pause");
        log("wrapper hooked: " + playerClass.getName());
    }

    private static void hookPlayerMethod(Class<?> type, final String methodName) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(type, methodName,
                    new XC_MethodHook(XCallbackPriority.HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isSuppressionActive()) {
                                return;
                            }
                            Method method = (Method) param.method;
                            param.setResult(defaultValue(method.getReturnType()));
                            log("suppressed " + method.getDeclaringClass().getSimpleName()
                                    + "." + methodName);
                        }
                    });
            if (hooks.isEmpty()) {
                // Optional API: absence is normal across player versions.
                return;
            }
        } catch (Throwable error) {
            log("could not hook " + type.getName() + "." + methodName + ": " + error);
        }
    }

    private static void hookBooleanPlayerMethod(Class<?> type, final String methodName) {
        try {
            XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook(XCallbackPriority.HIGHEST) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSuppressionActive() || param.args.length == 0
                            || !(param.args[0] instanceof Boolean)
                            || ((Boolean) param.args[0])) {
                        return;
                    }
                    Method method = (Method) param.method;
                    param.setResult(defaultValue(method.getReturnType()));
                    log("suppressed " + method.getDeclaringClass().getSimpleName()
                            + "." + methodName + "(false)");
                }
            });
        } catch (Throwable error) {
            log("could not hook " + type.getName() + "." + methodName + ": " + error);
        }
    }

    private static boolean isScreenOff(Activity activity) {
        try {
            PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            return power != null && !power.isInteractive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void armSuppression(String reason) {
        suppressUntil = SystemClock.uptimeMillis() + SUPPRESSION_MS;
        log("protection armed: " + reason);
    }

    private static boolean isSuppressionActive() {
        return !resumed && SystemClock.uptimeMillis() < suppressUntil;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Character.TYPE) return '\0';
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0f;
        if (returnType == Double.TYPE) return 0d;
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    /** Avoids relying on callback priority constants that differ across bridge forks. */
    private static final class XCallbackPriority {
        private static final int HIGHEST = 10_000;
    }
}
