#!/usr/bin/env bash
# Creates the Mini Sekolah sample (students + grades in SQLite, plain Android framework, Java).
# Usage: bash make-project.sh [target-dir]   (default /workspace/mini-sekolah)
set -eu
DEST="${1:-/workspace/mini-sekolah}"
mkdir -p "$DEST"
cd "$DEST"
mkdir -p "$(dirname 'README.md')"
cat > 'README.md' <<'FORGE_EOF_0'
# Mini Sekolah

Sampel kecil untuk menguji toolchain Android di perangkat: database sekolah mini (siswa dan nilai) dengan SQLite,
widget bawaan Android, Java, tanpa dependensi tambahan.

- Daftar siswa dengan pencarian (nama, NIS, kelas), tambah, ubah (tekan lama), hapus (tekan lama).
- Layar siswa: daftar nilai per mata pelajaran, rata-rata berwarna, tambah nilai, hapus nilai (tekan lama).
- Menu titik tiga: "Isi data contoh" (5 siswa dengan nilai).
- NIS unik; hapus siswa ikut menghapus nilainya (ON DELETE CASCADE).

Build: `gradle --no-daemon :app:assembleDebug` di folder ini. APK ada di `app/build/outputs/apk/debug/`.
`make-project.sh` membuat ulang seluruh proyek ini dari satu berkas (dibuat oleh `scripts/make-mini-sekolah-script.py`).
FORGE_EOF_0
mkdir -p "$(dirname 'build.gradle.kts')"
cat > 'build.gradle.kts' <<'FORGE_EOF_1'
plugins {
    id("com.android.application") version "9.4.1" apply false
    // Pins the Kotlin that AGP 9 builds with to the one in the device's offline Maven cache (AGP would otherwise ask for 2.2.10).
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
FORGE_EOF_1
mkdir -p "$(dirname 'gradle.properties')"
cat > 'gradle.properties' <<'FORGE_EOF_2'
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
android.nonTransitiveRClass=true
FORGE_EOF_2
mkdir -p "$(dirname 'settings.gradle.kts')"
cat > 'settings.gradle.kts' <<'FORGE_EOF_3'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MiniSekolah"
include(":app")
FORGE_EOF_3
mkdir -p "$(dirname 'app/build.gradle.kts')"
cat > 'app/build.gradle.kts' <<'FORGE_EOF_4'
plugins {
    id("com.android.application")
}

android {
    namespace = "id.sekolah.mini"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.sekolah.mini"
        minSdk = 26
        // 34 on purpose: keeps the plain framework action bar below the status bar (no edge-to-edge enforcement).
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
FORGE_EOF_4
mkdir -p "$(dirname 'app/src/main/AndroidManifest.xml')"
cat > 'app/src/main/AndroidManifest.xml' <<'FORGE_EOF_5'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.Light.DarkActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustPan">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".StudentActivity"
            android:exported="false" />
    </application>
</manifest>
FORGE_EOF_5
mkdir -p "$(dirname 'app/src/main/java/id/sekolah/mini/Db.java')"
cat > 'app/src/main/java/id/sekolah/mini/Db.java' <<'FORGE_EOF_6'
package id.sekolah.mini;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole database: students and their grades. Plain SQLite, no libraries.
 * Adding a student with a NIS that already exists throws SQLiteConstraintException.
 */
final class Db extends SQLiteOpenHelper {
    private static final String NAME = "sekolah.db";
    private static final int VERSION = 1;

    Db(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // Needed for ON DELETE CASCADE: deleting a student removes the grades too.
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE students ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "nis TEXT NOT NULL UNIQUE, "
                + "name TEXT NOT NULL, "
                + "kelas TEXT NOT NULL)");
        db.execSQL("CREATE TABLE grades ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "student_id INTEGER NOT NULL REFERENCES students(id) ON DELETE CASCADE, "
                + "subject TEXT NOT NULL, "
                + "score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100))");
        db.execSQL("CREATE INDEX grades_student ON grades(student_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the only schema so far.
    }

    long addStudent(String nis, String name, String kelas) {
        return getWritableDatabase().insertOrThrow("students", null, studentValues(nis, name, kelas));
    }

    void updateStudent(long id, String nis, String name, String kelas) {
        getWritableDatabase().update("students", studentValues(nis, name, kelas), "id = ?", new String[] {String.valueOf(id)});
    }

    void deleteStudent(long id) {
        getWritableDatabase().delete("students", "id = ?", new String[] {String.valueOf(id)});
    }

    /** Students whose name, NIS or class contains [query], with their average and grade count. */
    List<Student> students(String query) {
        String like = "%" + query.trim() + "%";
        List<Student> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.id, s.nis, s.name, s.kelas, AVG(g.score), COUNT(g.id) "
                        + "FROM students s LEFT JOIN grades g ON g.student_id = s.id "
                        + "WHERE s.name LIKE ? OR s.nis LIKE ? OR s.kelas LIKE ? "
                        + "GROUP BY s.id ORDER BY s.name COLLATE NOCASE",
                new String[] {like, like, like})) {
            while (c.moveToNext()) {
                result.add(new Student(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.isNull(4) ? Double.NaN : c.getDouble(4), c.getInt(5)));
            }
        }
        return result;
    }

    /** One student, or null when the id no longer exists. */
    Student student(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.id, s.nis, s.name, s.kelas, AVG(g.score), COUNT(g.id) "
                        + "FROM students s LEFT JOIN grades g ON g.student_id = s.id "
                        + "WHERE s.id = ? GROUP BY s.id",
                new String[] {String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            return new Student(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.isNull(4) ? Double.NaN : c.getDouble(4), c.getInt(5));
        }
    }

    int studentCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM students", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Mean of every grade in the school, NaN when there are none. */
    double overallAverage() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT AVG(score) FROM grades", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN;
        }
    }

    List<Grade> grades(long studentId) {
        List<Grade> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, subject, score FROM grades WHERE student_id = ? ORDER BY subject COLLATE NOCASE, id",
                new String[] {String.valueOf(studentId)})) {
            while (c.moveToNext()) result.add(new Grade(c.getLong(0), c.getString(1), c.getInt(2)));
        }
        return result;
    }

    void addGrade(long studentId, String subject, int score) {
        ContentValues values = new ContentValues();
        values.put("student_id", studentId);
        values.put("subject", subject);
        values.put("score", score);
        getWritableDatabase().insertOrThrow("grades", null, values);
    }

    void deleteGrade(long gradeId) {
        getWritableDatabase().delete("grades", "id = ?", new String[] {String.valueOf(gradeId)});
    }

    /** A few students with grades, so the screens can be tried right away. Existing NIS values are skipped. */
    void seedDemo() {
        String[][] people = {
            {"1001", "Andi Pratama", "7A"},
            {"1002", "Budi Santoso", "7A"},
            {"1003", "Citra Lestari", "7B"},
            {"1004", "Dewi Anggraini", "7B"},
            {"1005", "Eko Wijaya", "8A"},
        };
        String[] subjects = {"Matematika", "Bahasa Indonesia", "IPA"};
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < people.length; i++) {
                ContentValues values = studentValues(people[i][0], people[i][1], people[i][2]);
                long id = db.insertWithOnConflict("students", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (id < 0) continue;
                for (int j = 0; j < subjects.length; j++) {
                    ContentValues grade = new ContentValues();
                    grade.put("student_id", id);
                    grade.put("subject", subjects[j]);
                    grade.put("score", 55 + (i * 7 + j * 11) % 45);
                    db.insert("grades", null, grade);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static ContentValues studentValues(String nis, String name, String kelas) {
        ContentValues values = new ContentValues();
        values.put("nis", nis);
        values.put("name", name);
        values.put("kelas", kelas);
        return values;
    }
}
FORGE_EOF_6
mkdir -p "$(dirname 'app/src/main/java/id/sekolah/mini/Format.java')"
cat > 'app/src/main/java/id/sekolah/mini/Format.java' <<'FORGE_EOF_7'
package id.sekolah.mini;

import android.content.Context;

import java.util.Locale;

/** Small helpers for showing averages the same way on every screen. */
final class Format {
    private Format() {}

    static String average(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.forLanguageTag("id-ID"), "%.1f", value);
    }

    /** Green from 75, red below 60, amber in between; grey when there is no grade. */
    static int scoreColor(Context context, double value) {
        int res;
        if (Double.isNaN(value)) res = R.color.muted;
        else if (value >= 75) res = R.color.good;
        else if (value < 60) res = R.color.bad;
        else res = R.color.warn;
        return context.getColor(res);
    }
}
FORGE_EOF_7
mkdir -p "$(dirname 'app/src/main/java/id/sekolah/mini/Grade.java')"
cat > 'app/src/main/java/id/sekolah/mini/Grade.java' <<'FORGE_EOF_8'
package id.sekolah.mini;

/** One score of one student in one subject. */
final class Grade {
    final long id;
    final String subject;
    final int score;

    Grade(long id, String subject, int score) {
        this.id = id;
        this.subject = subject;
        this.score = score;
    }
}
FORGE_EOF_8
mkdir -p "$(dirname 'app/src/main/java/id/sekolah/mini/MainActivity.java')"
cat > 'app/src/main/java/id/sekolah/mini/MainActivity.java' <<'FORGE_EOF_9'
package id.sekolah.mini;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteConstraintException;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

/** Student list with search, add, edit and delete. Tapping a student opens their grades. */
public class MainActivity extends Activity {
    private Db db;
    private StudentAdapter adapter;
    private EditText search;
    private TextView summary;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new Db(this);
        search = findViewById(R.id.search);
        summary = findViewById(R.id.summary);
        empty = findViewById(R.id.empty);

        ListView list = findViewById(R.id.list);
        adapter = new StudentAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent open = new Intent(this, StudentActivity.class);
            open.putExtra(StudentActivity.EXTRA_ID, adapter.getItem(position).id);
            startActivity(open);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(adapter.getItem(position));
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });
        findViewById(R.id.add).setOnClickListener(v -> showStudentDialog(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.seed_demo);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            db.seedDemo();
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        String query = search.getText().toString();
        adapter.clear();
        adapter.addAll(db.students(query));
        adapter.notifyDataSetChanged();
        empty.setText(query.trim().isEmpty() ? R.string.empty_students : R.string.empty_search);
        int count = db.studentCount();
        summary.setText(count == 0
                ? getString(R.string.summary_none)
                : getString(R.string.summary, count, Format.average(db.overallAverage())));
    }

    private void showActions(final Student student) {
        new AlertDialog.Builder(this)
                .setTitle(student.name)
                .setItems(new CharSequence[] {getString(R.string.edit), getString(R.string.delete)}, (dialog, which) -> {
                    if (which == 0) showStudentDialog(student);
                    else confirmDelete(student);
                })
                .show();
    }

    private void confirmDelete(final Student student) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_student)
                .setMessage(getString(R.string.delete_student_message, student.name))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    db.deleteStudent(student.id);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Add form when [existing] is null, edit form otherwise. Stays open until the input is valid. */
    private void showStudentDialog(final Student existing) {
        View form = getLayoutInflater().inflate(R.layout.dialog_student, null);
        final EditText nis = form.findViewById(R.id.nis);
        final EditText name = form.findViewById(R.id.name);
        final EditText kelas = form.findViewById(R.id.kelas);
        if (existing != null) {
            nis.setText(existing.nis);
            name.setText(existing.name);
            kelas.setText(existing.kelas);
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.add_student : R.string.edit_student)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String nisText = nis.getText().toString().trim();
            String nameText = name.getText().toString().trim();
            String kelasText = kelas.getText().toString().trim();
            boolean valid = true;
            if (nisText.isEmpty()) {
                nis.setError(getString(R.string.required));
                valid = false;
            }
            if (nameText.isEmpty()) {
                name.setError(getString(R.string.required));
                valid = false;
            }
            if (kelasText.isEmpty()) {
                kelas.setError(getString(R.string.required));
                valid = false;
            }
            if (!valid) return;
            try {
                if (existing == null) db.addStudent(nisText, nameText, kelasText);
                else db.updateStudent(existing.id, nisText, nameText, kelasText);
                dialog.dismiss();
                refresh();
            } catch (SQLiteConstraintException e) {
                nis.setError(getString(R.string.nis_taken));
            }
        });
    }

    private static final class StudentAdapter extends ArrayAdapter<Student> {
        StudentAdapter(Context context) {
            super(context, 0, new ArrayList<Student>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null
                    ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_student, parent, false);
            Student student = getItem(position);
            ((TextView) row.findViewById(R.id.name)).setText(student.name);
            ((TextView) row.findViewById(R.id.meta)).setText(
                    getContext().getString(R.string.student_meta, student.nis, student.kelas));
            TextView average = row.findViewById(R.id.average);
            average.setText(Format.average(student.average));
            average.setTextColor(Format.scoreColor(getContext(), student.average));
            return row;
        }
    }
}
FORGE_EOF_9
mkdir -p "$(dirname 'app/src/main/java/id/sekolah/mini/Student.java')"
cat > 'app/src/main/java/id/sekolah/mini/Student.java' <<'FORGE_EOF_10'
package id.sekolah.mini;

