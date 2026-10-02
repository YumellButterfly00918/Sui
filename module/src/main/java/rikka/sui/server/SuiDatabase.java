package rikka.sui.server;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.Nullable;

import java.io.File;

import rikka.sui.server.SuiConfig.PackageEntry;
import rikka.sui.util.SQLiteDataBaseRemoteCompat;

public class SuiDatabase {

    private SuiDatabase() {
    }

    static {
        DATABASE_PATH = (new File("/data/adb/sui/sui.db")).getPath();
    }

    private static final String DATABASE_PATH;
    private static final String UID_CONFIG_TABLE = "uid_configs";
    private static final String GLOBAL_CONFIG_TABLE = "global_config";
    private static final String GLOBAL_AUTO_GRANT_KEY = "global_auto_grant";
    private static SQLiteDatabase databaseInternal;

    private static SQLiteDatabase createDatabase(boolean allowRetry) {
        SQLiteDatabase database;
        try {
            database = SQLiteDataBaseRemoteCompat.openDatabase(DATABASE_PATH, null);
            database.execSQL("CREATE TABLE IF NOT EXISTS uid_configs(uid INTEGER PRIMARY KEY, flags INTEGER);");
            database.execSQL("CREATE TABLE IF NOT EXISTS global_config(key TEXT PRIMARY KEY, value INTEGER NOT NULL);");
        } catch (Throwable e) {
            ServerConstants.LOGGER.e(e, "create database");
            if (allowRetry && (new File(DATABASE_PATH)).delete()) {
                ServerConstants.LOGGER.i("delete database and retry");
                database = createDatabase(false);
            } else {
                database = null;
            }
        }

        return database;
    }

    private static SQLiteDatabase getDatabase() {
        if (databaseInternal == null) {
            databaseInternal = createDatabase(true);
        }
        return databaseInternal;
    }

    @Nullable
    public static SuiConfig readConfig() {
        SQLiteDatabase database = getDatabase();
        if (database == null) {
            return null;
        }

        try (Cursor cursor = database.query(UID_CONFIG_TABLE, (String[]) null, (String) null, (String[]) null, (String) null, (String) null, (String) null, (String) null)) {
            if (cursor == null) {
                return null;
            }
            SuiConfig res = new SuiConfig();
            int cursorIndexOfUid = cursor.getColumnIndexOrThrow("uid");
            int cursorIndexOfFlags = cursor.getColumnIndexOrThrow("flags");
            if (cursor.moveToFirst()) {
                do {
                    res.packages.add(new PackageEntry(cursor.getInt(cursorIndexOfUid), cursor.getInt(cursorIndexOfFlags)));
                } while (cursor.moveToNext());
            }
            return res;
        }
    }

    public static void updateUid(int uid, int flags) {
        SQLiteDatabase database = getDatabase();
        if (database == null) {
            throw new IllegalStateException("Unable to open Sui database for UID update");
        }

        ContentValues values = new ContentValues();
        values.put("uid", uid);
        values.put("flags", flags);

        database.beginTransaction();
        try {
            long rowId = database.insertWithOnConflict(
                    UID_CONFIG_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            if (rowId < 0) {
                throw new IllegalStateException("Unable to persist flags for UID " + uid);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public static void removeUid(int uid) {
        SQLiteDatabase database = getDatabase();
        if (database == null) {
            return;
        }

        String selection = "uid=?";
        String[] selectionArgs = new String[]{String.valueOf(uid)};
        database.delete(UID_CONFIG_TABLE, selection, selectionArgs);
    }

    public static boolean readGlobalAutoGrant() {
        SQLiteDatabase database = getDatabase();
        if (database == null) {
            return false;
        }

        try (Cursor cursor = database.query(GLOBAL_CONFIG_TABLE, new String[]{"value"}, "key=?",
                new String[]{GLOBAL_AUTO_GRANT_KEY}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) != 0;
        } catch (Throwable e) {
            ServerConstants.LOGGER.e(e, "read global auto grant");
            return false;
        }
    }

    public static boolean writeGlobalAutoGrant(boolean enabled) {
        SQLiteDatabase database = getDatabase();
        if (database == null) {
            return false;
        }

        ContentValues values = new ContentValues();
        values.put("key", GLOBAL_AUTO_GRANT_KEY);
        values.put("value", enabled ? 1 : 0);
        try {
            if (database.update(GLOBAL_CONFIG_TABLE, values, "key=?", new String[]{GLOBAL_AUTO_GRANT_KEY}) <= 0) {
                database.insertWithOnConflict(GLOBAL_CONFIG_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            return true;
        } catch (Throwable e) {
            ServerConstants.LOGGER.e(e, "write global auto grant");
            return false;
        }
    }
}
