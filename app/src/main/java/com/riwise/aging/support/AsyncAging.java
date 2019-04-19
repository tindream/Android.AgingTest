package com.riwise.aging.support;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.support.v4.app.ActivityCompat;
import android.telephony.SmsManager;

import io.reactivex.ObservableEmitter;

import com.riwise.aging.enums.LoadType;
import com.riwise.aging.info.loadInfo.LoadInfo;
import com.riwise.aging.info.loadInfo.ProgressInfo;
import com.riwise.aging.info.loadInfo.TestInfo;
import com.riwise.aging.info.sqlInfo.AgingInfo;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class AsyncAging extends AsyncBase {
    private boolean iStop;

    public AsyncAging(AgingInfo info) {
        initData(new TestInfo(info));
    }

    public void stop() {
        this.iStop = true;
    }

    @Override
    protected void onLoadData(ObservableEmitter<LoadInfo> emitter, LoadInfo info) throws Exception {
        AgingInfo aging = ((TestInfo) info).info;
        String name = null;
        File testPath = new File(Config.file.getPath(), "Test");
        if (!testPath.exists()) testPath.mkdirs();
        try {
            execStart(emitter, name = "碎片化", testPath);
            copyFile(emitter, testPath, name = "图片", Config.Admin.Images, aging.Image);
            copyFile(emitter, testPath, name = "音频", Config.Admin.Audios, aging.Audio);
            copyFile(emitter, testPath, name = "视频", Config.Admin.Videos, aging.Video);
            String phoneFormat = "199%08d";
            execContact(emitter, aging, name = "联系人", phoneFormat);
            execSms(emitter, aging, name = "信息", phoneFormat);
            execCall(emitter, aging, name = "通话记录", phoneFormat);
            execApp(emitter, aging, name = "三方应用");
            execFile(emitter, aging, name = "填充文件", testPath);
            emitter.onNext(new LoadInfo(LoadType.complete));
        } catch (Exception e) {
            emitter.onNext(new ProgressInfo(name, e.getMessage(), false));
            throw e;
        } finally {
            if (iStop) emitter.onNext(new LoadInfo(LoadType.cancel));
        }
    }

    private void execStart(ObservableEmitter<LoadInfo> emitter, String name, File testPath) {
        int count = 0;
        File path = new File(testPath.getPath(), "填充文件");
        if (path.exists()) {
            File[] allFiles = path.listFiles();
            for (int i = 0; i < allFiles.length; i++) {
                File file = allFiles[i];
                String end = file.getName();
                if (end.endsWith("1") || end.endsWith("3")) {
                    file.delete();
                    count++;
                    if (count % 10 == 0) {
                        if (iStop) return;
                        emitter.onNext(new ProgressInfo(name, count + "", 100 * i / allFiles.length));
                    }
                }
            }
        }
        emitter.onNext(new ProgressInfo(name, "完成"));
    }

    private void execFile(ObservableEmitter<LoadInfo> emitter, AgingInfo aging, String name, File testPath) throws Exception {
        double last = aging.Last * 1024 * 1024 * 1024;
        File sdcard = Environment.getExternalStorageDirectory();//得到sdcard的目录作为一个文件对象
        long usable = sdcard.getUsableSpace();//获取文件目录对象剩余空间
        double space = usable - last;//填充空间
        if (space > 0) {
            int index = 0;
            File path = new File(testPath.getPath(), name);
            if (!path.exists()) path.mkdirs();
            BufferedInputStream reader4 = null, reader8 = null, reader128 = null;
            int mark4 = 0, mark8 = 0, mark128 = 0;
            if (!Method.isEmpty(Config.Admin.File4s)) {
                File file = new File(Config.Admin.File4s);
                mark4 = (int) file.length();
                reader4 = new BufferedInputStream(new FileInputStream(file));
            }
            if (!Method.isEmpty(Config.Admin.File8s)) {
                File file = new File(Config.Admin.File8s);
                mark8 = (int) file.length();
                reader8 = new BufferedInputStream(new FileInputStream(file));
            }
            if (!Method.isEmpty(Config.Admin.File128s)) {
                File file = new File(Config.Admin.File128s);
                mark128 = (int) file.length();
                reader128 = new BufferedInputStream(new FileInputStream(file));
            }
            if (reader4 == null && reader8 == null && reader128 == null) {
                emitter.onNext(new ProgressInfo(name, "未设置", false));
            } else {
                while (true) {
                    if (iStop) return;
                    if (reader4 != null)
                        index += copyFile(path.getPath(), reader4, mark4, aging.File4, index);
                    if (reader8 != null)
                        index += copyFile(path.getPath(), reader8, mark8, aging.File8, index);
                    if (reader128 != null)
                        index += copyFile(path.getPath(), reader128, mark128, aging.File128, index);

                    long usableSpace = sdcard.getUsableSpace();//获取文件目录对象剩余空间
                    double value = 100 * (usable - usableSpace) / space;
                    emitter.onNext(new ProgressInfo(name, index + "(" + String.format("%.3f", usableSpace / 1024.0 / 1024 / 1024) + "G)", (int) value));
                    if (usableSpace < last) break;
                }
                if (reader4 != null) reader4.close();
                if (reader8 != null) reader8.close();
                if (reader128 != null) reader128.close();
            }
        }
        emitter.onNext(new ProgressInfo(name, aging.File4 + ":" + aging.File8 + ":" + aging.File128));
    }

    private int copyFile(String path, BufferedInputStream reader, int mark, int count, int index) throws Exception {
        for (int i = 1; i <= count; i++) {
            reader.mark(mark);
            copyFile(reader, path, "%09d", index + i);
        }
        return count;
    }

    private void execApp(ObservableEmitter<LoadInfo> emitter, AgingInfo aging, String name) throws Exception {
        if (Method.isEmpty(Config.Admin.Apps)) {
            emitter.onNext(new ProgressInfo(name, "未设置", false));
            return;
        }
        String[] apps = Config.Admin.Apps.split(";");
        int count = apps.length;
        if (count > aging.App) count = aging.App;
        for (int i = 1; i < count; i++) {
            if (iStop) return;
            addApp(apps[i - 1]);
            emitter.onNext(new ProgressInfo(name, i + "/" + count, 100 * i / count));
        }
        emitter.onNext(new ProgressInfo(name, count + "/" + aging.App));
    }

    private boolean addApp(String apkPath) throws Exception {
        Process process = null;
        BufferedReader successResult = null;
        BufferedReader errorResult = null;
        StringBuilder successMsg = new StringBuilder();
        StringBuilder errorMsg = new StringBuilder();
        try {
            process = new ProcessBuilder("pm", "install", "-i", "com.riwise.aging", "-r", apkPath).start();
            successResult = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorResult = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String s;
            while ((s = successResult.readLine()) != null) successMsg.append(s);
            while ((s = errorResult.readLine()) != null) errorMsg.append(s);
        } catch (Exception e) {
            errorMsg.append(e.getMessage());
        } finally {
            if (successResult != null) successResult.close();
            if (errorResult != null) errorResult.close();
            if (process != null) process.destroy();
        }
        Method.hit(errorMsg.toString());
        //如果含有“success”单词则认为安装成功
        return successMsg.toString().equalsIgnoreCase("success");
    }

    private void execCall(ObservableEmitter<LoadInfo> emitter, AgingInfo aging, String name, String phoneFormat) {
        for (int i = 1; i <= aging.Call; i++) {
            if (iStop) return;
            addCall(String.format(phoneFormat, i));
            emitter.onNext(new ProgressInfo(name, i + "/" + aging.Call, 100 * i / aging.Call));
        }
        emitter.onNext(new ProgressInfo(name, aging.Call + ""));
    }

    /**
     * 插入一条通话记录
     *
     * @param number 通话号码
     *               duration 通话时长（响铃时长）以秒为单位 1分30秒则输入90
     *               type  通话类型  1呼入 2呼出 3未接
     *               isNew 是否已查看    0已看1未看
     */
    private void addCall(String number) {
        ContentValues values = new ContentValues();
        values.put(CallLog.Calls.NUMBER, number);
        values.put(CallLog.Calls.DATE, System.currentTimeMillis());
        values.put(CallLog.Calls.DURATION, 60);
        values.put(CallLog.Calls.TYPE, 2);
        values.put(CallLog.Calls.NEW, 0);
        Config.context.getContentResolver().insert(CallLog.Calls.CONTENT_URI, values);
    }

    private void execSms(ObservableEmitter<LoadInfo> emitter, AgingInfo aging, String name, String phoneFormat) {
        if (!Config.ISMS) {
            emitter.onNext(new ProgressInfo(name, "未授权", false));
            return;
        }
        String format = "测试短信%0" + (aging.Sms + "").length() + "d[Aging]";
        for (int i = 1; i <= aging.Sms; i++) {
            if (iStop) return;
            addSms(String.format(phoneFormat, i), String.format(format, i));
            emitter.onNext(new ProgressInfo(name, i + "/" + aging.Sms, 100 * i / aging.Sms));
        }
        emitter.onNext(new ProgressInfo(name, aging.Sms + ""));
    }

    private void addSms(String address, String body) {
        ContentValues values = new ContentValues();
        values.put("address", address);
        values.put("type", 1);
        values.put("date", System.currentTimeMillis());
        values.put("body", body);
        Config.context.getContentResolver().insert(Uri.parse("content://sms/"), values);
    }

    private void execContact(ObservableEmitter<LoadInfo> emitter, AgingInfo aging, String name, String phoneFormat) throws Exception {
        String format = "Aging%0" + (aging.Contact + "").length() + "d";
        for (int i = 1; i <= aging.Contact; i++) {
            if (iStop) return;
            addContact(String.format(format, i), String.format(phoneFormat, i));
            emitter.onNext(new ProgressInfo(name, i + "/" + aging.Contact, 100 * i / aging.Contact));
        }
        emitter.onNext(new ProgressInfo(name, aging.Contact + ""));
    }

    private void addContact(String userName, String phoneNumber) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)  // 此处传入null添加一个raw_contact空数据
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)  // RAW_CONTACT_ID是第一个事务添加得到的，因此这里传入0，applyBatch返回的ContentProviderResult[]数组中第一项
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, userName)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                .build());
        Config.context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
    }

    private void copyFile(ObservableEmitter<LoadInfo> emitter, File testPath, String name, String path, int count) throws Exception {
        if (Method.isEmpty(path)) {
            emitter.onNext(new ProgressInfo(name, "未设置", false));
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            emitter.onNext(new ProgressInfo(name, "文件不存在", false));
            return;
        }

        File imagePath = new File(testPath.getPath(), name);
        if (!imagePath.exists()) imagePath.mkdirs();
        BufferedInputStream reader = new BufferedInputStream(new FileInputStream(file));
        String format = "%0" + (count + "").length() + "d";
        for (int i = 1; i <= count; i++) {
            if (iStop) return;

            reader.mark((int) file.length());
            copyFile(reader, imagePath.getPath(), format, i);
            emitter.onNext(new ProgressInfo(name, i + "/" + count, 100 * i / count));
        }
        reader.close();
        emitter.onNext(new ProgressInfo(name, count + ""));
    }

    private void copyFile(BufferedInputStream reader, String path, String format, int index) throws Exception {
        File imageFile = new File(path, String.format(format, index));
        FileOutputStream output = new FileOutputStream(imageFile);
        byte[] bytes = new byte[1024];
        int read;
        while ((read = reader.read(bytes)) != -1) {
            output.write(bytes, 0, read);
        }
        output.flush();
        output.close();
        reader.reset();
    }
}