/** One row of the students table plus the figures computed from that student's grades. */
final class Student {
    final long id;
    final String nis;
    final String name;
    final String kelas;
    /** Mean score, or NaN when the student has no grades yet. */
    final double average;
    final int gradeCount;

    Student(long id, String nis, String name, String kelas, double average, int gradeCount) {
        this.id = id;
        this.nis = nis;
        this.name = name;
        this.kelas = kelas;
        this.average = average;
        this.gradeCount = gradeCount;
    }
}
FORGE_EOF_10
mkdir -p "$(dirname 'app/src/main/java/id/sekolah/mini/StudentActivity.java')"
cat > 'app/src/main/java/id/sekolah/mini/StudentActivity.java' <<'FORGE_EOF_11'
package id.sekolah.mini;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** One student: their details, the average, and the list of grades with add and delete. */
public class StudentActivity extends Activity {
    static final String EXTRA_ID = "student_id";

    private Db db;
    private long studentId;
    private GradeAdapter adapter;
    private TextView title;
    private TextView meta;
    private TextView average;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        db = new Db(this);
        studentId = getIntent().getLongExtra(EXTRA_ID, -1L);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        average = findViewById(R.id.average);

        ListView list = findViewById(R.id.list);
        adapter = new GradeAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.getItem(position));
            return true;
        });
        findViewById(R.id.add).setOnClickListener(v -> showGradeDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        Student student = db.student(studentId);
        if (student == null) {
            // Deleted elsewhere: nothing to show.
            Toast.makeText(this, R.string.student_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setTitle(student.name);
        title.setText(student.name);
        meta.setText(getString(R.string.student_meta, student.nis, student.kelas));
        if (student.gradeCount == 0) {
            average.setText(R.string.no_average);
        } else {
            average.setText(getString(R.string.average_label, Format.average(student.average)));
        }
        average.setTextColor(Format.scoreColor(this, student.average));
        adapter.clear();
        adapter.addAll(db.grades(studentId));
        adapter.notifyDataSetChanged();
    }

    private void confirmDelete(final Grade grade) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_grade)
                .setMessage(getString(R.string.delete_grade_message, grade.subject))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    db.deleteGrade(grade.id);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showGradeDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_grade, null);
        final EditText subject = form.findViewById(R.id.subject);
        final EditText score = form.findViewById(R.id.score);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_grade)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String subjectText = subject.getText().toString().trim();
            boolean valid = true;
            if (subjectText.isEmpty()) {
                subject.setError(getString(R.string.required));
                valid = false;
            }
            int value = -1;
            try {
                value = Integer.parseInt(score.getText().toString().trim());
            } catch (NumberFormatException ignored) {
                // Falls through to the range message below.
            }
            if (value < 0 || value > 100) {
                score.setError(getString(R.string.score_range));
                valid = false;
            }
            if (!valid) return;
            db.addGrade(studentId, subjectText, value);
            dialog.dismiss();
            refresh();
        });
    }

    private static final class GradeAdapter extends ArrayAdapter<Grade> {
        GradeAdapter(Context context) {
            super(context, 0, new ArrayList<Grade>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null
                    ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_grade, parent, false);
            Grade grade = getItem(position);
            ((TextView) row.findViewById(R.id.subject)).setText(grade.subject);
            TextView score = row.findViewById(R.id.score);
            score.setText(String.valueOf(grade.score));
            score.setTextColor(Format.scoreColor(getContext(), grade.score));
            return row;
        }
    }
}
FORGE_EOF_11
mkdir -p "$(dirname 'app/src/main/res/layout/activity_main.xml')"
cat > 'app/src/main/res/layout/activity_main.xml' <<'FORGE_EOF_12'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/summary"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="@color/muted"
        android:textSize="14sp" />

    <EditText
        android:id="@+id/search"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:hint="@string/search_hint"
        android:imeOptions="actionSearch"
        android:inputType="text"
        android:minHeight="48dp" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        android:layout_weight="1">

        <ListView
            android:id="@+id/list"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <TextView
            android:id="@+id/empty"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:gravity="center"
            android:padding="24dp"
            android:textColor="@color/muted"
            android:textSize="16sp" />
    </FrameLayout>

    <Button
        android:id="@+id/add"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:minHeight="48dp"
        android:text="@string/add_student" />
