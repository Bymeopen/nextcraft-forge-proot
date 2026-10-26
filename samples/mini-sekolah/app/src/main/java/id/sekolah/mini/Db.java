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
