import android.content.Context;
import android.os.Looper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Starts (or stops) Android tethering for a given type, run via:
 *   CLASSPATH=/data/local/tmp/tether.dex app_process /system/bin Main [type] [start|stop]
 * Default: type 5 (TETHERING_ETHERNET), action start.
 * Requires shell-uid privileges (TETHER_PRIVILEGED) — i.e. run via adb shell.
 */
public class Main {
    // TetheringManager constants
    static final int TETHERING_ETHERNET = 5;

    public static void main(String[] args) throws Exception {
        boolean info = args.length > 0 && args[0].equals("info");
        int type = (args.length > 0 && !info) ? Integer.parseInt(args[0]) : TETHERING_ETHERNET;
        boolean stop = args.length > 1 && args[1].equalsIgnoreCase("stop");

        Looper.prepareMainLooper();

        // Build a system Context without an app — the app_process trick.
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);

        // Present as com.android.shell (matches our uid 2000) so the service accepts the caller.
        Context appCtx;
        try { appCtx = ctx.createPackageContext("com.android.shell", 0); }
        catch (Exception e) { appCtx = ctx; System.out.println("WARN: createPackageContext failed: " + e); }

        // createPackageContext keeps the original op-package ("android"); getOpPackageName() reads it
        // from mAttributionSource. Replace both so TetheringService's "package must match UID" check passes.
        try {
            java.lang.reflect.Field f = appCtx.getClass().getDeclaredField("mOpPackageName");
            f.setAccessible(true);
            f.set(appCtx, "com.android.shell");
        } catch (Exception e) { System.out.println("WARN: could not set mOpPackageName: " + e); }
        try {
            android.content.AttributionSource src = new android.content.AttributionSource.Builder(
                    android.os.Process.myUid()).setPackageName("com.android.shell").build();
            java.lang.reflect.Field af = appCtx.getClass().getDeclaredField("mAttributionSource");
            af.setAccessible(true);
            af.set(appCtx, src);
        } catch (Exception e) { System.out.println("WARN: could not set mAttributionSource: " + e); }

        Object tm = appCtx.getSystemService("tethering"); // TetheringManager
        if (tm == null) { System.out.println("RESULT: no TetheringManager"); return; }
        Class<?> tmClass = tm.getClass();

        if (info) {
            System.out.println("myUid=" + android.os.Process.myUid());
            System.out.println("base ctx pkg=" + ctx.getPackageName() + " op=" + ctx.getOpPackageName());
            System.out.println("app  ctx pkg=" + appCtx.getPackageName() + " op=" + appCtx.getOpPackageName());
            String[] perms = {"android.permission.TETHER_PRIVILEGED", "android.permission.NETWORK_SETTINGS",
                "android.permission.WRITE_SETTINGS", "android.permission.NETWORK_STACK"};
            for (String p : perms) {
                System.out.println("  perm " + p + " => " + appCtx.checkSelfPermission(p) + " (0=GRANTED,-1=DENIED)");
            }
            for (Class<?> c = appCtx.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    String fn = f.getName().toLowerCase();
                    if (fn.contains("package") || fn.contains("attribution") || fn.contains("opfeature")) {
                        try { f.setAccessible(true); System.out.println("  FIELD " + c.getSimpleName() + "." + f.getName() + " = " + f.get(appCtx)); }
                        catch (Exception e) {}
                    }
                }
            }
            System.out.println("TM class: " + tmClass.getName());
            for (java.lang.reflect.Field f : tmClass.getFields()) {
                if (f.getName().startsWith("TETHER_ERROR")) {
                    try { System.out.println("  " + f.getName() + " = " + f.get(null)); } catch (Exception e) {}
                }
            }
            for (Method m : tmClass.getMethods()) {
                if (m.getName().toLowerCase().contains("tether")) {
                    StringBuilder sb = new StringBuilder("  " + m.getName() + "(");
                    for (Class<?> p : m.getParameterTypes()) sb.append(p.getName()).append(", ");
                    sb.append(")");
                    System.out.println(sb);
                }
            }
            return;
        }

        if (stop) {
            tmClass.getMethod("stopTethering", int.class).invoke(tm, type);
            System.out.println("RESULT: stopTethering(" + type + ") invoked");
            Thread.sleep(1500);
            return;
        }

        // StartTetheringCallback proxy
        Class<?> cbClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] outcome = {"no-callback"};
        InvocationHandler h = new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("onTetheringStarted")) { outcome[0] = "STARTED"; latch.countDown(); }
                else if (n.equals("onTetheringFailed")) {
                    outcome[0] = "FAILED error=" + (a != null && a.length > 0 ? a[0] : "?");
                    latch.countDown();
                } else if (n.equals("toString")) return "cb";
                else if (n.equals("hashCode")) return 0;
                else if (n.equals("equals")) return proxy == (a != null ? a[0] : null);
                return null;
            }
        };
        Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class[]{cbClass}, h);

        Executor exec = new Executor() { public void execute(Runnable r) { r.run(); } };

        Method start = tmClass.getMethod("startTethering", int.class, Executor.class, cbClass);
        start.invoke(tm, type, exec, cb);
        System.out.println("RESULT: startTethering(" + type + ") invoked");

        latch.await(8, TimeUnit.SECONDS);
        System.out.println("RESULT: " + outcome[0]);
    }
}