</LinearLayout>
FORGE_EOF_12
mkdir -p "$(dirname 'app/src/main/res/layout/activity_student.xml')"
cat > 'app/src/main/res/layout/activity_student.xml' <<'FORGE_EOF_13'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="22sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/meta"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="@color/muted"
        android:textSize="14sp" />

    <TextView
        android:id="@+id/average"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="16sp"
        android:textStyle="bold" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        android:layout_weight="1">

        <ListView
            android:id="@+id/list"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <TextView
            android:id="@+id/empty"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:gravity="center"
            android:padding="24dp"
            android:text="@string/empty_grades"
            android:textColor="@color/muted"
            android:textSize="16sp" />
    </FrameLayout>

    <Button
        android:id="@+id/add"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:minHeight="48dp"
        android:text="@string/add_grade" />
</LinearLayout>
FORGE_EOF_13
mkdir -p "$(dirname 'app/src/main/res/layout/dialog_grade.xml')"
cat > 'app/src/main/res/layout/dialog_grade.xml' <<'FORGE_EOF_14'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingStart="24dp"
    android:paddingTop="8dp"
    android:paddingEnd="24dp">

    <EditText
        android:id="@+id/subject"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/subject"
        android:inputType="textCapWords"
        android:minHeight="48dp" />

    <EditText
        android:id="@+id/score"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/score"
        android:inputType="number"
        android:minHeight="48dp" />
