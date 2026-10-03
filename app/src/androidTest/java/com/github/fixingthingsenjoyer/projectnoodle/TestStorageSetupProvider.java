package com.github.fixingthingsenjoyer.projectnoodle;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.File;

/** Test-only SAF grant bootstrap. Java avoids relying on the target APK's Kotlin runtime. */
public class TestStorageSetupProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"reset".equals(method) || arg == null) throw new IllegalArgumentException();
        Context owner = getContext();
        File root = new File(owner.getFilesDir(), "server-test");
        deleteTree(root);
        root.mkdirs();
        Uri tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root");
        owner.grantUriPermission(arg, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Bundle result = new Bundle();
        result.putString("uri", tree.toString());
        return result;
    }

    static boolean deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        return file.delete();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
