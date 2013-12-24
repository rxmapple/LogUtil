package org.syxc.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.TreeSet;

/**
 * UncaughtException处理类，当程序发生UncaughtException的时候，由该类来接管程序，并记录、发送错误报告
 * <pre>
 * 使用方式:
 * CrashHandler crashHandler = CrashHandler.getInstance();
 * crashHandler.init(getApplicationContext());
 * crashHandler.sendPreviousReportsToServer(); // 发送以前没发送的报告(可选)
 * </pre>
 */
public class CrashHandler implements UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    private static final String EOL = "\n";

    // 是否开启日志输出，在Debug状态下开启，在Release状态下关闭以提示程序性能
    private static final boolean DEBUG = true;

    private Thread.UncaughtExceptionHandler mDefaultHandler;

    private static CrashHandler instance;

    private Context mContext;

    // 使用Properties来保存设备的信息和错误堆栈信息
    private Properties mDeviceCrashInfo = new Properties();
    private static final String VERSION_NAME = "versionName";
    private static final String VERSION_CODE = "versionCode";
    private static final String STACK_TRACE = "STACK_TRACE";

    // 错误报告文件的扩展名
    private static final String CRASH_REPORTER_EXTENSION = ".trace";

    private static final String MSG_SUBJECT_TAG = "Exception Report"; // "app title + this tag" = email subject
    private static final String MSG_SENDTO = "gaibing2009@foxmail.com"; // email will be sent to this account
    private static final String MSG_BODY = "Please help by sending this email. " +
            "No personal information is being sent (you can check by reading the rest of the email).";

    private CrashHandler() {
    }

    public static CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    /**
     * 初始化，注册Context对象，
     * 获取系统默认的UncaughtException处理器，
     * 设置该CrashHandler为程序的默认处理器
     *
     * @param context
     */
    public void init(Context context) {
        mContext = context;
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        if (!handleException(ex) && mDefaultHandler != null) {
            // 如果用户没有处理则让系统默认的异常处理器来处理
            mDefaultHandler.uncaughtException(thread, ex);
        } else {
            // Sleep一会后结束程序
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Logger.e(TAG, "Error: ", e);
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    /**
     * 自定义错误处理，收集错误信息，发送错误报告等操作均在此完成，
     * 开发者可以根据自己的情况来自定义异常处理逻辑
     *
     * @param ex
     * @return true:如果处理了该异常信息;否则返回false
     */
    private boolean handleException(Throwable ex) {
        if (ex == null) {
            return false;
        }

        final String msg = ex.getLocalizedMessage();

        // 使用Toast来显示异常信息
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                Toast.makeText(mContext, "程序出错啦：" + msg, Toast.LENGTH_LONG).show();
                Looper.loop();
            }
        }.start();

        // 收集设备信息
        collectCrashDeviceInfo(mContext);

        // 保存错误报告文件
        String crashFileName = saveCrashInfoToFile(ex);

        // 发送错误报告到服务器
        sendCrashReportsToServer(mContext);

        return true;
    }

    /**
     * 在程序启动时候, 可以调用该函数来发送以前没有发送的报告
     */
    public void sendPreviousReportsToServer() {
        sendCrashReportsToServer(mContext);
    }

    /**
     * 把错误报告发送给服务器,包含新产生的和以前没发送的.
     *
     * @param ctx
     */
    private void sendCrashReportsToServer(Context ctx) {
        String[] crFiles = getCrashReportFiles(ctx);
        if (crFiles != null && crFiles.length > 0) {
            TreeSet<String> sortedFiles = new TreeSet<String>();
            sortedFiles.addAll(Arrays.asList(crFiles));

            for (String fileName : sortedFiles) {
                File cr = new File(ctx.getFilesDir(), fileName);
                postReport(cr);
                cr.delete(); // 删除已发送的报告
            }
        }
    }

    private void postReport(File file) {
        sendDebugReport(mContext, file);
    }

    private void sendDebugReport(final Context context, final File file) {
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);

        String subject = "crash_report_mail_subject";
        String body = "crash_report_mail_body" + EOL + EOL;

        ArrayList<Uri> uris = new ArrayList<Uri>();

        Uri uri = Uri.parse(file.getAbsolutePath());

        uris.add(uri);

        intent.setType("plain/text");
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{MSG_SENDTO});
        intent.putExtra(Intent.EXTRA_TEXT, body);
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);

        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);

        context.startActivity(Intent.createChooser(intent, "Send Email Via:"));
    }


    /**
     * 获取错误报告文件名
     *
     * @param ctx
     * @return
     */
    private String[] getCrashReportFiles(Context ctx) {
        final File filesDir = new File(Logger.LOG_DIR); // 获取日志文件存放路径
        final FilenameFilter filter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
                return filename.endsWith(CRASH_REPORTER_EXTENSION);
            }
        };
        return filesDir.list(filter);
    }

    /**
     * 保存错误信息到文件中
     *
     * @param ex
     * @return
     */
    private String saveCrashInfoToFile(Throwable ex) {
        Writer info = new StringWriter();
        PrintWriter printWriter = new PrintWriter(info);
        ex.printStackTrace(printWriter);

        Throwable cause = ex.getCause();

        while (cause != null) {
            cause.printStackTrace(printWriter);
            cause = cause.getCause();
        }

        String result = info.toString();
        printWriter.close();
        mDeviceCrashInfo.put(STACK_TRACE, result);

        String fileName = "";

        try {
            long timestamp = System.currentTimeMillis();

            fileName = "crash-" + timestamp + CRASH_REPORTER_EXTENSION;
            FileOutputStream trace = mContext.openFileOutput(fileName, Context.MODE_PRIVATE);
            mDeviceCrashInfo.store(trace, "");
            trace.flush();
            trace.close();

            fileName = URLEncoder.encode(fileName, "UTF-8");

            return fileName;
        } catch (Exception e) {
            Logger.e(TAG, "An error occured while writing report file..." + fileName, e);
        }

        return null;
    }

    /**
     * 收集程序崩溃的设备信息
     *
     * @param ctx
     */
    private void collectCrashDeviceInfo(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_ACTIVITIES);
            if (pi != null) {
                mDeviceCrashInfo.put(VERSION_NAME, pi.versionName == null ? "not set" : pi.versionName);
                mDeviceCrashInfo.put(VERSION_CODE, pi.versionCode);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Logger.e(TAG, "Error while collect package info", e);
        }

        // 收集设备信息
        Field[] fields = Build.class.getDeclaredFields();

        for (Field field : fields) {
            try {
                field.setAccessible(true);
                mDeviceCrashInfo.put(field.getName(), field.get(null));
                if (DEBUG) {
                    Logger.d(TAG, field.getName() + " : " + field.get(null));
                }
            } catch (Exception e) {
                Logger.e(TAG, "Error while collect crash info", e);
            }
        }

    }

}
