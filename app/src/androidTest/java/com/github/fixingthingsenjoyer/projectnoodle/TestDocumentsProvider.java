package com.github.fixingthingsenjoyer.projectnoodle;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Private sandbox served only from the instrumentation APK; never accesses device storage. */
public class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.github.fixingthingsenjoyer.projectnoodle.test.documents";
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private File root() { File root = new File(getContext().getFilesDir(), "server-test"); root.mkdirs(); return root; }
    @Override public boolean onCreate() { return true; }
    @Override public boolean isChildDocument(String parent, String child) {
        return child.equals(parent) || child.startsWith(parent + "/");
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection == null ? new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE
        } : projection);
        result.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, "root")
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
            .add(DocumentsContract.Root.COLUMN_TITLE, "Test folder");
        return result;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        addDocument(result, id, resolve(id));
        return result;
    }

    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        File[] children = resolve(id).listFiles();
        if (children != null) for (File child : children) addDocument(result, id + "/" + child.getName(), child);
        return result;
    }

    private void addDocument(MatrixCursor cursor, String id, File file) {
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "root".equals(id) ? "Test folder" : file.getName());
        values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream");
        values.put(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME | (file.isDirectory() ?
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE));
        values.put(DocumentsContract.Document.COLUMN_SIZE, file.length());
        values.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        Object[] row = new Object[cursor.getColumnCount()];
        String[] names = cursor.getColumnNames();
        for (int index = 0; index < names.length; index++) row[index] = values.get(names[index]);
        cursor.addRow(row);
    }

    private File resolve(String id) throws FileNotFoundException {
        if (!"root".equals(id) && !id.startsWith("root/")) throw new FileNotFoundException();
        File root = root();
        File file = "root".equals(id) ? root : new File(root, id.substring(5));
        try {
            if (!file.getCanonicalPath().equals(root.getCanonicalPath()) &&
                    !file.getCanonicalPath().startsWith(root.getCanonicalPath() + "/")) throw new FileNotFoundException();
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return file;
    }

    private void validateName(String name) {
        if (name.trim().isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) throw new IllegalArgumentException();
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(id), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String createDocument(String id, String mime, String name) throws FileNotFoundException {
        validateName(name);
        File file = new File(resolve(id), name);
        try {
            boolean created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ? file.mkdir() : file.createNewFile();
            if (!created) throw new FileNotFoundException();
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return id + "/" + name;
    }

    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        validateName(name);
        File source = resolve(id);
        if (!source.renameTo(new File(source.getParentFile(), name))) throw new FileNotFoundException();
        return id.substring(0, id.lastIndexOf('/')) + "/" + name;
    }

    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!TestStorageSetupProvider.deleteTree(resolve(id))) throw new FileNotFoundException();
    }
}
