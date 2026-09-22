package com.bucket;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URLConnection;

/** Serves downloaded files to other apps without an androidx dependency.
 *
 *  FileOpener stages a server file under getCacheDir()/shared and hands the
 *  receiving app a content://com.bucket.cache/&lt;name&gt; URI with a read
 *  grant. A custom provider (instead of FileProvider, which would need the
 *  androidx core artifact the no-Gradle build deliberately avoids) keeps the
 *  build at five SDK tool invocations. Only that one directory is ever
 *  exposed, names are flattened, and ".." is rejected. */
public class CacheProvider extends ContentProvider {
    static final String AUTHORITY = "com.bucket.cache";

    static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(file.getName())
                .build();
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        String mime = URLConnection.guessContentTypeFromName(
                uri.getLastPathSegment());
        return mime != null ? mime : "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")
                || name.contains("\\")) {
            throw new FileNotFoundException("bad name");
        }
        if (getContext() == null) throw new FileNotFoundException("no context");
        File file = new File(new File(getContext().getCacheDir(), "shared"), name);
        if (!file.isFile()) throw new FileNotFoundException("missing");
        return ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        String name = uri.getLastPathSegment();
        MatrixCursor cursor = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (name != null && getContext() != null) {
            File file = new File(
                    new File(getContext().getCacheDir(), "shared"), name);
            if (file.isFile()) {
                cursor.addRow(new Object[]{file.getName(), file.length()});
            }
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }
}