</LinearLayout>
FORGE_EOF_14
mkdir -p "$(dirname 'app/src/main/res/layout/dialog_student.xml')"
cat > 'app/src/main/res/layout/dialog_student.xml' <<'FORGE_EOF_15'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingStart="24dp"
    android:paddingTop="8dp"
    android:paddingEnd="24dp">

    <EditText
        android:id="@+id/nis"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/nis"
        android:inputType="number"
        android:minHeight="48dp" />

    <EditText
        android:id="@+id/name"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/name"
        android:inputType="textPersonName|textCapWords"
        android:minHeight="48dp" />

    <EditText
        android:id="@+id/kelas"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/kelas"
        android:inputType="textCapCharacters"
        android:minHeight="48dp" />
</LinearLayout>
FORGE_EOF_15
mkdir -p "$(dirname 'app/src/main/res/layout/item_grade.xml')"
cat > 'app/src/main/res/layout/item_grade.xml' <<'FORGE_EOF_16'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="52dp"
    android:orientation="horizontal">

    <TextView
        android:id="@+id/subject"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="16sp" />

    <TextView
        android:id="@+id/score"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="16sp"
        android:textStyle="bold" />
</LinearLayout>
FORGE_EOF_16
mkdir -p "$(dirname 'app/src/main/res/layout/item_student.xml')"
cat > 'app/src/main/res/layout/item_student.xml' <<'FORGE_EOF_17'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="64dp"
    android:orientation="horizontal"
    android:paddingTop="8dp"
    android:paddingBottom="8dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/name"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="17sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/meta"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="@color/muted"
            android:textSize="13sp" />
    </LinearLayout>

    <TextView
        android:id="@+id/average"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:textSize="17sp"
        android:textStyle="bold" />
</LinearLayout>
FORGE_EOF_17
mkdir -p "$(dirname 'app/src/main/res/values/colors.xml')"
cat > 'app/src/main/res/values/colors.xml' <<'FORGE_EOF_18'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="good">#1B7F3B</color>
    <color name="warn">#8A5300</color>
    <color name="bad">#B3261E</color>
    <color name="muted">#5F6368</color>
</resources>
FORGE_EOF_18
mkdir -p "$(dirname 'app/src/main/res/values/strings.xml')"
cat > 'app/src/main/res/values/strings.xml' <<'FORGE_EOF_19'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Database Sekolah Mini</string>
    <string name="search_hint">Cari nama, NIS, atau kelas</string>
    <string name="add_student">Tambah siswa</string>
    <string name="edit_student">Ubah siswa</string>
    <string name="delete_student">Hapus siswa</string>
    <string name="delete_student_message">Hapus %1$s beserta semua nilainya?</string>
    <string name="add_grade">Tambah nilai</string>
    <string name="delete_grade">Hapus nilai</string>
    <string name="delete_grade_message">Hapus nilai %1$s?</string>
    <string name="seed_demo">Isi data contoh</string>
    <string name="save">Simpan</string>
    <string name="cancel">Batal</string>
    <string name="delete">Hapus</string>
    <string name="edit">Ubah</string>
    <string name="nis">NIS</string>
    <string name="name">Nama</string>
    <string name="kelas">Kelas</string>
    <string name="subject">Mata pelajaran</string>
    <string name="score">Nilai (0-100)</string>
    <string name="required">Wajib diisi</string>
    <string name="nis_taken">NIS sudah dipakai siswa lain</string>
    <string name="score_range">Isi angka 0 sampai 100</string>
    <string name="empty_students">Belum ada siswa.\nKetuk Tambah siswa, atau menu titik tiga lalu Isi data contoh.</string>
    <string name="empty_search">Tidak ada siswa yang cocok.</string>
    <string name="empty_grades">Belum ada nilai.</string>
    <string name="summary">%1$d siswa · rata-rata %2$s</string>
    <string name="summary_none">0 siswa</string>
    <string name="student_meta">NIS %1$s · Kelas %2$s</string>
    <string name="grade_count">%1$d nilai</string>
    <string name="average_label">Rata-rata %1$s</string>
    <string name="no_average">Belum ada nilai</string>
    <string name="student_missing">Data siswa tidak ditemukan.</string>
</resources>
FORGE_EOF_19
echo "Proyek siap di $DEST"
